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
            setRepeatableKey(v) {
                commitText(" ")
                consumeOneShotModifiers()
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
            setRepeatableKey(v) { flushComposingIfNeeded(); sendDpad(KeyEvent.KEYCODE_DPAD_LEFT); consumeOneShotModifiers() }
        }
        root.findViewById<View>(R.id.key_arrow_right)?.let { v ->
            setRepeatableKey(v) { flushComposingIfNeeded(); sendDpad(KeyEvent.KEYCODE_DPAD_RIGHT); consumeOneShotModifiers() }
        }
        root.findViewById<View>(R.id.key_arrow_up)?.let { v ->
            setRepeatableKey(v) { flushComposingIfNeeded(); sendDpad(KeyEvent.KEYCODE_DPAD_UP); consumeOneShotModifiers() }
        }
        root.findViewById<View>(R.id.key_arrow_down)?.let { v ->
            setRepeatableKey(v) { flushComposingIfNeeded(); sendDpad(KeyEvent.KEYCODE_DPAD_DOWN); consumeOneShotModifiers() }
        }

        // ESC / TAB (repeat enabled)
        root.findViewById<View>(R.id.key_esc)?.let { v ->
            setRepeatableKey(v) { flushComposingIfNeeded(); sendSimpleKey(KeyEvent.KEYCODE_ESCAPE); consumeOneShotModifiers() }
        }
        root.findViewById<View>(R.id.key_tab)?.let { v ->
            setRepeatableKey(v) { flushComposingIfNeeded(); sendSimpleKey(KeyEvent.KEYCODE_TAB); consumeOneShotModifiers() }
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
                setRepeatableKey(v) { flushComposingIfNeeded(); sendSimpleKey(code); consumeOneShotModifiers() }
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
                        if (kanaMode) flushComposingIfNeeded()
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
        if (kanaMode && romaji.hasComposing()) {
            romaji.backspace()
            updateComposingText()
        } else {
            currentInputConnection?.deleteSurroundingText(1, 0)
        }
    }

    private fun sendEnter() {
        val ic = currentInputConnection ?: return
        if (kanaMode && romaji.hasComposing()) {
            val text = romaji.flush()
            ic.commitText(text, 1)
            ic.finishComposingText()
        } else {
            ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER))
            ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER))
        }
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
                if (romaji.hasComposing()) {
                    currentInputConnection?.finishComposingText()
                }
                romaji.clear()
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

    private fun handleKanaLetter(base: String) {
        if (base.isEmpty()) return
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
}
