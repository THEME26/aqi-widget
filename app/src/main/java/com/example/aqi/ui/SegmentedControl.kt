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
        fun thumb(color: Int): GradientDrawable = GradientDrawable().apply {
            cornerRadius = 9f
            setColor(Color.TRANSPARENT)
            setStroke(1, color)
        }
    }
}
