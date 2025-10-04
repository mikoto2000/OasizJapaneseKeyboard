package dev.mikoto2000.oasizjapanesekeyboard.ime

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

class JapaneseKeyboardService : InputMethodService() {
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

    // Kana composing state
    private var kanaMode = false // default: ASCII mode
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

    override fun onCreateInputView(): View {
        val root = layoutInflater.inflate(R.layout.keyboard_jis_qwerty, null)
        rootViewRef = root
        // Initialize converter: prefer SQLite dictionary; fallback to TSV; then to simple built-in
        converter = try {
            SqliteDictionaryConverter(this)
        } catch (e: Exception) {
            DictionaryConverter(this)
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
            toggleKanaMode()
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

        // Apply initial backgrounds to all keys
        applyKeyBackgrounds()

        return root
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
                            flushComposingOrConversionIfNeeded()
                        }
                        commitText(out)
                        if (!kanaMode) consumeOneShotModifiers()
                    }
                }
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
            btn.text = if (active) "Shift ON" else "Shift"
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
        btn.text = if (fnVisible) "Fn ON" else "Fn OFF"
        btn.isSelected = fnVisible
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
            }
        }
    }

    private fun toggleKanaMode() {
        kanaMode = !kanaMode
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

    private fun flushComposingIfNeeded() {
        if (!kanaMode) return
        if (romaji.hasComposing()) {
            val ic = currentInputConnection ?: return
            val text = romaji.flush()
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
            if (next.reading.length <= 1) return
            val ch = next.reading.first()
            cur.reading += ch
            next.reading = next.reading.substring(1)
        } else if (delta < 0) {
            // shrink current from right: give 1 char to next head
            if (cur.reading.length <= 1) return
            val ch = cur.reading.last()
            cur.reading = cur.reading.substring(0, cur.reading.length - 1)
            next.reading = ch + next.reading
        } else return

        // reset candidates for affected segments
        cur.candidates.clear(); cur.selectedIndex = 0; cur.loading = true
        next.candidates.clear(); next.selectedIndex = 0; next.loading = true
        // Immediately reflect UI with placeholder (readings) before async results arrive
        updateSegmentsUI()
        updateComposingFromSegments()
        updateCandidatesUI()
        loadSegmentCandidates(idx)
        loadSegmentCandidates(idx + 1)
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
        super.onDestroy()
        convExecutor.shutdownNow()
    }
}
