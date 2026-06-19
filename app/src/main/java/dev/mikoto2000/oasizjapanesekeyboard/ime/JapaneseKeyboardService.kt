/*
    All Rights Reserved, Copyright (C) 2025, mikoto2000
      Licensed Material of mikoto2000.

    All Rights Reserved, Copyright (C) 2026, Moto+4 Applications LLC
      Licensed Material of Moto+4 Applications LLC.
 */

package dev.mikoto2000.oasizjapanesekeyboard.ime

import android.content.Intent
import android.inputmethodservice.InputMethodService
import android.view.KeyEvent
import android.os.SystemClock
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import java.util.concurrent.Executors
import dev.mikoto2000.oasizjapanesekeyboard.R
import dev.mikoto2000.oasizjapanesekeyboard.MainActivity

import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowCompat
import kotlin.arrayOf
import android.graphics.Color
import android.content.res.ColorStateList

class JapaneseKeyboardService : InputMethodService() {
    private var shiftOn = false
    private var shiftLock = false
    private var shiftLockAfterSoon = false
    private var ctrlOn = false
    private var shiftBtn: Button? = null
    private var shiftBtnRight: Button? = null
    private var ctrlBtn: Button? = null
    private var langBtn: Button? = null
    private var rootViewRef: View? = null
    private var feedbackEnabled = true
    private val repeatHandler = Handler(Looper.getMainLooper())
    private val longPressHandler = Handler(Looper.getMainLooper())
    private val repeatTasks = mutableMapOf<View, Runnable>()
    private val longPressTasks = mutableMapOf<View, Runnable>()
    private val letterButtons = mutableListOf<Button>()
    private val symbolButtons = mutableListOf<Pair<Button, String>>()
    private var fnVisible = true

    // Kana composing state
    private val   MODE_ASCII    = 0
    private val   MODE_ROMA     = 1
    private val   MODE_HIRAGANA = 2
    private val   MODE_KATAKANA = 3
    private val   MODE_MAXNUM  = 4
    private val   MODE_DSPTXT = arrayOf("英数","ﾛｰﾏ字","かな","カナ"," ")
    private var inputMode = MODE_ASCII // default: ASCII mode

    private val romaji = RomajiConverter()

