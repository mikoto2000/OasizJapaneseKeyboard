package dev.mikoto2000.oasizjapanesekeyboard.ime

import android.inputmethodservice.InputMethodService
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import dev.mikoto2000.oasizjapanesekeyboard.R

class JapaneseKeyboardService : InputMethodService() {
    private var shiftOn = false
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
                        commitText(text)
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
