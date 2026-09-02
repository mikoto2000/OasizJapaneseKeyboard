package dev.mikoto2000.oasizjapanesekeyboard.ime

import android.inputmethodservice.InputMethodService
import android.content.Context
import android.view.KeyEvent
import android.view.Gravity
import android.os.SystemClock
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.PopupWindow
import android.widget.TextView
import java.util.concurrent.Executors
import dev.mikoto2000.oasizjapanesekeyboard.R

class JapaneseKeyboardService : InputMethodService() {
    private companion object {
        const val REMAINING_CANDIDATES_DELAY_MS = 120L
        const val COMPOSING_SUGGESTION_DELAY_MS = 35L
        const val PREFS_NAME = "japanese_keyboard_service"
        const val PREF_KEY_LAYOUT_MODE = "layout_mode"
        const val LAYOUT_JIS_QWERTY = "jis_qwerty"
        const val LAYOUT_KANA_12_SWIPE = "kana_12_swipe"
        const val LAYOUT_KANA_12_SYMBOL = "kana_12_symbol"
        const val LAYOUT_ENGLISH_12 = "english_12"
    }

    private var shiftOn = false
    private var ctrlOn = false
    private var shiftBtn: Button? = null
    private var shiftBtnRight: Button? = null
    private var ctrlBtn: Button? = null
    private var langBtn: Button? = null
    private var rootViewRef: View? = null
    private var feedbackEnabled = true
    private val repeatHandler = Handler(Looper.getMainLooper())
    private val repeatTasks = mutableMapOf<View, Runnable>()
    private val letterButtons = mutableListOf<Button>()
    private val symbolButtons = mutableListOf<Pair<Button, String>>()
    private var fnVisible = true
    private var functionRow: View? = null
    private var layoutMode = LAYOUT_JIS_QWERTY
    private var flickGuidePopup: PopupWindow? = null
    private var symbolPageIndex = 0
    private val symbolPageButtons = mutableListOf<Pair<Button, Boolean>>()
    private var symbolPageButton: Button? = null
    private val symbolNumberFlicks = listOf(
        "1", "2", "3",
        "4", "5", "6",
        "7", "8", "9",
        "!?#", "0", "="
    )

    // Kana composing state
    private var kanaMode = false // default: ASCII mode
    private val romaji = RomajiConverter()

    // Conversion (candidates) state
    private var conversionReading: String? = null
    private var candidates: List<String> = emptyList()
    private var selectedCandidateIndex: Int = 0
    private var suggestionQuerySeq: Long = 0L
    private var suggestionTask: Runnable? = null
    private var candidatesRoot: View? = null
    private var segmentControls: View? = null
    private var segmentList: ViewGroup? = null
    private var candidateContainer: View? = null
    private var candidateList: ViewGroup? = null
    private var converter: JapaneseConverter = SimpleConverter()
    private val convExecutor = Executors.newSingleThreadExecutor()
    // Slow prefix prediction must never queue in front of the next exact TOP 5 lookup.
    private val predictionExecutor = Executors.newSingleThreadExecutor()
    private var convQuerySeq: Long = 0L
    private var sqliteConverter: SqliteDictionaryConverter? = null

    // Segment conversion state
    private data class Segment(
        var reading: String,
        var candidates: MutableList<String> = mutableListOf(),
        var selectedIndex: Int = 0,
        var loading: Boolean = false
    )
    private var segments: MutableList<Segment>? = null
    private var segmentFocus: Int = 0

    private val shiftSymbolMap: Map<String, String> = mapOf(
        // Number row
        "1" to "!",
        "2" to "\"",
        "3" to "#",
        "4" to "$",
        "5" to "%",
        "6" to "&",
        "7" to "'",
        "8" to "(",
        "9" to ")",
        "0" to ")",
        "-" to "=",
        "^" to "~",
        "¥" to "|",
        // Right side of Q row
        "@" to "`",
        "[" to "{",
        // Home row right side
        ";" to "+",
        ":" to "*",
        "]" to "}",
        // Bottom row
        "," to "<",
        "." to ">",
        "/" to "?",
        "\\" to "_"
    )

    override fun onCreate() {
        super.onCreate()
        layoutMode = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(PREF_KEY_LAYOUT_MODE, LAYOUT_JIS_QWERTY) ?: LAYOUT_JIS_QWERTY
        kanaMode = isJapanese12Mode(layoutMode)
        sqliteConverter = try {
            SqliteDictionaryConverter(this).also { converter ->
                convExecutor.execute {
                    try {
                        converter.preload()
                    } catch (_: Exception) {
                        // ignore and fallback at query time if needed
                    }
                }
            }
        } catch (_: Exception) {
            null
        }
    }

    override fun onFinishInput() {
        finishCurrentInputSession()
        super.onFinishInput()
    }

