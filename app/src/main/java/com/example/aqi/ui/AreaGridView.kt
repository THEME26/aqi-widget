package com.example.aqi.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.util.AttributeSet
import android.util.TypedValue
import android.view.View
import androidx.core.content.ContextCompat
import com.example.aqi.R
import com.example.aqi.core.AqiLevel

/**
 * 周边九宫格（v0.4.5）。**位置 = 地理方位**：
 *
 * ```
 *   NW   N   NE        ← 索引 8, 1, 2
 *    W   ·   E         ← 索引 7, 0(你), 3
 *   SW   S   SE        ← 索引 6, 5, 4
 * ```
 *
 * 为什么从 v0.4.2 的圆环退回方格：
 *  - 圆环只有 15dp 的点，9 个三位数（"203"）根本放不下，图上只能画点、数字另标；
 *  - 方格天然表达「这一格是哪个方向」，用户看一眼就知道东北角是东北方向；
 *  - 点击热区从 26dp 的圆放大到 50dp+ 的方块，手指不会点错。
 *
 * 代价是四个角看起来比四个边远 —— 但九宫格本来就不是等距投影，而采样半径
 * 在标题里写得很清楚（「你周围 25 公里」），不会有人把格子间距当尺子量。
 *
 * 读图规则（图上不写字，靠颜色和层级）：
 *  - 中心格 = 你（或最近站点），1.5dp 主文字色描边，字号大一号；
 *  - 其余 8 格 = 该方位的值，底色 = 档色 16% 透明度，数字 = 档色文字版；
 *  - 最大值那格加一道 1.5dp 档色实线描边 = 「这一片最差的方向」。
 *
 * 缺失格（-1）画成灰底「–」，绝不用 0 冒充「空气极好」。
 */
class AreaGridView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val number = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = Typeface.DEFAULT_BOLD
    }

    /** 9 个点，顺序 = [中心, 北, 东北, 东, 东南, 南, 西南, 西, 西北]。 */
    private var values: List<Int> = emptyList()

    /** 每格的屏幕矩形（与 [values] 同序），onDraw 时更新，供点击命中使用。 */
    private val rects = Array(9) { RectF() }

    /** 点中某一格（0 = 中心）。null 表示没有数据/旧缓存，点了不响应。 */
    var onPointTap: ((Int) -> Unit)? = null

    private var downX = 0f
    private var downY = 0f

    private val gap = dp(4f)
    private val neutralDim = ContextCompat.getColor(context, R.color.text_dim)
    private val neutralFill = ContextCompat.getColor(context, R.color.surface_2)
    private val ink = ContextCompat.getColor(context, R.color.text_primary)

    init {
        isClickable = true
        isFocusable = true
    }

    fun setValues(v: List<Int>) {
        values = v
        invalidate()
    }

    /** 九宫格里 9 个索引 → 行列。中心的邻居顺序必须与外圈方位一致。 */
    private fun rowOf(i: Int): Int = when (i) {
        8, 1, 2 -> 0
        7, 0, 3 -> 1
        else -> 2
    }

    private fun colOf(i: Int): Int = when (i) {
        8, 7, 6 -> 0
        1, 0, 5 -> 1
        else -> 2
    }

    private fun hitTest(x: Float, y: Float): Int {
        // 没有数据时格子照画（灰底占位），但不响应点击：点了也没有可展示的东西
        if (values.size != 9) return -1
        for (i in rects.indices) if (rects[i].contains(x, y)) return i
        return -1
    }

    override fun onTouchEvent(event: android.view.MotionEvent): Boolean {
        when (event.actionMasked) {
            android.view.MotionEvent.ACTION_DOWN -> {
                downX = event.x
                downY = event.y
                return true
            }
            android.view.MotionEvent.ACTION_UP -> {
                // 手指移出格子就算滑动页面，不弹详情
                if (kotlin.math.abs(event.x - downX) < dp(12f) &&
                    kotlin.math.abs(event.y - downY) < dp(12f)
                ) {
                    val i = hitTest(event.x, event.y)
                    if (i >= 0) {
                        performClick()
                        onPointTap?.invoke(i)
                        return true
                    }
                }
            }
        }
        return super.onTouchEvent(event)
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        // 宽度由布局给（weight / 固定 dp），高度按宽度算成正方形 —— 九宫格必须方，
        // 拉成长条后数字会被挤在格子中央的窄条里，看着像没对齐。
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val hSpec = MeasureSpec.getMode(heightMeasureSpec)
        val h = if (hSpec == MeasureSpec.EXACTLY) {
            MeasureSpec.getSize(heightMeasureSpec)
        } else {
            w
        }
        setMeasuredDimension(w, h)
    }

    override fun onDraw(canvas: Canvas) {
        val side = minOf(width, height)
        if (side <= 0) return
        val cell = (side - gap * 2) / 3f
        val left = (width - (cell * 3 + gap * 2)) / 2f
        val top = (height - (cell * 3 + gap * 2)) / 2f

        val max = if (values.size == 9) values.filter { it >= 0 }.maxOrNull() ?: -1 else -1

        for (i in 0..8) {
            val l = left + colOf(i) * (cell + gap)
            val t = top + rowOf(i) * (cell + gap)
            rects[i].set(l, t, l + cell, t + cell)
            val v = values.getOrElse(i) { -1 }
            drawCell(canvas, rects[i], v, i == 0, values.size == 9 && v >= 0 && v == max)
        }
    }

    private fun drawCell(
        canvas: Canvas,
        r: RectF,
        value: Int,
        isCenter: Boolean,
        isMax: Boolean,
    ) {
        val level = if (value >= 0) AqiLevel.of(value) else null
        val radius = dp(12f)

        fill.color = if (level != null) {
            withAlpha(level.colorInt(), if (isCenter) 0.22f else 0.15f)
        } else {
            neutralFill
        }
        canvas.drawRoundRect(r, radius, radius, fill)

        // 描边：中心 = 主文字色（「你在这里」），最糟格 = 该档实色（「这片最差」）
        when {
            isCenter -> {
                stroke.color = withAlpha(ink, 0.55f)
                stroke.strokeWidth = dp(1.5f)
                canvas.drawRoundRect(r, radius, radius, stroke)
            }
            isMax -> {
                stroke.color = level!!.colorInt()
                stroke.strokeWidth = dp(1.5f)
                canvas.drawRoundRect(r, radius, radius, stroke)
            }
        }

        number.textSize = sp(if (isCenter) 17f else 15f)
        number.color = level?.textColorInt(context) ?: neutralDim
        val text = if (value >= 0) value.toString() else "–"
        val cy = r.centerY() - (number.ascent() + number.descent()) / 2f
        canvas.drawText(text, r.centerX(), cy, number)
    }

    private fun dp(v: Float): Float = v * resources.displayMetrics.density

    private fun sp(v: Float): Float = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_SP, v, resources.displayMetrics,
    )

    private fun withAlpha(c: Int, a: Float): Int =
        Color.argb((a * 255).toInt().coerceIn(0, 255), Color.red(c), Color.green(c), Color.blue(c))
}
