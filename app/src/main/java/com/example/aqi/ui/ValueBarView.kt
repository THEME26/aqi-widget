package com.example.aqi.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View

/**
 * 单值横条（设计稿 .bar-track / .bar-fill）。
 *
 * 为什么自绘而不是「一个 FrameLayout + 两个 View 调 width」：
 * width 是 LayoutParams，改它必须触发一次 requestLayout + 完整测量；
 * 在 onBind 里对一个刚 inflate 的页面连续改 2 个 width 会多量两遍。
 * 画一条线只要 invalidate 一次，且圆头/半透明底能精确控制。
 */
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

    /**
     * @param fraction 0..1，超出自动收敛
     * @param color    填充色（档位 ink 色）
     * @param alpha    填充不透明度；对照条用 0.55 表示「次要」
     */
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
            // 极窄时 drawRoundRect 会退化成一枚针，改画圆点（与 GaugePainter.bar 同一条规则）
            canvas.drawCircle(h / 2f, r, r, paint)
        } else {
            canvas.drawRoundRect(0f, 0f, fw, h, r, r, paint)
        }
    }

    private fun withAlpha(c: Int, a: Float): Int =
        Color.argb((a * 255).toInt().coerceIn(0, 255), Color.red(c), Color.green(c), Color.blue(c))
}
