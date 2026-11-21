package com.example.aqi.core

import android.content.Context
import android.graphics.Color
import androidx.annotation.ColorInt
import androidx.annotation.ColorRes
import androidx.annotation.StringRes
import androidx.annotation.DrawableRes
import androidx.core.content.ContextCompat
import com.example.aqi.R

enum class AqiLevel(
    val from: Int,
    val to: Int,
    val cn: String,
    val en: String,
    val cnStandard: String,
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

    fun label(standard: AqiStandard): String =
        if (standard == AqiStandard.CN) cnStandard else cn

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
