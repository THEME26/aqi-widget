package com.example.aqi.core

import android.content.Context
import android.graphics.Color
import androidx.annotation.ColorInt
import androidx.annotation.ColorRes
import androidx.annotation.StringRes
import androidx.annotation.DrawableRes
import androidx.core.content.ContextCompat
import com.example.aqi.R

/**
 * 6 档 AQI 等级。颜色取 EPA / AirNow 现行官方 RGB。
 *
 * 两套标准（US EPA 与 HJ 633-2012）的分级界限完全相同（50/100/150/200/300），
 * 所以颜色、底色、状态栏 6 档方框图标全部共用；不同的只是等级中文名
 * （[cn] 美标 / [cnStandard] 国标）与 AQI 数值本身。
 *
 * 注意有两套文字色语义：
 *  - solidHex：纯色底最小款（1×1 / 1×2）上的文字色，按 WCAG 对比度单独选。
 *    橙 #FF7E00 底上白字仅约 2.5:1，必须用深字（约 7.5:1），绿/黄同理。
 *  - 小色块 / 通知圆底由系统或 setColor() 处理，不走这里。
 */
enum class AqiLevel(
    val from: Int,
    val to: Int,
    val cn: String,             // 美标等级中文名
    val en: String,
    val cnStandard: String,     // 国标等级中文名（HJ 633-2012）
    val hex: String,
    val solidHex: String,
    @DrawableRes val bgRes: Int,
    @DrawableRes val statIconRes: Int,
    val advice: String,
) {
    GOOD(
        0, 50, "优", "Good", "优", "#00E400", "#08110A",
        R.drawable.bg_aqi_good, R.drawable.ic_stat_l1,
        "空气质量令人满意，可正常户外活动"
    ),
    MODERATE(
        51, 100, "良", "Moderate", "良", "#FFFF00", "#0A0C11",
        R.drawable.bg_aqi_moderate, R.drawable.ic_stat_l2,
        "空气质量可接受，极少数敏感人群注意"
    ),
    USG(
        101, 150, "敏感人群不健康", "USG", "轻度污染", "#FF7E00", "#0A0C11",
        R.drawable.bg_aqi_usg, R.drawable.ic_stat_l3,
        "敏感人群减少长时间户外剧烈运动"
    ),
    UNHEALTHY(
        151, 200, "不健康", "Unhealthy", "中度污染", "#FF0000", "#FFFFFF",
        R.drawable.bg_aqi_unhealthy, R.drawable.ic_stat_l4,
        "所有人减少户外活动，敏感人群避免外出"
    ),
    VERY_UNHEALTHY(
        201, 300, "非常不健康", "Very Unhealthy", "重度污染", "#8F3F97", "#FFFFFF",
        R.drawable.bg_aqi_very_unhealthy, R.drawable.ic_stat_l5,
        "健康警报，尽量留在室内"
    ),
    HAZARDOUS(
        301, 500, "危险", "Hazardous", "严重污染", "#7E0023", "#FFFFFF",
        R.drawable.bg_aqi_hazardous, R.drawable.ic_stat_l6,
        "紧急状况，所有人避免户外活动"
    ),
    ;

    /** 分级界限一致，按标准选中文名。 */
    fun label(standard: AqiStandard): String =
        if (standard == AqiStandard.CN) cnStandard else cn

    /**
     * 等级名的本地化资源。v0.4.1 起界面**一律走这里**，不再用 [label]。
     *
     * [cn] / [cnStandard] 是硬编码中文，只在数据层日志里还能用；
     * 之前英文界面六档表直接显示“优 / 良 / 敏感人群不健康”，
     * 日韩法西德五种语言更是全中文 —— 这就是“中英文混用”最集中的一处。
     */
    @StringRes
    fun labelRes(standard: AqiStandard): Int = if (standard == AqiStandard.CN) {
        when (this) {
            GOOD -> R.string.lv_cn_good
            MODERATE -> R.string.lv_cn_moderate
            USG -> R.string.lv_cn_usg
            UNHEALTHY -> R.string.lv_cn_unhealthy
            VERY_UNHEALTHY -> R.string.lv_cn_very_unhealthy
            HAZARDOUS -> R.string.lv_cn_hazardous
        }
    } else {
        when (this) {
            GOOD -> R.string.lv_good
            MODERATE -> R.string.lv_moderate
            USG -> R.string.lv_usg
            UNHEALTHY -> R.string.lv_unhealthy
            VERY_UNHEALTHY -> R.string.lv_very_unhealthy
            HAZARDOUS -> R.string.lv_hazardous
        }
    }

    fun label(ctx: Context, standard: AqiStandard): String = ctx.getString(labelRes(standard))

    @ColorInt
    fun colorInt(): Int = Color.parseColor(hex)

    @ColorInt
    fun solidTextInt(): Int = Color.parseColor(solidHex)

    /**
     * 等级色的**文字版**：浅色模式下用加深 ink，深色模式下用提亮版。
     * 直接用 [hex] 当文字色会踩两个坑：黄 #FFFF00 在白底几乎不可见、
     * 绿 #00E400 在白底对比度约 1.6:1。ink 由 colors.xml 的两套值分别给出。
     */
    @ColorRes
    fun textColorRes(): Int = when (this) {
        GOOD -> R.color.aqi_good_ink
        MODERATE -> R.color.aqi_moderate_ink
        USG -> R.color.aqi_usg_ink
        UNHEALTHY -> R.color.aqi_unhealthy_ink
        VERY_UNHEALTHY -> R.color.aqi_very_unhealthy_ink
        HAZARDOUS -> R.color.aqi_hazardous_ink
    }

    @ColorInt
    fun textColorInt(ctx: Context): Int = ContextCompat.getColor(ctx, textColorRes())

    companion object {
        fun of(value: Int): AqiLevel = when {
            value <= 50 -> GOOD
            value <= 100 -> MODERATE
            value <= 150 -> USG
            value <= 200 -> UNHEALTHY
            value <= 300 -> VERY_UNHEALTHY
            else -> HAZARDOUS
        }
    }
}
