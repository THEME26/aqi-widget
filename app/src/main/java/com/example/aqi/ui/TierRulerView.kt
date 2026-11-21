package com.example.aqi.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.TypedValue
import androidx.core.content.ContextCompat
import com.example.aqi.R
import android.util.AttributeSet
import android.view.View
import com.example.aqi.core.AqiLevel
import com.example.aqi.render.GaugePainter

/**
 * 六格尺（设计稿「详情」页那条横尺）。
 *
 * 它不是进度条，也不是 0–500 的线性刻度：6 档等宽，**指针在档内的位置**
 * 由 [GaugePainter.segmentOf] 给出，与环、小部件横条共用同一套几何。
 * 如果把 152 按 v/500 摆到 30% 处，就会和第三格（101–150）的色块对不上 ——
 * 那是上一版设计稿犯过的错。
 *
 * 两个指针：
 *  - 本站：实心圆 + 竖线（主读数，与卡片数字同色）
 *  - 区域：细竖线（对照值，半透明）
 */
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

    /** 刻度文字：9.5sp，弱色。画在视图内部而不是用一排 TextView ——
        只有在这里才能保证「50」正好落在第一格与第二格的分界线上。
        用等宽权重的 TextView 排会导致标签落在格中心，和色段对不上。 */
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

        // 六段底：亮色 @55%，是「量程」而不是读数
        for (i in 0 until 6) {
            val l = left + i * cellW + gap / 2
            val r = l + cellW - gap
            rect.set(l, bandTop, r, bandTop + bandH)
            paint.color = withAlpha(ringColors[i], 0.55f)
            canvas.drawRoundRect(rect, bandH / 2f, bandH / 2f, paint)
        }

        // 区域指针：细竖线，先画（在下层），不抢本站的视觉
        if (areaValue >= 0) {
            val x = xOf(areaValue, left, cellW)
            paint.color = withAlpha(areaColor, 0.75f)
            canvas.drawRoundRect(
                RectF(x - dp(1f), bandTop - dp(10f), x + dp(1f), bandTop + bandH + dp(6f)),
                dp(1f), dp(1f), paint,
            )
        }

        // 本站指针：圆头 + 竖线，与卡片大数字同色
        if (siteValue >= 0) {
            val x = xOf(siteValue, left, cellW)
            paint.color = siteColor
            canvas.drawRoundRect(
                RectF(x - dp(1f), bandTop - dp(2f), x + dp(1f), bandTop + bandH + dp(6f)),
                dp(1f), dp(1f), paint,
            )
            canvas.drawCircle(x, bandTop - dp(6.5f), dp(4.5f), paint)
        }

        // 刻度：0 左对齐、500 右对齐、其余居中在分界线上
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

    /** 值 → 横坐标：段起点 + 段内比例，几何与环完全一致。 */
    private fun xOf(value: Int, left: Float, cellW: Float): Float {
        val (idx, frac) = GaugePainter.segmentOf(value)
        val l = left + idx * cellW + gap / 2
        return l + (cellW - gap) * frac
    }

    private fun dp(v: Float): Float = v * resources.displayMetrics.density

    private fun withAlpha(c: Int, a: Float): Int =
        Color.argb((a * 255).toInt().coerceIn(0, 255), Color.red(c), Color.green(c), Color.blue(c))
}
