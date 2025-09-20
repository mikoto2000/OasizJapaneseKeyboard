package dev.mikoto2000.oasizjapanesekeyboard.ime

import android.inputmethodservice.InputMethodService
import android.view.KeyEvent
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import dev.mikoto2000.oasizjapanesekeyboard.R

class JapaneseKeyboardService : InputMethodService() {
    private var shiftOn = false
    private var ctrlOn = false
    private val letterButtons = mutableListOf<Button>()
    private val symbolButtons = mutableListOf<Pair<Button, String>>()

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

        // Wire generic keys by tag
        wireKeysRecursively(root)

        // Special keys
        root.findViewById<View>(R.id.key_backspace)?.setOnClickListener { deleteText() }
        root.findViewById<View>(R.id.key_enter)?.setOnClickListener { sendEnter() }
        root.findViewById<View>(R.id.key_space)?.setOnClickListener { commitText(" ") }
        root.findViewById<View>(R.id.key_shift)?.setOnClickListener { toggleShift(root) }
        (root.findViewById<View>(R.id.key_ctrl) as? Button)?.let { btn ->
            btn.setOnClickListener { toggleCtrl(btn) }
            updateCtrlLabel(btn)
        }

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
                    view.setOnClickListener {
                        val text = if (shiftOn) base.uppercase() else base.lowercase()
                        if (ctrlOn) {
                            val code = letterToKeyCode(base)
                            if (code != null) sendKeyWithMeta(code, KeyEvent.META_CTRL_ON)
                            else commitText(text)
                        } else {
                            commitText(text)
                        }
                    }
                }
                tag.startsWith("symbol:") -> {
                    val base = tag.removePrefix("symbol:")
                    symbolButtons.add(view to base)
                    // Initial label reflects current shift state
                    val label = if (shiftOn) shiftSymbolMap[base] ?: base else base
                    view.text = label
                    view.setOnClickListener {
                        val out = if (shiftOn) shiftSymbolMap[base] ?: base else base
                        // For symbols we keep commitText; Ctrl combos typically apply to letters.
                        commitText(out)
                    }
                }
            }
        }
    }

    private fun toggleShift(root: View) {
        shiftOn = !shiftOn
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
    }

    private fun toggleCtrl(btn: Button) {
        ctrlOn = !ctrlOn
        updateCtrlLabel(btn)
    }

    private fun updateCtrlLabel(btn: Button) {
        btn.text = if (ctrlOn) "Ctrl ON" else "Ctrl"
        btn.isSelected = ctrlOn
    }

    private fun sendKeyWithMeta(keyCode: Int, meta: Int) {
        val now = SystemClock.uptimeMillis()
        val ic = currentInputConnection ?: return
        ic.sendKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_DOWN, keyCode, 0, meta))
        ic.sendKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_UP, keyCode, 0, meta))
    }

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
        currentInputConnection?.deleteSurroundingText(1, 0)
    }

    private fun sendEnter() {
        val ic = currentInputConnection ?: return
        ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER))
        ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER))
    }
}