    override fun onCreateInputView(): View {
        val root = layoutInflater.inflate(currentKeyboardLayoutRes(), null)
        rootViewRef = root
        letterButtons.clear()
        symbolButtons.clear()
        symbolPageButtons.clear()
        symbolPageButton = null
        // Initialize converter: prefer SQLite dictionary; fallback to TSV; then to simple built-in
        converter = sqliteConverter ?: try {
            DictionaryConverter(this)
        } catch (_: Exception) {
            SimpleConverter()
        }

        // Wire generic keys by tag
        wireKeysRecursively(root)

        // Special keys (repeat enabled)
        root.findViewById<View>(R.id.key_backspace)?.let { v ->
            setRepeatableKey(v, initialDelay = 350L, repeatInterval = 60L) {
                deleteText()
                consumeOneShotModifiers()
            }
        }
        root.findViewById<View>(R.id.key_enter)?.let { v ->
            setRepeatableKey(v) {
                sendEnter()
                consumeOneShotModifiers()
            }
        }
        root.findViewById<View>(R.id.key_space)?.let { v ->
            setRepeatableKey(v, initialDelay = 400L, repeatInterval = 150L) {
                if (kanaMode) {
                    if (isInConversion()) {
                        val segs = segments
                        if (segs != null && segs.isNotEmpty()) {
                            val focus = segs[segmentFocus]
                            val size = if (focus.candidates.isNotEmpty()) focus.candidates.size else 1
                            if (size > 0) {
                                focus.selectedIndex = (focus.selectedIndex + 1) % size
                                updateCandidatesUI()
                                updateSegmentsUI()
                                updateComposingFromSegments()
                            }
                        } else if (candidates.isNotEmpty()) {
                            selectedCandidateIndex = (selectedCandidateIndex + 1) % candidates.size
                            updateCandidateSelectionUI()
                        }
                    } else if (romaji.hasComposing()) {
                        // start conversion
                        startConversion()
                    } else {
                        commitText(" ")
                    }
                } else {
                    commitText(" ")
                    consumeOneShotModifiers()
                }
            }
        }

        shiftBtn = root.findViewById<Button>(R.id.key_shift)
        shiftBtn?.setOnClickListener {
            if (!kanaMode) {
                shiftOn = !shiftOn
                updateShiftUI()
            }
        }
        updateShiftUI()
        if (layoutMode == LAYOUT_ENGLISH_12) {
            (shiftBtn as? FlickKeyButton)?.let { btn ->
                btn.setFlickHints(left = "", up = "", right = "", down = "⌫")
                setCaseToggleOrDownDeleteKey(btn)
            }
        }

        shiftBtnRight = root.findViewById<Button>(R.id.key_shift_right)
        shiftBtnRight?.setOnClickListener {
            if (!kanaMode) {
                shiftOn = !shiftOn
                updateShiftUI()
            }
        }

        ctrlBtn = root.findViewById<Button>(R.id.key_ctrl)
        ctrlBtn?.setOnClickListener {
            if (!kanaMode) {
                ctrlOn = !ctrlOn
                updateCtrlUI()
            }
        }
        updateCtrlUI()

        // Language toggle (A <-> あ)
        langBtn = root.findViewById<Button>(R.id.key_lang_toggle)
        langBtn?.setOnClickListener {
            if (layoutMode != LAYOUT_JIS_QWERTY) {
                showLayoutModeMenu(it)
            } else {
                toggleKanaMode()
            }
        }
        updateLangToggleUI()

        // Arrow keys (repeat enabled)
        root.findViewById<View>(R.id.key_arrow_left)?.let { v ->
            setRepeatableKey(v) {
                if (kanaMode && isInConversion() && segments != null) {
                    moveSegmentFocus(-1)
                } else {
                    flushComposingOrConversionIfNeeded(); sendDpad(KeyEvent.KEYCODE_DPAD_LEFT); consumeOneShotModifiers()
                }
            }
        }
        root.findViewById<View>(R.id.key_arrow_right)?.let { v ->
            setRepeatableKey(v) {
                if (kanaMode && isInConversion() && segments != null) {
                    moveSegmentFocus(1)
                } else {
                    flushComposingOrConversionIfNeeded(); sendDpad(KeyEvent.KEYCODE_DPAD_RIGHT); consumeOneShotModifiers()
                }
            }
        }
        root.findViewById<View>(R.id.key_arrow_up)?.let { v ->
            setRepeatableKey(v) { flushComposingOrConversionIfNeeded(); sendDpad(KeyEvent.KEYCODE_DPAD_UP); consumeOneShotModifiers() }
        }
        root.findViewById<View>(R.id.key_arrow_down)?.let { v ->
            setRepeatableKey(v) { flushComposingOrConversionIfNeeded(); sendDpad(KeyEvent.KEYCODE_DPAD_DOWN); consumeOneShotModifiers() }
        }

        // ESC / TAB (repeat enabled)
        root.findViewById<View>(R.id.key_esc)?.let { v ->
            setRepeatableKey(v) { flushComposingOrConversionIfNeeded(); sendSimpleKey(KeyEvent.KEYCODE_ESCAPE); consumeOneShotModifiers() }
        }
        root.findViewById<View>(R.id.key_tab)?.let { v ->
            setRepeatableKey(v) { flushComposingOrConversionIfNeeded(); sendSimpleKey(KeyEvent.KEYCODE_TAB); consumeOneShotModifiers() }
        }

        // Function keys F1..F12 (repeat enabled)
        val fnMap = listOf(
            R.id.key_f1 to KeyEvent.KEYCODE_F1,
            R.id.key_f2 to KeyEvent.KEYCODE_F2,
            R.id.key_f3 to KeyEvent.KEYCODE_F3,
            R.id.key_f4 to KeyEvent.KEYCODE_F4,
            R.id.key_f5 to KeyEvent.KEYCODE_F5,
            R.id.key_f6 to KeyEvent.KEYCODE_F6,
            R.id.key_f7 to KeyEvent.KEYCODE_F7,
            R.id.key_f8 to KeyEvent.KEYCODE_F8,
            R.id.key_f9 to KeyEvent.KEYCODE_F9,
            R.id.key_f10 to KeyEvent.KEYCODE_F10,
            R.id.key_f11 to KeyEvent.KEYCODE_F11,
            R.id.key_f12 to KeyEvent.KEYCODE_F12,
        )
        for ((rid, code) in fnMap) {
            root.findViewById<View>(rid)?.let { v ->
                setRepeatableKey(v) { flushComposingOrConversionIfNeeded(); sendSimpleKey(code); consumeOneShotModifiers() }
            }
        }

        // Fn toggle (left of space): show/hide top function row
        functionRow = root.findViewById(R.id.row_fn)
        functionRow?.visibility = functionRowVisibility()
        root.findViewById<Button>(R.id.key_fn_toggle)?.let { btn ->
            btn.setOnClickListener {
                if (layoutMode != LAYOUT_JIS_QWERTY) {
                    showLayoutModeMenu(btn)
                } else {
                    fnVisible = !fnVisible
                    functionRow?.visibility = functionRowVisibility()
                    updateFnToggleUI(btn)
                }
            }
            btn.setOnLongClickListener {
                showLayoutModeMenu(btn)
                true
            }
            updateFnToggleUI(btn)
        }

        // Feedback toggle (left of space)
        root.findViewById<Button>(R.id.key_feedback_toggle)?.let { btn ->
            btn.setOnClickListener {
                feedbackEnabled = !feedbackEnabled
                updateFeedbackToggleUI(btn)
                applyKeyBackgrounds()
            }
            updateFeedbackToggleUI(btn)
        }

        // Candidate views
        candidatesRoot = root.findViewById(R.id.candidates_root)
        segmentControls = root.findViewById(R.id.segment_controls)
        segmentList = root.findViewById(R.id.segment_list)
        candidateContainer = root.findViewById(R.id.candidate_container)
        candidateList = root.findViewById(R.id.candidate_list)

        // Segment boundary adjust buttons
        root.findViewById<Button>(R.id.segment_shrink_right)?.setOnClickListener {
            adjustBoundaryRight(-1)
        }
        root.findViewById<Button>(R.id.segment_expand_right)?.setOnClickListener {
            adjustBoundaryRight(1)
        }

        // Apply initial backgrounds to all keys
        applyKeyBackgrounds()
        updateSymbolPageKeys()

        return root
    }

    private fun currentKeyboardLayoutRes(): Int {
        return when (layoutMode) {
            LAYOUT_KANA_12_SWIPE -> R.layout.keyboard_kana_12_swipe
            LAYOUT_KANA_12_SYMBOL -> R.layout.keyboard_kana_12_symbol
            LAYOUT_ENGLISH_12 -> R.layout.keyboard_english_12
            else -> R.layout.keyboard_jis_qwerty
        }
    }

