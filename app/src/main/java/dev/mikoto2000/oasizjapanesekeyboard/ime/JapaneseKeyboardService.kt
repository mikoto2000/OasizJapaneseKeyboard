package dev.mikoto2000.oasizjapanesekeyboard.ime

import android.inputmethodservice.InputMethodService
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout

class JapaneseKeyboardService : InputMethodService() {
    override fun onCreateInputView(): View {
        val context = this
        val root = LinearLayout(context)
        root.orientation = LinearLayout.VERTICAL
        root.layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
        root.setPadding(dp(8), dp(4), dp(8), dp(4))
        root.setBackgroundColor(0xFFEFEFEF.toInt())

        // Row 1: あ い う
        val row1 = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        row1.addView(makeKey("あ") { commitText("あ") })
        row1.addView(makeKey("い") { commitText("い") })
        row1.addView(makeKey("う") { commitText("う") })

        // Row 2: 空白 削除 改行
        val row2 = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        row2.addView(makeKey("空白") { commitText(" ") })
        row2.addView(makeKey("削除") { deleteText() })
        row2.addView(makeKey("改行") { sendEnter() })

        root.addView(row1)
        root.addView(row2)

        return root
    }

    private fun makeKey(label: String, onClick: () -> Unit): View {
        val btn = Button(this)
        val lp = LinearLayout.LayoutParams(0, dp(48), 1f)
        lp.marginStart = dp(4)
        lp.marginEnd = dp(4)
        lp.topMargin = dp(4)
        lp.bottomMargin = dp(4)
        btn.layoutParams = lp
        btn.text = label
        btn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
        btn.setOnClickListener { onClick() }
        return btn
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

    private fun dp(value: Int): Int {
        val metrics = resources.displayMetrics
        return (value * metrics.density).toInt()
    }
}

