package dev.mikoto2000.oasizjapanesekeyboard.ime

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import androidx.appcompat.widget.AppCompatButton

class FlickKeyButton @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = android.R.attr.buttonStyle
) : AppCompatButton(context, attrs, defStyleAttr) {
    private val hintPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xAA111111.toInt()
        textAlign = Paint.Align.CENTER
    }

    private var leftHint = ""
    private var upHint = ""
    private var rightHint = ""
    private var downHint = ""

    fun setFlickHints(left: String, up: String, right: String, down: String) {
        leftHint = left
        upHint = up
        rightHint = right
        downHint = down
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        hintPaint.textSize = textSize * 0.58f
        val baselineOffset = (hintPaint.descent() + hintPaint.ascent()) / 2f
        if (upHint.isNotEmpty()) {
            canvas.drawText(upHint, width / 2f, height * 0.24f - baselineOffset, hintPaint)
        }
        if (leftHint.isNotEmpty()) {
            canvas.drawText(leftHint, width * 0.22f, height / 2f - baselineOffset, hintPaint)
        }
        if (rightHint.isNotEmpty()) {
            canvas.drawText(rightHint, width * 0.78f, height / 2f - baselineOffset, hintPaint)
        }
        if (downHint.isNotEmpty()) {
            canvas.drawText(downHint, width / 2f, height * 0.78f - baselineOffset, hintPaint)
        }
    }
}
