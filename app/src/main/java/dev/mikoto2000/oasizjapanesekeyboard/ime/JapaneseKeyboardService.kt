package dev.mikoto2000.oasizjapanesekeyboard.ime

import android.inputmethodservice.InputMethodService
import android.view.KeyEvent
import android.view.View
import dev.mikoto2000.oasizjapanesekeyboard.R

class JapaneseKeyboardService : InputMethodService() {
    override fun onCreateInputView(): View {
        val root = layoutInflater.inflate(R.layout.keyboard_view, null)

        root.findViewById<View>(R.id.key_a).setOnClickListener { commitText("あ") }
        root.findViewById<View>(R.id.key_i).setOnClickListener { commitText("い") }
        root.findViewById<View>(R.id.key_u).setOnClickListener { commitText("う") }

        root.findViewById<View>(R.id.key_space).setOnClickListener { commitText(" ") }
        root.findViewById<View>(R.id.key_delete).setOnClickListener { deleteText() }
        root.findViewById<View>(R.id.key_enter).setOnClickListener { sendEnter() }

        return root
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
