package io.github.theme26.aqibar.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.TypedValue
import androidx.core.content.ContextCompat
import io.github.theme26.aqibar.R
import android.util.AttributeSet
import android.view.View
import io.github.theme26.aqibar.core.AqiLevel
import io.github.theme26.aqibar.render.GaugePainter

class TierRulerView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()

    private val ringColors: IntArray =
        AqiLevel.values().map { GaugePainter.ringColor(it) }.toIntArray()

    private var siteValue = -1
    private var siteColor = Color.GRAY
    private var areaValue = -1
    private var areaColor = Color.GRAY

    private val bandTop = dp(14f)
    private val bandH = dp(7f)
    private val gap = dp(1.5f)
    private val labelTop = dp(30f)

    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_SP, 9.5f, resources.displayMetrics,
        )
        typeface = android.graphics.Typeface.DEFAULT_BOLD
    }
    private val ticks = intArrayOf(0, 50, 100, 150, 200, 300, 500)

    fun setPointers(site: Int, siteColor: Int, area: Int?, areaColor: Int?) {
        siteValue = site
        this.siteColor = siteColor
        areaValue = area ?: -1
        this.areaColor = areaColor ?: siteColor
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        setMeasuredDimension(w, (labelTop + dp(16f) + paddingTop + paddingBottom).toInt())
    }

    override fun onDraw(canvas: Canvas) {
        val left = paddingLeft.toFloat()
        val right = (width - paddingRight).toFloat()
        val cellW = (right - left) / 6f
        if (cellW <= 0f) return

        for (i in 0 until 6) {
            val l = left + i * cellW + gap / 2
            val r = l + cellW - gap
            rect.set(l, bandTop, r, bandTop + bandH)
            paint.color = withAlpha(ringColors[i], 0.55f)
            canvas.drawRoundRect(rect, bandH / 2f, bandH / 2f, paint)
        }

        if (areaValue >= 0) {
            val x = xOf(areaValue, left, cellW)
            paint.color = withAlpha(areaColor, 0.75f)
            canvas.drawRoundRect(
                RectF(x - dp(1f), bandTop - dp(10f), x + dp(1f), bandTop + bandH + dp(6f)),
                dp(1f), dp(1f), paint,
            )
        }

        if (siteValue >= 0) {
            val x = xOf(siteValue, left, cellW)
            paint.color = siteColor
            canvas.drawRoundRect(
                RectF(x - dp(1f), bandTop - dp(2f), x + dp(1f), bandTop + bandH + dp(6f)),
                dp(1f), dp(1f), paint,
            )
            canvas.drawCircle(x, bandTop - dp(6.5f), dp(4.5f), paint)
        }

        labelPaint.color = ContextCompat.getColor(context, R.color.text_dim)
        val baseline = labelTop + dp(9f)
        ticks.forEachIndexed { i, v ->
            val x = left + i * cellW
            val text = v.toString()
            val align = when (i) {
                0 -> Paint.Align.LEFT
                ticks.lastIndex -> Paint.Align.RIGHT
                else -> Paint.Align.CENTER
            }
            labelPaint.textAlign = align
            canvas.drawText(text, x, baseline, labelPaint)
        }
    }

    private fun xOf(value: Int, left: Float, cellW: Float): Float {
        val (idx, frac) = GaugePainter.segmentOf(value)
        val l = left + idx * cellW + gap / 2
        return l + (cellW - gap) * frac
    }

    private fun dp(v: Float): Float = v * resources.displayMetrics.density

    private fun withAlpha(c: Int, a: Float): Int =
        Color.argb((a * 255).toInt().coerceIn(0, 255), Color.red(c), Color.green(c), Color.blue(c))
}
