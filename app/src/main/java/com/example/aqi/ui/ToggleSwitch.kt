package com.example.aqi.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import android.view.animation.DecelerateInterpolator
import androidx.core.content.ContextCompat
import com.example.aqi.R

/**
 * 设置页的开关（设计稿 .sw：44×26，滑块 21dp）。
 *
 * 为什么不用 [androidx.appcompat.widget.SwitchCompat]：
 * 它按主题里的 thumb/track drawable 计算尺寸与位置，实测在本页会**画到自身边界之外**
 * （右侧越出约 16dp，被卡片圆角切掉，见 v0.4.0 第一版截图）。
 * 这个控件只需要「圆角轨道 + 圆形滑块」，自绘 40 行就能精确控制，
 * 还能让开/关的颜色直接跟上 accent，不必为两套主题各配一套 drawable。
 */
class ToggleSwitch @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()
    private var fraction = 0f

    var isChecked: Boolean = false
        private set

    var onCheckedChange: ((Boolean) -> Unit)? = null

    private val trackOn = ContextCompat.getColor(context, R.color.accent)
    private val trackOff = ContextCompat.getColor(context, R.color.text_dim)
    private val thumbColor = Color.WHITE

    init {
        isClickable = true
        isFocusable = true
        setOnClickListener {
            isChecked = !isChecked
            animateTo(if (isChecked) 1f else 0f)
            onCheckedChange?.invoke(isChecked)
        }
    }

    /** 程序化设置，不回调（避免初始化时连锁触发一次刷新）。 */
    fun setChecked(checked: Boolean, animate: Boolean = false) {
        if (checked == isChecked) return
        isChecked = checked
        if (animate) animateTo(if (checked) 1f else 0f) else {
            fraction = if (checked) 1f else 0f
            invalidate()
        }
    }

    private fun animateTo(target: Float) {
        ValueAnimator.ofFloat(fraction, target).apply {
            duration = 180
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                fraction = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(dp(44f), dp(26f))
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val r = h / 2f
        rect.set(0f, 0f, w, h)
        paint.color = blend(trackOff, trackOn, fraction, 0.32f, 1f)
        canvas.drawRoundRect(rect, r, r, paint)

        val thumbR = r - dp(2.5f)
        val cx = dp(2.5f) + thumbR + (w - dp(5f) - thumbR * 2f) * fraction
        paint.color = thumbColor
        canvas.drawCircle(cx, r, thumbR, paint)
    }

    /** 起点色（关）→ 终点色（开），中间按 [fraction] 线性混合；关态用低透明度压暗。 */
    private fun blend(from: Int, to: Int, t: Float, fromAlpha: Float, toAlpha: Float): Int {
        val a = (fromAlpha + (toAlpha - fromAlpha) * t)
        val r = (Color.red(from) + (Color.red(to) - Color.red(from)) * t).toInt()
        val g = (Color.green(from) + (Color.green(to) - Color.green(from)) * t).toInt()
        val b = (Color.blue(from) + (Color.blue(to) - Color.blue(from)) * t).toInt()
        return Color.argb((a * 255).toInt().coerceIn(0, 255), r, g, b)
    }

    private fun dp(v: Float): Int = (v * resources.displayMetrics.density + 0.5f).toInt()

    override fun getAccessibilityClassName(): CharSequence = "android.widget.Switch"

    override fun onInitializeAccessibilityNodeInfo(info: AccessibilityNodeInfo) {
        super.onInitializeAccessibilityNodeInfo(info)
        info.isCheckable = true
        info.isChecked = isChecked
        info.isClickable = true
    }
}
