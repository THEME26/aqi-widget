package io.github.theme26.aqibar.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View

class ValueBarView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var fraction = 0f
    private var color = Color.GRAY
    private var fillAlpha = 1f
    private var trackAlpha = 0.13f

    fun set(fraction: Float, color: Int, alpha: Float = 1f, trackAlpha: Float = 0.13f) {
        this.fraction = fraction.coerceIn(0f, 1f)
        this.color = color
        this.fillAlpha = alpha
        this.trackAlpha = trackAlpha
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val h = height.toFloat()
        val w = width.toFloat()
        if (h <= 0f || w <= 0f) return
        val r = h / 2f

        paint.color = withAlpha(color, trackAlpha)
        canvas.drawRoundRect(0f, 0f, w, h, r, r, paint)

        if (fraction <= 0f) return
        val fw = w * fraction
        paint.color = withAlpha(color, fillAlpha)
        if (fw < h) {
            canvas.drawCircle(h / 2f, r, r, paint)
        } else {
            canvas.drawRoundRect(0f, 0f, fw, h, r, r, paint)
        }
    }

    private fun withAlpha(c: Int, a: Float): Int =
        Color.argb((a * 255).toInt().coerceIn(0, 255), Color.red(c), Color.green(c), Color.blue(c))
}