    private fun wireKeysRecursively(view: View) {
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                wireKeysRecursively(view.getChildAt(i))
            }
            return
        }

        if (view is Button) {
            val tag = view.tag as? String ?: return
            when {
                tag.startsWith("letter:") -> {
                    val base = tag.removePrefix("letter:")
                    letterButtons.add(view)
                    // Initial label based on shift state
                    view.text = if (shiftOn) base.uppercase() else base.lowercase()
                    setRepeatableKey(view) {
                        if (kanaMode) {
                            handleKanaLetter(base)
                        } else {
                            val text = if (shiftOn) base.uppercase() else base.lowercase()
                            if (ctrlOn) {
                                val code = letterToKeyCode(base)
                                if (code != null) sendKeyWithMeta(code, KeyEvent.META_CTRL_ON) else commitText(text)
                            } else {
                                commitText(text)
                            }
                            consumeOneShotModifiers()
                        }
                    }
                }
                tag.startsWith("symbol:") -> {
                    val base = tag.removePrefix("symbol:")
                    symbolButtons.add(view to base)
                    // Initial label reflects current shift state
                    val label = if (shiftOn) shiftSymbolMap[base] ?: base else base
                    view.text = label
                    setRepeatableKey(view) {
                        val out = if (shiftOn) shiftSymbolMap[base] ?: base else base
                        if (kanaMode) {
                            if (out == "-") {
                                if (isInConversion()) cancelConversionRestore()
                                romaji.appendKana("ー")
                                updateComposingText()
                                return@setRepeatableKey
                            }
                            flushComposingOrConversionIfNeeded()
                        }
                        commitText(out)
                        if (!kanaMode) consumeOneShotModifiers()
                    }
                }
                tag.startsWith("kana_flick:") -> {
                    val parts = tag.removePrefix("kana_flick:").split(":")
                    if (parts.size >= 5) {
                        val flick = KanaFlickKey(
                            center = parts[0],
                            left = parts[1],
                            up = parts[2],
                            right = parts[3],
                            down = parts[4]
                        )
                        view.text = flick.center
                        (view as? FlickKeyButton)?.setFlickHints(
                            left = flick.left,
                            up = flick.up,
                            right = flick.right,
                            down = flick.down
                        )
                        setKanaFlickKey(view, flick)
                    }
                }
                tag == "action:kana_modifier" -> {
                    view.setOnClickListener {
                        applyKanaModifier()
                    }
                }
                tag.startsWith("action:commit:") -> {
                    val text = tag.removePrefix("action:commit:")
                    view.setOnClickListener {
                        flushComposingOrConversionIfNeeded()
                        commitText(text)
                    }
                }
                tag == "action:commit_space" -> {
                    view.setOnClickListener {
                        flushComposingOrConversionIfNeeded()
                        commitText(" ")
                    }
                }
                tag == "action:switch_jis_qwerty" -> {
                    view.setOnClickListener {
                        switchLayoutMode(LAYOUT_JIS_QWERTY)
                    }
                }
                tag == "action:mode_menu" -> {
                    view.setOnClickListener {
                        showLayoutModeMenu(view)
                    }
                }
                tag == "action:esc" -> {
                    setRepeatableKey(view) {
                        flushComposingOrConversionIfNeeded()
                        sendSimpleKey(KeyEvent.KEYCODE_ESCAPE)
                        consumeOneShotModifiers()
                    }
                }
                tag == "action:switch_kana_12_swipe" -> {
                    view.setOnClickListener {
                        switchLayoutMode(LAYOUT_KANA_12_SWIPE)
                    }
                }
                tag == "action:switch_kana_12_symbol" -> {
                    view.setOnClickListener {
                        switchLayoutMode(LAYOUT_KANA_12_SYMBOL)
                    }
                }
                tag == "action:switch_english_12" -> {
                    view.setOnClickListener {
                        switchLayoutMode(LAYOUT_ENGLISH_12)
                    }
                }
                tag == "action:symbol_page_next" -> {
                    symbolPageButton = view
                    view.setOnClickListener {
                        symbolPageIndex = (symbolPageIndex + 1) % symbolPages.size
                        updateSymbolPageKeys()
                    }
                }
                tag == "symbol_slot" || tag == "symbol_slot_down" -> {
                    symbolPageButtons.add(view to (tag == "symbol_slot_down"))
                }
                tag.startsWith("english_flick:") -> {
                    val parts = tag.removePrefix("english_flick:").split(":")
                    if (parts.size >= 5) {
                        val flick = KanaFlickKey(
                            center = decodeDirectFlickText(parts[0]),
                            left = decodeDirectFlickText(parts[1]),
                            up = decodeDirectFlickText(parts[2]),
                            right = decodeDirectFlickText(parts[3]),
                            down = decodeDirectFlickText(parts[4])
                        )
                        if (flick.center == " ") {
                            view.text = "空白"
                        }
                        (view as? FlickKeyButton)?.setFlickHints(
                            left = flick.left,
                            up = flick.up,
                            right = flick.right,
                            down = flick.down
                        )
                        setDirectFlickKey(view, flick)
                    }
                }
            }
        }
    }

    private fun decodeDirectFlickText(text: String): String {
        return when (text) {
            "space" -> " "
            "dq" -> "\""
            "colon" -> ":"
            "semicolon" -> ";"
            else -> text
        }
    }

    private data class KanaFlickKey(
        val center: String,
        val left: String,
        val up: String,
        val right: String,
        val down: String
    )

    private enum class FlickDirection {
        Center, Left, Up, Right, Down
    }

    private val symbolPages: List<List<String>> = listOf(
        listOf(
            "、", "。", "？",
            "！", "・", "…",
            "〜", "ー", "「",
            "」", "『", "』",
            "（", "）", "［",
            "］", "｛", "｝",
            "〃"
        ),
        listOf(
            "〈", "〉", "《",
            "》", "【", "】",
            "〔", "〕", "〝",
            "〟", "〃", "〆",
            "々", "ヶ", "※",
            "〒", "・", "／",
            "＼"
        ),
        listOf(
            "@", "#", "￥",
            "$", "%", "&",
            "*", "+", "-",
            "=", "/", "\\",
            ":", ";", "\"",
            "'", "^", "_",
            "!"
        ),
        listOf(
            "|", "※", "~",
            "`", "<", ">",
            "=", "+", "*",
            "÷", "×", "±",
            "≠", "≒", "≦",
            "≧", "∞", "√",
            "∴"
        ),
        listOf(
            "○", "◎", "△",
            "□", "☆", "★",
            "♪", "→", "←",
            "↑", "↓", "⇔",
            "℃", "€", "£",
            "©", "®", "™",
            "♡"
        )
    )

    private fun updateSymbolPageKeys() {
        if (symbolPageButtons.isEmpty()) return
        val page = symbolPages[symbolPageIndex.coerceIn(0, symbolPages.lastIndex)]
        symbolPageButton?.text = "記号${symbolPageIndex + 1}/${symbolPages.size}"
        var downIndex = 0
        symbolPageButtons.forEachIndexed { index, (btn, supportsDownFlick) ->
            val symbol = page.getOrNull(index)
            btn.text = symbol ?: ""
            btn.isEnabled = symbol != null
            btn.alpha = if (symbol != null) 1.0f else 0.35f
            val down = if (supportsDownFlick) {
                symbolNumberFlicks.getOrNull(downIndex++).orEmpty()
            } else {
                ""
            }
            (btn as? FlickKeyButton)?.setFlickHints(
                left = "",
                up = "",
                right = "",
                down = down
            )
            if (symbol != null) {
                setSymbolFlickKey(btn, center = symbol, down = down)
            } else {
                btn.setOnTouchListener(null)
                btn.setOnClickListener(null)
            }
        }
    }

    private fun updateShiftUI() {
        // Update labels for letter buttons
        for (btn in letterButtons) {
            val tag = btn.tag as? String ?: continue
            val base = tag.removePrefix("letter:")
            btn.text = if (shiftOn) base.uppercase() else base.lowercase()
        }
        // Update labels for symbol buttons
        for ((btn, base) in symbolButtons) {
            btn.text = if (shiftOn) shiftSymbolMap[base] ?: base else base
        }
        val active = shiftOn
        shiftBtn?.let { btn ->
            btn.text = if (layoutMode == LAYOUT_ENGLISH_12) {
                "a⇔A"
            } else if (active) {
                "Shift ON"
            } else {
                "Shift"
            }
            btn.isSelected = active
        }
        shiftBtnRight?.let { btn ->
            btn.text = if (active) "Shift ON" else "Shift"
            btn.isSelected = active
        }
    }

    private fun updateCtrlUI() {
        ctrlBtn?.let { btn ->
            btn.text = if (ctrlOn) "Ctrl ON" else "Ctrl"
            btn.isSelected = ctrlOn
            btn.isEnabled = !kanaMode
            btn.alpha = if (kanaMode) 0.5f else 1.0f
        }
    }

    private fun sendKeyWithMeta(keyCode: Int, meta: Int) {
        val now = SystemClock.uptimeMillis()
        val ic = currentInputConnection ?: return
        ic.sendKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_DOWN, keyCode, 0, meta))
        ic.sendKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_UP, keyCode, 0, meta))
    }

    private fun sendDpad(keyCode: Int) {
        val now = SystemClock.uptimeMillis()
        val ic = currentInputConnection ?: return
        ic.sendKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_DOWN, keyCode, 0))
        ic.sendKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_UP, keyCode, 0))
    }

    private fun sendSimpleKey(keyCode: Int) {
        val now = SystemClock.uptimeMillis()
        val ic = currentInputConnection ?: return
        ic.sendKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_DOWN, keyCode, 0))
        ic.sendKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_UP, keyCode, 0))
    }

    private fun updateFeedbackToggleUI(btn: Button) {
        btn.text = if (feedbackEnabled) "FX ON" else "FX OFF"
        btn.isSelected = feedbackEnabled
    }

    private fun applyKeyBackgrounds() {
        val root = rootViewRef as? ViewGroup ?: return
        applyKeyBackgroundsRec(root)
    }

    private fun applyKeyBackgroundsRec(view: View) {
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                applyKeyBackgroundsRec(view.getChildAt(i))
            }
            return
        }
        if (view is Button) {
            val bg = if (feedbackEnabled) R.drawable.key_bg_feedback else R.drawable.key_bg_static
            view.setBackgroundResource(bg)
        }
    }

    private fun updateFnToggleUI(btn: Button) {
        if (layoutMode != LAYOUT_JIS_QWERTY) {
            btn.text = when (layoutMode) {
                LAYOUT_KANA_12_SYMBOL -> "記号"
                LAYOUT_ENGLISH_12 -> "ABC"
                else -> "12 ON"
            }
            btn.isSelected = true
        } else {
            btn.text = if (fnVisible) "Fn ON" else "Fn OFF"
            btn.isSelected = fnVisible
        }
    }

    private fun functionRowVisibility(): Int {
        return if (layoutMode != LAYOUT_JIS_QWERTY) {
            View.GONE
        } else if (fnVisible) {
            View.VISIBLE
        } else {
            View.GONE
        }
    }

    private fun showLayoutModeMenu(anchor: View) {
        val menu = PopupMenu(this, anchor)
        menu.menu.add(0, 1, 0, "JIS QWERTY")
        menu.menu.add(0, 2, 1, "スワイプ入力 (12キー)")
        menu.menu.add(0, 3, 2, "記号入力")
        menu.menu.add(0, 4, 3, "英字入力 (12キー)")
        menu.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                1 -> switchLayoutMode(LAYOUT_JIS_QWERTY)
                2 -> switchLayoutMode(LAYOUT_KANA_12_SWIPE)
                3 -> switchLayoutMode(LAYOUT_KANA_12_SYMBOL)
                4 -> switchLayoutMode(LAYOUT_ENGLISH_12)
            }
            true
        }
        menu.show()
    }

    private fun switchLayoutMode(mode: String) {
        if (layoutMode == mode) return
        flushComposingOrConversionIfNeeded()
        hideKanaFlickGuide()
        layoutMode = mode
        kanaMode = isJapanese12Mode(mode)
        getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(PREF_KEY_LAYOUT_MODE, mode)
            .apply()
        setInputView(onCreateInputView())
    }

    private fun isJapanese12Mode(mode: String): Boolean {
        return mode == LAYOUT_KANA_12_SWIPE || mode == LAYOUT_KANA_12_SYMBOL
    }

    private fun consumeOneShotModifiers() {
        var changed = false
        if (shiftOn) { shiftOn = false; changed = true }
        if (ctrlOn) { ctrlOn = false; changed = true }
        if (changed) {
            updateShiftUI()
            updateCtrlUI()
        }
    }

    private fun setRepeatableKey(
        view: View,
        initialDelay: Long = 400L,
        repeatInterval: Long = 70L,
        action: () -> Unit
    ) {
        view.setOnTouchListener { v, ev ->
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    v.isPressed = true
                    // Fire immediately
                    action()
                    // Schedule repeats
                    val task = object : Runnable {
                        override fun run() {
                            action()
                            repeatHandler.postDelayed(this, repeatInterval)
                        }
                    }
                    repeatTasks[v] = task
                    repeatHandler.postDelayed(task, initialDelay)
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL, MotionEvent.ACTION_OUTSIDE -> {
                    v.isPressed = false
                    repeatTasks.remove(v)?.let { repeatHandler.removeCallbacks(it) }
                    true
                }
                else -> false
            }
        }
    }

    private fun setKanaFlickKey(button: Button, flick: KanaFlickKey) {
        val threshold = dp(26).toFloat()
        var downX = 0f
        var downY = 0f
        var currentDirection = FlickDirection.Center

        fun directionFor(ev: MotionEvent): FlickDirection {
            val dx = ev.x - downX
            val dy = ev.y - downY
            if (kotlin.math.hypot(dx.toDouble(), dy.toDouble()) < threshold) {
                return FlickDirection.Center
            }
            return if (kotlin.math.abs(dx) > kotlin.math.abs(dy)) {
                if (dx < 0f) FlickDirection.Left else FlickDirection.Right
            } else {
                if (dy < 0f) FlickDirection.Up else FlickDirection.Down
            }
        }

        button.setOnTouchListener { v, ev ->
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    v.isPressed = true
                    downX = ev.x
                    downY = ev.y
                    currentDirection = FlickDirection.Center
                    showKanaFlickGuide(button, flick, currentDirection)
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val nextDirection = directionFor(ev)
                    if (nextDirection != currentDirection) {
                        currentDirection = nextDirection
                        showKanaFlickGuide(button, flick, currentDirection)
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    v.isPressed = false
                    hideKanaFlickGuide()
                    handleKanaText(flickTextFor(flick, directionFor(ev)))
                    true
                }
                MotionEvent.ACTION_CANCEL, MotionEvent.ACTION_OUTSIDE -> {
                    v.isPressed = false
                    hideKanaFlickGuide()
                    true
                }
                else -> false
            }
        }
    }

    private fun setSymbolFlickKey(button: Button, center: String, down: String) {
        val threshold = dp(26).toFloat()
        var downX = 0f
        var downY = 0f

        fun isDownFlick(ev: MotionEvent): Boolean {
            val dx = ev.x - downX
            val dy = ev.y - downY
            return dy > threshold && kotlin.math.abs(dy) > kotlin.math.abs(dx)
        }

        button.setOnTouchListener { v, ev ->
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    v.isPressed = true
                    downX = ev.x
                    downY = ev.y
                    true
                }
                MotionEvent.ACTION_UP -> {
                    v.isPressed = false
                    flushComposingOrConversionIfNeeded()
                    commitText(if (isDownFlick(ev) && down.isNotEmpty()) down else center)
                    true
                }
                MotionEvent.ACTION_CANCEL, MotionEvent.ACTION_OUTSIDE -> {
                    v.isPressed = false
                    true
                }
                else -> true
            }
        }
    }

    private fun setDirectFlickKey(button: Button, flick: KanaFlickKey) {
        val threshold = dp(26).toFloat()
        var downX = 0f
        var downY = 0f

        fun directionFor(ev: MotionEvent): FlickDirection {
            val dx = ev.x - downX
            val dy = ev.y - downY
            if (kotlin.math.hypot(dx.toDouble(), dy.toDouble()) < threshold) {
                return FlickDirection.Center
            }
            return if (kotlin.math.abs(dx) > kotlin.math.abs(dy)) {
                if (dx < 0f) FlickDirection.Left else FlickDirection.Right
            } else {
                if (dy < 0f) FlickDirection.Up else FlickDirection.Down
            }
        }

        button.setOnTouchListener { v, ev ->
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    v.isPressed = true
                    downX = ev.x
                    downY = ev.y
                    true
                }
                MotionEvent.ACTION_UP -> {
                    v.isPressed = false
                    flushComposingOrConversionIfNeeded()
                    commitText(applyDirectFlickModifiers(flickTextFor(flick, directionFor(ev))))
                    consumeOneShotModifiers()
                    true
                }
                MotionEvent.ACTION_CANCEL, MotionEvent.ACTION_OUTSIDE -> {
                    v.isPressed = false
                    true
                }
                else -> true
            }
        }
    }

    private fun setCaseToggleOrDownDeleteKey(button: Button) {
        val threshold = dp(26).toFloat()
        var downX = 0f
        var downY = 0f

        fun isDownFlick(ev: MotionEvent): Boolean {
            val dx = ev.x - downX
            val dy = ev.y - downY
            return dy > threshold && kotlin.math.abs(dy) > kotlin.math.abs(dx)
        }

        button.setOnTouchListener { v, ev ->
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    v.isPressed = true
                    downX = ev.x
                    downY = ev.y
                    true
                }
                MotionEvent.ACTION_UP -> {
                    v.isPressed = false
                    if (isDownFlick(ev)) {
                        flushComposingOrConversionIfNeeded()
                        deleteText()
                        consumeOneShotModifiers()
                    } else if (!kanaMode) {
                        togglePreviousAsciiLetterCase()
                    }
                    true
                }
                MotionEvent.ACTION_CANCEL, MotionEvent.ACTION_OUTSIDE -> {
                    v.isPressed = false
                    true
                }
                else -> true
            }
        }
    }

    private fun togglePreviousAsciiLetterCase() {
        val ic = currentInputConnection ?: return
        val previous = ic.getTextBeforeCursor(1, 0)?.firstOrNull() ?: return
        if (previous !in 'A'..'Z' && previous !in 'a'..'z') return
        val toggled = if (previous.isUpperCase()) {
            previous.lowercaseChar()
        } else {
            previous.uppercaseChar()
        }
        ic.deleteSurroundingText(1, 0)
        ic.commitText(toggled.toString(), 1)
    }

    private fun applyDirectFlickModifiers(text: String): String {
        return if (shiftOn && text.length == 1 && text[0].isLetter()) {
            text.uppercase()
        } else {
            text
        }
    }

    private fun flickTextFor(flick: KanaFlickKey, direction: FlickDirection): String {
        return when (direction) {
            FlickDirection.Center -> flick.center
            FlickDirection.Left -> flick.left
            FlickDirection.Up -> flick.up
            FlickDirection.Right -> flick.right
            FlickDirection.Down -> flick.down
        }
    }

    private fun showKanaFlickGuide(anchor: View, flick: KanaFlickKey, selected: FlickDirection) {
        val label = buildString {
            append("  ")
            append(markFlickLabel(flick.up, selected == FlickDirection.Up))
            append("\n")
            append(markFlickLabel(flick.left, selected == FlickDirection.Left))
            append("  ")
            append(markFlickLabel(flick.center, selected == FlickDirection.Center))
            append("  ")
            append(markFlickLabel(flick.right, selected == FlickDirection.Right))
            append("\n")
            append("  ")
            append(markFlickLabel(flick.down, selected == FlickDirection.Down))
        }
        val popup = flickGuidePopup ?: PopupWindow(this).also {
            it.isClippingEnabled = false
            flickGuidePopup = it
        }
        val textView = TextView(this).apply {
            text = label
            textSize = 22f
            gravity = Gravity.CENTER
            setTextColor(0xFF111111.toInt())
            setBackgroundColor(0xF7FFFFFF.toInt())
            setPadding(dp(12), dp(8), dp(12), dp(8))
        }
        popup.contentView = textView
        popup.width = dp(128)
        popup.height = dp(112)
        if (popup.isShowing) {
            popup.update(anchor, -dp(40), -dp(118), popup.width, popup.height)
        } else {
            popup.showAsDropDown(anchor, -dp(40), -dp(118), Gravity.NO_GRAVITY)
        }
    }

    private fun markFlickLabel(text: String, selected: Boolean): String {
        return if (selected) "[$text]" else " $text "
    }

    private fun hideKanaFlickGuide() {
        flickGuidePopup?.dismiss()
    }

    private fun dp(value: Int): Int {
        return (value * resources.displayMetrics.density + 0.5f).toInt()
    }

    // One-shot and lock behavior removed; simple toggle with click.

    private fun letterToKeyCode(letter: String): Int? {
        return when (letter.lowercase()) {
            "a" -> KeyEvent.KEYCODE_A
            "b" -> KeyEvent.KEYCODE_B
            "c" -> KeyEvent.KEYCODE_C
            "d" -> KeyEvent.KEYCODE_D
            "e" -> KeyEvent.KEYCODE_E
            "f" -> KeyEvent.KEYCODE_F
            "g" -> KeyEvent.KEYCODE_G
            "h" -> KeyEvent.KEYCODE_H
            "i" -> KeyEvent.KEYCODE_I
            "j" -> KeyEvent.KEYCODE_J
            "k" -> KeyEvent.KEYCODE_K
            "l" -> KeyEvent.KEYCODE_L
            "m" -> KeyEvent.KEYCODE_M
            "n" -> KeyEvent.KEYCODE_N
            "o" -> KeyEvent.KEYCODE_O
            "p" -> KeyEvent.KEYCODE_P
            "q" -> KeyEvent.KEYCODE_Q
            "r" -> KeyEvent.KEYCODE_R
            "s" -> KeyEvent.KEYCODE_S
            "t" -> KeyEvent.KEYCODE_T
            "u" -> KeyEvent.KEYCODE_U
            "v" -> KeyEvent.KEYCODE_V
            "w" -> KeyEvent.KEYCODE_W
            "x" -> KeyEvent.KEYCODE_X
            "y" -> KeyEvent.KEYCODE_Y
            "z" -> KeyEvent.KEYCODE_Z
            else -> null
        }
    }

    private fun commitText(text: String) {
        currentInputConnection?.commitText(text, 1)
    }

    private fun deleteText() {
        if (kanaMode) {
            if (isInConversion()) {
                cancelConversionRestore()
                return
            }
            if (romaji.hasComposing()) {
                romaji.backspace()
                updateComposingText()
                return
            }
        }
        currentInputConnection?.deleteSurroundingText(1, 0)
    }

    private fun sendEnter() {
        val ic = currentInputConnection ?: return
        if (kanaMode) {
            if (isInConversion()) {
                commitSelectedCandidate()
                return
            }
            if (romaji.hasComposing()) {
                val text = romaji.flush()
                clearComposingSuggestions()
                ic.commitText(text, 1)
                ic.finishComposingText()
                return
            }
        }
        ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER))
        ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER))
    }

    private fun updateLangToggleUI() {
        langBtn?.let { btn ->
            btn.text = if (kanaMode) "あ" else "A"
            // Disable shift while in kana mode
            shiftBtn?.isEnabled = !kanaMode
            shiftBtnRight?.isEnabled = !kanaMode
            val alpha = if (kanaMode) 0.5f else 1.0f
            shiftBtn?.alpha = alpha
            shiftBtnRight?.alpha = alpha
            updateShiftUI()
            updateCtrlUI()
            if (!kanaMode) {
                // Ensure composing cleared when leaving kana mode
                if (isInConversion()) {
                    // commit selected before leaving kana mode
                    commitSelectedCandidate()
                } else {
                    if (romaji.hasComposing()) {
                        currentInputConnection?.finishComposingText()
                    }
                    romaji.clear()
                }
                // Invalidate any in-flight conversion queries
                convQuerySeq++
                clearComposingSuggestions()
            }
        }
    }

    private fun toggleKanaMode() {
        if (layoutMode == LAYOUT_KANA_12_SWIPE) {
            switchLayoutMode(LAYOUT_JIS_QWERTY)
            return
        }
        kanaMode = !kanaMode
        updateLangToggleUI()
    }

    private fun updateComposingText() {
        val ic = currentInputConnection ?: return
        val text = romaji.getComposing()
        if (text.isEmpty()) {
            ic.finishComposingText()
            clearComposingSuggestions()
        } else {
            ic.setComposingText(text, 1)
            scheduleComposingSuggestions(text)
        }
    }

    private fun scheduleComposingSuggestions(reading: String) {
        suggestionTask?.let { repeatHandler.removeCallbacks(it) }
        suggestionQuerySeq++
        val token = suggestionQuerySeq

        if (!reading.all { it in '\u3041'..'\u3096' || it == 'ー' }) {
            clearComposingSuggestions()
            return
        }

        // Never leave the bar blank while waiting for the dictionary.
        candidates = SimpleConverter().query(reading).take(2)
        selectedCandidateIndex = 0
        candidatesRoot?.visibility = View.VISIBLE
        segmentControls?.visibility = View.GONE
        functionRow?.visibility = View.GONE
        segmentList?.removeAllViews()
        updateCandidatesUI()

        val task = Runnable {
            convExecutor.execute {
                val result = try { converter.query(reading, 5, true) } catch (_: Throwable) { emptyList() }
                repeatHandler.post {
                    if (!isInConversion() && suggestionQuerySeq == token && romaji.getComposing() == reading) {
                        candidates = result
                        selectedCandidateIndex = 0
                        updateCandidatesUI()
                    }
                }
            }
        }
        suggestionTask = task
        repeatHandler.postDelayed(task, COMPOSING_SUGGESTION_DELAY_MS)
    }

    private fun clearComposingSuggestions() {
        suggestionTask?.let { repeatHandler.removeCallbacks(it) }
        suggestionTask = null
        suggestionQuerySeq++
        if (!isInConversion()) {
            candidates = emptyList()
            hideCandidatesUI()
        }
    }

    private fun commitComposingSuggestion(index: Int) {
        if (isInConversion()) return
        val reading = romaji.getComposing()
        val selected = candidates.getOrNull(index) ?: return
        if (reading.isEmpty()) return
        currentInputConnection?.commitText(selected, 1)
        currentInputConnection?.finishComposingText()
        romaji.clear()
        clearComposingSuggestions()
        if (reading.all { it in '\u3041'..'\u3096' || it == 'ー' }) {
            predictionExecutor.execute {
                try { converter.recordSelection(reading, selected) } catch (_: Throwable) {}
            }
        }
    }

    private fun updateComposingFromSegments() {
        val ic = currentInputConnection ?: return
        val out = joinedOutputFromSegments()
        ic.setComposingText(out, 1)
    }

    private fun handleKanaLetter(base: String) {
        if (base.isEmpty()) return
        if (isInConversion()) {
            // typing while selecting: cancel conversion and restore reading to composing
            cancelConversionRestore()
        }
        val c = base[0]
        romaji.pushChar(c)
        updateComposingText()
    }

    private fun handleKanaText(text: String) {
        if (text.isEmpty()) return
        if (!kanaMode) {
            commitText(text)
            return
        }
        if (isInConversion()) {
            cancelConversionRestore()
        }
        romaji.appendKana(text)
        updateComposingText()
    }

    private fun applyKanaModifier() {
        if (!kanaMode || !romaji.hasComposing()) return
        if (isInConversion()) {
            cancelConversionRestore()
        }
        val current = romaji.getComposing()
        if (current.isEmpty()) return
        val last = current.last()
        val replacement = nextKanaVariant(last) ?: return
        romaji.restoreFromKana(current.dropLast(1) + replacement)
        updateComposingText()
    }

    private fun nextKanaVariant(ch: Char): Char? {
        val cycles = listOf(
            "あぁ", "いぃ", "うぅゔ", "えぇ", "おぉ",
            "かが", "きぎ", "くぐ", "けげ", "こご",
            "さざ", "しじ", "すず", "せぜ", "そぞ",
            "ただ", "ちぢ", "つっづ", "てで", "とど",
            "はばぱ", "ひびぴ", "ふぶぷ", "へべぺ", "ほぼぽ",
            "やゃ", "ゆゅ", "よょ", "わゎ"
        )
        val cycle = cycles.firstOrNull { it.contains(ch) } ?: return null
        val index = cycle.indexOf(ch)
        return cycle[(index + 1) % cycle.length]
    }

    private fun flushComposingIfNeeded() {
        if (!kanaMode) return
        if (romaji.hasComposing()) {
            val ic = currentInputConnection ?: return
            val text = romaji.flush()
            clearComposingSuggestions()
            ic.commitText(text, 1)
            ic.finishComposingText()
        }
    }

    private fun flushComposingOrConversionIfNeeded() {
        if (!kanaMode) return
        if (isInConversion()) {
            commitSelectedCandidate()
        } else {
            flushComposingIfNeeded()
        }
    }

    private fun finishCurrentInputSession() {
        flushComposingOrConversionIfNeeded()
        currentInputConnection?.finishComposingText()

        suggestionTask?.let { repeatHandler.removeCallbacks(it) }
        suggestionTask = null
        for (task in repeatTasks.values) {
            repeatHandler.removeCallbacks(task)
        }
        repeatTasks.clear()
        hideKanaFlickGuide()

        romaji.clear()
        conversionReading = null
        candidates = emptyList()
        selectedCandidateIndex = 0
        segments = null
        segmentFocus = 0
        convQuerySeq++
        suggestionQuerySeq++
        hideCandidatesUI()
    }

    private fun isInConversion(): Boolean = conversionReading != null

    private fun startConversion() {
        val ic = currentInputConnection ?: return
        val reading = romaji.flush()
        if (reading.isEmpty()) return
        clearComposingSuggestions()
        conversionReading = reading
        // start new conversion session (invalidate in-flight queries)
        convQuerySeq++
        // Segment discovery touches the dictionary repeatedly, so keep it off the IME/UI thread.
        segments = mutableListOf(Segment(reading, loading = true))
        segmentFocus = 0
        ic.setComposingText(reading, 1)
        // Always have useful content on the very first frame, even while a large
        // packaged database is being copied/opened for the first time.
        segments?.firstOrNull()?.candidates = SimpleConverter().query(reading).take(2).toMutableList()
        showCandidatesUI()
        // Do not make the first visible candidates wait for segment discovery.
        // The placeholder segment represents the complete reading and is replaced below.
        loadSegmentCandidates(0, includeRemaining = false)
        val token = convQuerySeq
        convExecutor.execute {
            val built = buildSegments(reading)
            repeatHandler.post {
                if (isInConversion() && convQuerySeq == token && conversionReading == reading) {
                    segments = built
                    segmentFocus = 0
                    updateSegmentsUI()
                    updateComposingFromSegments()
                    loadSegmentCandidates(0)
                }
            }
        }
    }

    private fun buildSegments(reading: String): MutableList<Segment> {
        val maxLen = 6
        val segs = mutableListOf<Segment>()
        var i = 0
        while (i < reading.length) {
            val maxTry = kotlin.math.min(maxLen, reading.length - i)
            // One indexed lookup per segment instead of up to maxLen separate queries.
            val taken = try {
                converter.longestExactPrefix(reading, i, maxTry)
            } catch (_: Throwable) {
                1
            }
            val segReading = reading.substring(i, i + taken)
            segs.add(Segment(segReading))
            i += taken
        }
        return segs
    }

    private fun joinedOutputFromSegments(): String {
        val segs = segments ?: return conversionReading ?: ""
        val sb = StringBuilder()
        for (seg in segs) {
            val out = currentSegmentOutput(seg)
            sb.append(out)
        }
        return sb.toString()
    }

    private fun currentSegmentOutput(seg: Segment): String {
        return if (seg.candidates.isNotEmpty()) {
            seg.candidates.getOrNull(seg.selectedIndex) ?: seg.reading
        } else seg.reading
    }

    private fun moveSegmentFocus(delta: Int) {
        val segs = segments ?: return
        if (segs.isEmpty()) return
        val newIdx = (segmentFocus + delta).coerceIn(0, segs.lastIndex)
        if (newIdx == segmentFocus) return
        segmentFocus = newIdx
        updateSegmentsUI()
        loadSegmentCandidates(segmentFocus)
    }

    private fun loadSegmentCandidates(index: Int, includeRemaining: Boolean = true) {
        val segs = segments ?: return
        val seg = segs.getOrNull(index) ?: return
        val reading = seg.reading
        seg.loading = true
        updateCandidatesUI()
        val token = convQuerySeq
        convExecutor.execute {
            // Exact TOP 5 is normally index-only and can be displayed without waiting for
            // the substantially more expensive prefix/prediction aggregation.
            val initial = try { converter.query(reading, 5, false) } catch (_: Throwable) { emptyList() }
            repeatHandler.post {
                if (isInConversion() && convQuerySeq == token && segments === segs && segs.getOrNull(index)?.reading == reading) {
                    seg.candidates = initial.toMutableList()
                    // Initialize selection to first candidate if available
                    if (seg.selectedIndex !in seg.candidates.indices) seg.selectedIndex = 0
                    updateSegmentsUI()
                    updateCandidatesUI()
                    updateComposingFromSegments()
                }
            }
            if (!includeRemaining) return@execute

            // Give immediate navigation/selection work a chance to enter the executor
            // before the more expensive prediction query.
            repeatHandler.postDelayed({
                if (isInConversion() && convQuerySeq == token && segments === segs && segs.getOrNull(index)?.reading == reading) {
                    predictionExecutor.execute {
                        val full = try { converter.query(reading, 50, true) } catch (_: Throwable) { initial }
                        repeatHandler.post {
                            if (isInConversion() && convQuerySeq == token && segments === segs && segs.getOrNull(index)?.reading == reading) {
                                seg.candidates = full.toMutableList()
                                seg.loading = false
                                if (seg.selectedIndex !in seg.candidates.indices) seg.selectedIndex = 0
                                updateSegmentsUI()
                                updateCandidatesUI()
                                updateComposingFromSegments()
                            }
                        }
                    }
                }
            }, REMAINING_CANDIDATES_DELAY_MS)
        }
    }

    private fun adjustBoundaryRight(delta: Int) {
        val segs = segments ?: return
        if (segs.isEmpty()) return
        val idx = segmentFocus
        if (idx < 0 || idx >= segs.size - 1) return // need next segment to adjust right boundary
        val cur = segs[idx]
        val next = segs[idx + 1]
        if (delta > 0) {
            // expand current to right: take 1 char from next head
            if (next.reading.length <= 1) {
                // Merge next into current when next is single-character segment
                cur.reading += next.reading
                // Remove next segment
                segs.removeAt(idx + 1)
            } else {
                val ch = next.reading.first()
                cur.reading += ch
                next.reading = next.reading.substring(1)
            }
        } else if (delta < 0) {
            // shrink current from right: give 1 char to next head
            if (cur.reading.length <= 1) return
            val ch = cur.reading.last()
            cur.reading = cur.reading.substring(0, cur.reading.length - 1)
            next.reading = ch + next.reading
        } else return

        // reset candidates for affected segments
        cur.candidates.clear(); cur.selectedIndex = 0; cur.loading = true
        // next may have been removed by merge; refresh if still exists
        if (idx + 1 < segs.size) {
            val n2 = segs[idx + 1]
            n2.candidates.clear(); n2.selectedIndex = 0; n2.loading = true
        }
        // Immediately reflect UI with placeholder (readings) before async results arrive
        updateSegmentsUI()
        updateComposingFromSegments()
        updateCandidatesUI()
        loadSegmentCandidates(idx)
        if (idx + 1 < segs.size) loadSegmentCandidates(idx + 1)
    }

    private fun commitSelectedCandidate() {
        val ic = currentInputConnection ?: return
        if (isInConversion()) {
            val segs = segments
            if (segs != null && segs.isNotEmpty()) {
                val sb = StringBuilder()
                for (seg in segs) {
                    val out = currentSegmentOutput(seg)
                    sb.append(out)
                    try { converter.recordSelection(seg.reading, out) } catch (_: Throwable) {}
                }
                ic.commitText(sb.toString(), 1)
            } else {
                val text = candidates.getOrNull(selectedCandidateIndex) ?: conversionReading!!
                try {
                    val reading = conversionReading
                    if (reading != null) converter.recordSelection(reading, text)
                } catch (_: Throwable) {}
                ic.commitText(text, 1)
            }
            hideCandidatesUI()
            conversionReading = null
            convQuerySeq++
            candidates = emptyList()
            segments = null
        }
    }

    private fun cancelConversionRestore() {
        if (!isInConversion()) return
        val ic = currentInputConnection ?: return
        val reading = conversionReading!!
        hideCandidatesUI()
        conversionReading = null
        convQuerySeq++
        candidates = emptyList()
        romaji.restoreFromKana(reading)
        segments = null
        ic.setComposingText(reading, 1)
        scheduleComposingSuggestions(reading)
    }

    private fun showCandidatesUI() {
        candidatesRoot?.visibility = View.VISIBLE
        segmentControls?.visibility = View.VISIBLE
        functionRow?.visibility = View.GONE
        updateSegmentsUI()
        updateCandidatesUI()
    }

    private fun hideCandidatesUI() {
        candidatesRoot?.visibility = View.GONE
        segmentControls?.visibility = View.GONE
        functionRow?.visibility = functionRowVisibility()
        candidateList?.removeAllViews()
        segmentList?.removeAllViews()
    }

    private fun updateCandidatesUI() {
        val list = candidateList ?: return
        list.removeAllViews()
        val segs = segments
        if (isInConversion() && segs != null) {
            if (segs.isEmpty()) return
            val focus = segs.getOrNull(segmentFocus) ?: return
            val cands = if (focus.candidates.isNotEmpty()) focus.candidates else emptyList()
            if (cands.isEmpty()) {
                // show loading or reading placeholder
                val btn = Button(this)
                val lp = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.MATCH_PARENT
                )
                lp.marginEnd = 6
                btn.layoutParams = lp
                btn.isEnabled = false
                btn.text = if (focus.loading) "…" else focus.reading
                list.addView(btn)
                return
            }
            cands.forEachIndexed { index, cand ->
                val btn = Button(this)
                val lp = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.MATCH_PARENT
                )
                lp.marginEnd = 6
                btn.layoutParams = lp
                btn.text = if (index == focus.selectedIndex) "•$cand" else cand
                btn.setOnClickListener {
                    focus.selectedIndex = index
                    updateSegmentsUI()
                    updateComposingFromSegments()
                    // auto-advance to next segment if exists
                    if (segmentFocus < segs.lastIndex) {
                        moveSegmentFocus(1)
                    } else {
                        updateCandidatesUI()
                    }
                }
                list.addView(btn)
            }
        } else {
            candidates.forEachIndexed { index, cand ->
                val btn = Button(this)
                val lp = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.MATCH_PARENT
                )
                lp.marginEnd = 6
                btn.layoutParams = lp
                btn.text = if (index == selectedCandidateIndex) "•$cand" else cand
                btn.setOnClickListener {
                    selectedCandidateIndex = index
                    commitComposingSuggestion(index)
                }
                list.addView(btn)
            }
        }
    }

    private fun updateSegmentsUI() {
        val list = segmentList ?: return
        list.removeAllViews()
        val segs = segments ?: return
        segs.forEachIndexed { idx, seg ->
            val label = currentSegmentOutput(seg)
            val btn = Button(this)
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.MATCH_PARENT
            )
            lp.marginEnd = 6
            btn.layoutParams = lp
            btn.text = if (idx == segmentFocus) "[$label]" else label
            btn.setOnClickListener {
                segmentFocus = idx
                updateSegmentsUI()
                loadSegmentCandidates(segmentFocus)
            }
            list.addView(btn)
        }
    }

    private fun updateCandidateSelectionUI() {
        val list = candidateList ?: return
        for (i in 0 until list.childCount) {
            val v = list.getChildAt(i)
            if (v is Button) {
                val text = candidates.getOrNull(i) ?: ""
                v.text = if (i == selectedCandidateIndex) "•$text" else text
            }
        }
    }

    override fun onDestroy() {
        repeatHandler.removeCallbacksAndMessages(null)
        hideKanaFlickGuide()
        try {
            sqliteConverter?.close()
        } catch (_: Exception) {
        } finally {
            sqliteConverter = null
        }
        convExecutor.shutdownNow()
        predictionExecutor.shutdownNow()
        super.onDestroy()
    }
}
