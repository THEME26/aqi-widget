package com.example.aqi.ui

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.util.AttributeSet
import android.util.TypedValue
import android.view.Gravity
import android.view.LayoutInflater
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.example.aqi.R

/**
 * 分段控件（设计稿 .seg）。
 *
 * 为什么不用 MaterialButtonToggleGroup：本项目没有引 Material 依赖，
 * 引了就得把主题换成 Material3，连带影响小部件预览主题。
 * 这里只需要「一排互斥选项 + 选中块浮起」，20 行代码就能做，且能精确控色。
 *
 * 选中态：滑块用 bg_seg_thumb（比槽亮一档）+ 主文字色 + 中等字重；
 * 未选中：透明 + text_dim。整个控件只有 2.5dp 内边距，滑块高度 = 控件高 - 5dp。
 */
class SegmentedControl @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : LinearLayout(context, attrs, defStyleAttr) {

    private val labels = mutableListOf<String>()
    private var selected = 0
    private var notify = true

    var onSelect: ((Int) -> Unit)? = null

    init {
        orientation = HORIZONTAL
        background = ContextCompat.getDrawable(context, R.drawable.bg_seg_track)
        setPadding(dp(2.5f), dp(2.5f), dp(2.5f), dp(2.5f))
    }

    fun setItems(items: List<String>) {
        labels.clear()
        labels.addAll(items)
        rebuild()
    }

    /** 程序化设置选中项，不触发 [onSelect]（否则会在初始化时连锁触发一次刷新）。 */
    fun select(index: Int) {
        if (index == selected || index !in labels.indices) {
            selected = index
            return
        }
        selected = index
        notify = false
        rebuild()
        notify = true
    }

    fun selectedIndex(): Int = selected

    private fun rebuild() {
        removeAllViews()
        labels.forEachIndexed { i, label ->
            val on = i == selected
            val tv = LayoutInflater.from(context).inflate(R.layout.item_seg, this, false) as TextView
            tv.text = label
            tv.setTextColor(
                ContextCompat.getColor(context, if (on) R.color.text_primary else R.color.text_dim)
            )
            tv.background = if (on) ContextCompat.getDrawable(context, R.drawable.bg_seg_thumb) else null
            tv.setOnClickListener {
                if (selected == i) return@setOnClickListener
                selected = i
                rebuild()
                if (notify) onSelect?.invoke(i)
            }
            addView(
                tv,
                LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
                    if (i < labels.lastIndex) marginEnd = dp(2f)
                },
            )
        }
    }

    private fun dp(v: Float): Int =
        (v * resources.displayMetrics.density + 0.5f).toInt()

    companion object {
        /** 供运行时给滑块上色（例如把选中项染成强调色时用；当前保持中性）。 */
        fun thumb(color: Int): GradientDrawable = GradientDrawable().apply {
            cornerRadius = 9f
            setColor(Color.TRANSPARENT)
            setStroke(1, color)
        }
    }
}