    // Conversion (candidates) state
    private var conversionReading: String? = null
    private var candidates: List<String> = emptyList()
    private var selectedCandidateIndex: Int = 0
    private var candidatesRoot: View? = null
    private var segmentList: ViewGroup? = null
    private var candidateContainer: View? = null
    private var candidateList: ViewGroup? = null
    private var converter: JapaneseConverter = SimpleConverter()
    private val convExecutor = Executors.newSingleThreadExecutor()
    private var convQuerySeq: Long = 0L
    private var sqliteConverter: SqliteDictionaryConverter? = null
    private  var longPressStarTime : Long = 0L

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
        "\\" to "|"     // This setting is for en-sign. Implemant replacing of backslash in side of logic.
    )

    data class keyCodeToMoji (
       var  keyCode : Int,
       var  moji : String
    )

    private val engToHiraganaMap = arrayOf(
        // NOTE: Use only UTF-8 of Japanease language.
        // 0 to 9
        keyCodeToMoji( R.id.key_1, "ぬ" ),
        keyCodeToMoji( R.id.key_2, "ふ" ),
        // a to z
        keyCodeToMoji( R.id.key_a, "ち" ),
        keyCodeToMoji( R.id.key_b, "こ" ),
        keyCodeToMoji( R.id.key_c, "そ" ),
        keyCodeToMoji( R.id.key_d, "し" ),
        keyCodeToMoji( R.id.key_f, "は" ),
        keyCodeToMoji( R.id.key_g, "き" ),
        keyCodeToMoji( R.id.key_h, "く" ),
        keyCodeToMoji( R.id.key_i, "に" ),
        keyCodeToMoji( R.id.key_j, "ま" ),
        keyCodeToMoji( R.id.key_k, "の" ),
        keyCodeToMoji( R.id.key_l, "り" ),
        keyCodeToMoji( R.id.key_m, "も" ),
        keyCodeToMoji( R.id.key_n, "み" ),
        keyCodeToMoji( R.id.key_o, "ら" ),
        keyCodeToMoji( R.id.key_p, "せ" ),
        keyCodeToMoji( R.id.key_q, "た" ),
        keyCodeToMoji( R.id.key_r, "す" ),
        keyCodeToMoji( R.id.key_s, "と" ),
        keyCodeToMoji( R.id.key_t, "か" ),
        keyCodeToMoji( R.id.key_u, "な" ),
        keyCodeToMoji( R.id.key_v, "ひ" ),
        keyCodeToMoji( R.id.key_w, "て" ),
        keyCodeToMoji( R.id.key_x, "さ" ),
        keyCodeToMoji( R.id.key_y, "ん" ),
        // other
        keyCodeToMoji( R.id.key_hyphen     , "ほ" ),
        keyCodeToMoji( R.id.key_ensign     , "ー" ),
        keyCodeToMoji( R.id.key_yama       , "へ" ),
        keyCodeToMoji( R.id.key_semicolon  , "れ" ),
        keyCodeToMoji( R.id.key_colon      , "け" ),
        keyCodeToMoji( R.id.key_r_kakukakko, "む" ),
        keyCodeToMoji( R.id.key_atmark     , "゛" ),
        keyCodeToMoji( R.id.key_l_kakukakko, "゜" ),
        keyCodeToMoji( R.id.key_backslash  , "ろ" ),
    )

    private val engToHiraganaShiftOffMap = arrayOf(
        // NOTE: Use only UTF-8 of Japanease language.
        // 0 to 9
        keyCodeToMoji( R.id.key_3, "あ" ),
        keyCodeToMoji( R.id.key_4, "う" ),
        keyCodeToMoji( R.id.key_5, "え" ),
        keyCodeToMoji( R.id.key_6, "お" ),
        keyCodeToMoji( R.id.key_7, "や" ),
        keyCodeToMoji( R.id.key_8, "ゆ" ),
        keyCodeToMoji( R.id.key_9, "よ" ),
        keyCodeToMoji( R.id.key_0, "わ" ),
        keyCodeToMoji( R.id.key_e, "い" ),
        keyCodeToMoji( R.id.key_z, "つ" ),
        keyCodeToMoji( R.id.key_comma      , "ね" ),
        keyCodeToMoji( R.id.key_pochi      , "る" ),
        keyCodeToMoji( R.id.key_slash      , "め" ),
    )

    private val engToHiraganaShiftOnMap = arrayOf(
        // NOTE: Use only UTF-8 of Japanease language.
        keyCodeToMoji( R.id.key_3,      "ぁ" ),
        keyCodeToMoji( R.id.key_4,      "ぅ" ),
        keyCodeToMoji( R.id.key_5,      "ぇ" ),
        keyCodeToMoji( R.id.key_6,      "ぉ" ),
        keyCodeToMoji( R.id.key_7,      "ゃ" ),
        keyCodeToMoji( R.id.key_8,      "ゅ" ),
        keyCodeToMoji( R.id.key_9,      "ょ" ),
        keyCodeToMoji( R.id.key_0,      "を" ),
        keyCodeToMoji( R.id.key_e,      "ぃ" ),
        keyCodeToMoji( R.id.key_z,      "っ" ),
        keyCodeToMoji( R.id.key_comma,  "、" ),
        keyCodeToMoji( R.id.key_pochi,  "。" ),
        keyCodeToMoji( R.id.key_slash,  "・" ),
    )

    override fun onCreate() {
        super.onCreate()

        sqliteConverter = try {
            // For ime do not overlap navigation　bar.
            val diaWindow = getWindow() ?: return
            val imeWindow = diaWindow?.getWindow() ?: return
            WindowCompat.setDecorFitsSystemWindows(imeWindow, false)

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

    override fun onCreateInputView(): View {
        val root = layoutInflater.inflate(R.layout.keyboard_jis_qwerty, null)
        rootViewRef = root
        // Initialize converter: prefer SQLite dictionary; fallback to TSV; then to simple built-in
        converter = sqliteConverter ?: try {
            DictionaryConverter(this)
        } catch (_: Exception) {
            SimpleConverter()
        }

        // Wire generic keys by tag
        wireKeysRecursively(root)

        // Special keys (repeat enabled)
        // Remark: setRepeatableKey is original API.
        root.findViewById<View>(R.id.key_backspace)?.let { v ->
            setRepeatableKey(v, initialDelay= 350L, repeatInterval = 60L) {
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
                if ( isNeedConvertKanji() ) {
                    //----------------- KANA convert to KANJI start ---------------
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
                    //----------------- KANA convert to KANJI end ---------------
                } else {
                    commitText(" ")
                    consumeOneShotModifiers()
                }
            }
        }

        shiftBtn = root.findViewById<Button>(R.id.key_shift)
        // Normal Press
        shiftBtn?.setOnClickListener {
            if ( ! shiftLock ) {
                changeShiftMode()
                updateShiftUIALLMode()
                updateShiftUI()
            }
        }
        // Long Press
        // Remark: setOnLongClickListener is timeout 500msec fixed...
        shiftBtn?.let { v ->
            setImeLongPressKey( v ) {
                changeShiftLockMode()   // Must call first
                changeShiftMode()       // Must call after
                updateShiftUIALLMode()
                updateShiftUI()
            }
        }

        updateShiftUI()     // NOTE: Changing of keyboard may not nessary in this case.

        shiftBtnRight = root.findViewById<Button>(R.id.key_shift_right)
        shiftBtnRight?.setOnClickListener {
            if ( ! shiftLock ) {
                changeShiftMode()
                updateShiftUIALLMode()
                updateShiftUI()
            }
        }
        // Long Press
        shiftBtnRight?.let { v ->
            setImeLongPressKey( v ) {
                changeShiftLockMode()   // Must call first
                changeShiftMode()       // Must call after
                updateShiftUIALLMode()
                updateShiftUI()
            }
        }

        ctrlBtn = root.findViewById<Button>(R.id.key_ctrl)
        ctrlBtn?.setOnClickListener {
            if (inputMode == MODE_ASCII) {
                ctrlOn = !ctrlOn
                updateCtrlUI()
            }
        }
        updateCtrlUI()

        // Language toggle (A <-> あ)
        langBtn = root.findViewById<Button>(R.id.key_lang_toggle)
        langBtn?.setOnClickListener {
            toggleKanaMode()
        }
        updateLangToggleUI()

        // Arrow keys (repeat enabled)
        // Remark: setRepeatableKey is original API.
        root.findViewById<View>(R.id.key_arrow_left)?.let { v ->
            setRepeatableKey(v) {
                if ( isNeedConvertKanji() && isInConversion() && segments != null ) {
                    moveSegmentFocus(-1)
                } else {
                    flushComposingOrConversionIfNeeded(); sendDpad(KeyEvent.KEYCODE_DPAD_LEFT); consumeOneShotModifiers()
                }
            }
        }
        root.findViewById<View>(R.id.key_arrow_right)?.let { v ->
            setRepeatableKey(v) {
                if ( isNeedConvertKanji() && isInConversion() && segments != null ) {
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

        // help view
        root.findViewById<Button>(R.id.key_help)?.let { btn ->
            btn.setOnClickListener {
                var intentHelp : Intent = Intent( getApplication(), MainActivity::class.java)
                intentHelp.addFlags( Intent.FLAG_ACTIVITY_NEW_TASK );
                startActivity( intentHelp )
            }
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
        val fnRow = root.findViewById<View>(R.id.row_fn)
        fnRow?.visibility = if (fnVisible) View.VISIBLE else View.GONE
        root.findViewById<Button>(R.id.key_fn_toggle)?.let { btn ->
            btn.setOnClickListener {
                fnVisible = !fnVisible
                fnRow?.visibility = if (fnVisible) View.VISIBLE else View.GONE
                updateFnToggleUI(btn)
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

        // For IME do not overlap navigation　bar.
        // NOTE: IME sometimes overlap navigation bar.
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, windowInsets ->
            val insets = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime())
            view.setPadding(insets.left, insets.top, insets.right, insets.bottom)
            WindowInsetsCompat.CONSUMED
        }

        // Apply initial backgrounds to all keys
        applyKeyBackgrounds()

        return root
    }

    private fun changeShiftMode() {
        if ( isNeedShiftKey() ) {
            // Do not check condidion of shitfLock hear.
            // Check condidion of shitfLock in only consumeOneShotModifiers().
            shiftOn = !shiftOn
        }
    }

    private fun changeShiftLockMode() {
        if ( isNeedShiftKey()) {
            shiftLock = !shiftLock
        }
    }

    private fun updateShiftUIALLMode() {
        if (inputMode == MODE_ASCII) {
            updateShiftEnglishKeyboard()
        } else if (inputMode == MODE_HIRAGANA) {
            updateShiftHiraganaKeyboard()
        }
    }

    // Is it mode need to convert to KANJI.
    private fun isNeedConvertKanji() : Boolean  {
        if ( inputMode == MODE_ROMA     ) return true
        if ( inputMode == MODE_HIRAGANA ) return true
        return false
    }

    private fun isNeedShiftKey() : Boolean  {
        if ( inputMode == MODE_ASCII    ) return true
        if ( inputMode == MODE_ROMA     ) return true
        if ( inputMode == MODE_HIRAGANA ) return true
        return false
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
                    //---------- Key input event start ----------------
                    setRepeatableKey(view) {
                        if (inputMode == MODE_ROMA) {
                            handleRomaKanaLetter(base)
                        }
                        else if (inputMode == MODE_HIRAGANA) {
                            handleHiraganaLetter(view)
                        } else {
                            val text = if (shiftOn) base.uppercase() else base.lowercase()
                            if (ctrlOn) {
                                val code = letterToKeyCode(base)
                                if (code != null) sendKeyWithMeta(code, KeyEvent.META_CTRL_ON) else commitText(text)
                            } else {
                                commitText(text)
                            }
                        }
                        if ( isNeedShiftKey() ) consumeOneShotModifiers()
                    }
                    //---------- Key input event end ----------------
                }
                tag.startsWith("symbol:") -> {
                    val base = tag.removePrefix("symbol:")
                    symbolButtons.add(view to base)
                    // Initial label reflects current shift state
                    val label = if (shiftOn) shiftSymbolMap[base] ?: base else base
                    view.text = label
                    //---------- Key input event start ----------------
                    setRepeatableKey(view) {
                        if (inputMode == MODE_HIRAGANA) {
                            handleHiraganaLetter(view)
                        }
                        else {
                            var out = if (shiftOn) shiftSymbolMap[base] ?: base else base
                            if (view.id == R.id.key_backslash ) {
                                out = if (shiftOn) "_" else "\\"
                            }

                            if (inputMode == MODE_ROMA) {
                                flushComposingOrConversionIfNeeded()
                            }
                            commitText(out)
                        }
                        if ( isNeedShiftKey() ) consumeOneShotModifiers()
                    }
                    //---------- Key input event end ----------------
                }
            }
        }
    }

    private fun updateShiftEnglishKeyboard() {
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

        // Reset rotationY to 0.
        // RotationY has set to 180 because display backslash in japanease mode.
        if (shiftOn) {
            rootViewRef?.findViewById<Button>(R.id.key_backslash)?.text = "_"
            rootViewRef?.findViewById<Button>(R.id.key_backslash)?.rotationY = 0.0f
        }
        else {
            rootViewRef?.findViewById<Button>(R.id.key_backslash)?.text = "/"
            rootViewRef?.findViewById<Button>(R.id.key_backslash)?.rotationY = 180.0f
        }
    }

    private fun changeOnColorButton(btn : Button, onState :Boolean, lockState : Boolean = false) {
        var onColor     = Color.rgb(10,10,10) /*Deep gray*/
        var lockColor   = Color.rgb(255,0,0) /*red*/
        var textColor1  = Color.rgb(255,255,255)  /* Withe */
        var textColor2  = Color.rgb(0,0,0)  /* black */

        if ( lockState ) {
            btn.setTypeface(null, 1 /*Bold*/);
            btn.setTextColor( textColor1 )
            btn.setBackgroundTintList( ColorStateList.valueOf( lockColor ) );
            btn.text = "・" + btn.text
        }
        else {
            var find : String = "・"
            btn.text = btn.text.replace(find.toRegex(),"")
            if ( onState ) {
                btn.setTypeface(null, 0 /*Bold*/);
                btn.setTextColor( textColor1 )
                btn.setBackgroundTintList( ColorStateList.valueOf( onColor ) );
            }
            else {
                btn.setTypeface(null, 0 /*Bold*/);
                btn.setTextColor( textColor2 )
                btn.setBackgroundTintList( ColorStateList.valueOf( textColor1 ) );
            }
        }
    }

    private fun updateShiftUI() {
        val active = shiftOn
        shiftBtn?.let { btn ->
            changeOnColorButton(btn, active, shiftLock)
            btn.isSelected = active
        }
        shiftBtnRight?.let { btn ->
            changeOnColorButton(btn, active, shiftLock)
            btn.isSelected = active
        }
    }

    private fun updateCtrlUI() {
        ctrlBtn?.let { btn ->
            changeOnColorButton(btn, ctrlOn)
            btn.isSelected = ctrlOn
            var mode = false
            var alpha = 0.5f
            if (inputMode == MODE_ASCII) {
                mode = true
                alpha = 1.0f
            }
            btn.isEnabled = true
            btn.alpha = 1.0f
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

    // This founction set to whether any button filled backcolor.
    private fun updateFeedbackToggleUI(btn: Button) {
        changeOnColorButton(btn, feedbackEnabled)
        btn.isSelected = feedbackEnabled
    }

    // This founction set to whether any button filled backcolor.
    private fun applyKeyBackgrounds() {
        val root = rootViewRef as? ViewGroup ?: return
        applyKeyBackgroundsRec(root)
    }

    // This founction set to whether any button filled backcolor.
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
        changeOnColorButton(btn, fnVisible)
        btn.isSelected = fnVisible
    }

    private fun consumeOneShotModifiers() {
        var changed = false
        if (shiftOn == true && shiftLock == false ) {
            shiftOn = false;
            changed = true
        }
        if (ctrlOn) { ctrlOn = false; changed = true }
        if (changed) {
            updateShiftUIALLMode()
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

    private fun setImeLongPressKey(
        view: View,
        waittime: Long = 1500L,
        action: () -> Unit
    ) {
        view.setOnTouchListener { v, ev ->
            /* NOTE: There are 6 warning error in this function. */
                when (ev.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        // Handler at passed waittime
                        val task = object : Runnable {
                            override fun run() {
                                action()
                            }
                        }
                        longPressTasks[v] = task    // keep instance for stop task
                        longPressHandler.postDelayed(task, waittime)    // Start waiting
                        false // if set to true, do not occure click enent.
                    }
                    MotionEvent.ACTION_UP -> {
                        longPressTasks.remove(v)?.let { longPressHandler.removeCallbacks(it) }
                        false // if set to true, do not occure click enent.
                    }
                    else -> false
                }
            }
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
        if ( isNeedConvertKanji() ) {
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
        if ( isNeedConvertKanji() ) {
            if (isInConversion()) {
                commitSelectedCandidate()
                return
            }
            if (romaji.hasComposing()) {
                val text = romaji.flush()
                ic.commitText(text, 1)
                ic.finishComposingText()
                return
            }
        }
        ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER))
        ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER))
    }

    private fun changeToHiraganaKeyboard() {
        for ( rec in engToHiraganaMap) {
            val btn = rootViewRef?.findViewById<Button>(rec.keyCode)
            if ( btn != null ) {
                btn.text = rec.moji
            }
        }

        updateShiftHiraganaKeyboard()

        rootViewRef?.findViewById<Button>(R.id.key_space)?.text = "space(変換)"

        // Reset rotationY to 0.
        // RotationY has set to 180 because display backslash in japanease mode.
        rootViewRef?.findViewById<Button>(R.id.key_backslash)?.rotationY = 0.0f
    }

    private fun updateShiftHiraganaKeyboard() {
        val keyMap : Array<keyCodeToMoji>

        if ( shiftOn == true ) {
            keyMap = engToHiraganaShiftOnMap.copyOf()
        }
        else {
            keyMap = engToHiraganaShiftOffMap.copyOf()
        }

        for ( rec in keyMap ) {
            val btn = rootViewRef?.findViewById<Button>(rec.keyCode)
            if ( btn != null ) {
                btn.text = rec.moji
            }
        }
    }

    private fun changeToKatakanaKeyboard() {
    }

    private fun changeToEngilshKeyboard() {
        rootViewRef?.findViewById<Button>(R.id.key_space)?.text = "space"

        updateShiftEnglishKeyboard()
    }

    private fun updateLangToggleUI() {
        langBtn?.let { btn ->
            btn.text = MODE_DSPTXT[inputMode]
            // Disable shift while in kana mode
            var mode = false
            var alpha = 0.5f    /* gray mask */
            if (inputMode == MODE_ASCII    ||
                inputMode == MODE_HIRAGANA ||
                inputMode == MODE_KATAKANA) {
                mode    = true
                alpha   = 1.0f  /* no mask */
            }

            shiftBtn?.isEnabled = mode
            shiftBtnRight?.isEnabled = mode
            shiftBtn?.alpha = alpha
            shiftBtnRight?.alpha = alpha
            updateShiftUI()     // NOTE: Changing of keyboard may not nessary in this case.
            updateCtrlUI()

            if (inputMode == MODE_HIRAGANA) {
                changeToHiraganaKeyboard()
            }
            else if (inputMode == MODE_KATAKANA) {
                changeToKatakanaKeyboard()
            }
            else {
                changeToEngilshKeyboard()

                if (inputMode == MODE_ASCII) {
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
                }

                if (inputMode == MODE_ROMA) {
                    rootViewRef?.findViewById<Button>(R.id.key_space)?.text = "space(変換)"
                }
            }
        }
    }

    private fun toggleKanaMode() {
        //  Input modes will change cyclic as blow.
        //  ASCII -> ROMA -> HIRAGANA -> KATAKANA -> ASCII
        inputMode ++
        if (inputMode >= MODE_MAXNUM) {
            inputMode = MODE_ASCII
        }
        updateLangToggleUI()
    }

    private fun updateComposingText() {
        val ic = currentInputConnection ?: return
        val text = romaji.getComposing()
        if (text.isEmpty()) {
            ic.finishComposingText()
        } else {
            ic.setComposingText(text, 1)
        }
    }

    private fun updateComposingFromSegments() {
        val ic = currentInputConnection ?: return
        val out = joinedOutputFromSegments()
        ic.setComposingText(out, 1)
    }

    private fun handleRomaKanaLetter(base: String) {
        if (base.isEmpty()) return
        if (isInConversion()) {
            // typing while selecting: cancel conversion and restore reading to composing
            cancelConversionRestore()
        }
        val c = base[0]
        romaji.pushChar(c)
        updateComposingText()
    }

    private fun handleHiraganaLetter(base: Button) {
        if (isInConversion()) {
            // typing while selecting: cancel conversion and restore reading to composing
            cancelConversionRestore()
        }
        val text = base?.text.toString()
        romaji.pushKanaChar(text)
        updateComposingText()
    }

    private fun flushComposingIfNeeded() {
        if (inputMode != MODE_ASCII) return
        if (romaji.hasComposing()) {
            val ic = currentInputConnection ?: return
            val text = romaji.flush()
            ic.commitText(text, 1)
            ic.finishComposingText()
        }
    }

    private fun flushComposingOrConversionIfNeeded() {
        if (inputMode != MODE_ASCII) return
        if (isInConversion()) {
            commitSelectedCandidate()
        } else {
            flushComposingIfNeeded()
        }
    }

    private fun isInConversion(): Boolean = conversionReading != null

    private fun startConversion() {
        val ic = currentInputConnection ?: return
        val reading = romaji.flush()
        if (reading.isEmpty()) return
        conversionReading = reading
        // start new conversion session (invalidate in-flight queries)
        convQuerySeq++
        segments = buildSegments(reading)
        segmentFocus = 0
        ic.setComposingText(joinedOutputFromSegments(), 1)
        showCandidatesUI()
        loadSegmentCandidates(segmentFocus)
    }

    private fun buildSegments(reading: String): MutableList<Segment> {
        val maxLen = 6
        val segs = mutableListOf<Segment>()
        var i = 0
        while (i < reading.length) {
            var taken = 1
            var bestLen = 1
            var bestScore = 0
            val maxTry = kotlin.math.min(maxLen, reading.length - i)
            for (l in maxTry downTo 1) {
                val sub = reading.substring(i, i + l)
                val qs = try { converter.query(sub) } catch (_: Throwable) { emptyList() }
                // score by number of candidates beyond baseline (reading + katakana)
                val score = (qs.size - 2).coerceAtLeast(0)
                if (l == 1 || score > 0) {
                    if (score > bestScore || (score == bestScore && l > bestLen)) {
                        bestScore = score
                        bestLen = l
                    }
                }
            }
            taken = bestLen
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

    private fun loadSegmentCandidates(index: Int) {
        val segs = segments ?: return
        val seg = segs.getOrNull(index) ?: return
        val reading = seg.reading
        seg.loading = true
        updateCandidatesUI()
        val token = convQuerySeq
        convExecutor.execute {
            val res = try { converter.query(reading) } catch (_: Throwable) { emptyList() }
            repeatHandler.post {
                if (isInConversion() && convQuerySeq == token && segments === segs && segs.getOrNull(index)?.reading == reading) {
                    seg.candidates = res.toMutableList()
                    seg.loading = false
                    // Initialize selection to first candidate if available
                    if (seg.selectedIndex !in seg.candidates.indices) seg.selectedIndex = 0
                    updateSegmentsUI()
                    updateCandidatesUI()
                    updateComposingFromSegments()
                }
            }
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
        ic.setComposingText(reading, 1)
    }

    private fun showCandidatesUI() {
        candidatesRoot?.visibility = View.VISIBLE
        updateSegmentsUI()
        updateCandidatesUI()
    }

    private fun hideCandidatesUI() {
        candidatesRoot?.visibility = View.GONE
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
                    commitSelectedCandidate()
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
        try {
            sqliteConverter?.close()
        } catch (_: Exception) {
        } finally {
            sqliteConverter = null
        }
        convExecutor.shutdownNow()
        super.onDestroy()
    }
}
