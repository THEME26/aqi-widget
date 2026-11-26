package io.github.theme26.aqibar.ui

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
import io.github.theme26.aqibar.R
import io.github.theme26.aqibar.core.AqiLevel

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

    private var values: List<Int> = emptyList()

    private val rects = Array(9) { RectF() }

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
