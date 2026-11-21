package com.example.aqi.core

import android.content.Context
import androidx.annotation.StringRes
import com.example.aqi.R

enum class AqiStandard(
    val id: String,
    val shortLabel: String,
    val fullLabel: String,
) {
    US("us", "US AQI 2024", "美标 US AQI · EPA 2024（本地计算）"),
    CN("cn", "CN AQI", "国标 CN AQI · HJ 633-2012"),
    ;

    @Suppress("UNUSED_PARAMETER")
    fun sourceTag(legacyWaqi: Boolean): String = if (this == CN) "cn_hj633_avg" else "us_2024_avg"

    @StringRes
    fun fullLabelRes(): Int = if (this == CN) R.string.std_full_cn else R.string.std_full_us

    fun fullLabel(ctx: Context): String = ctx.getString(fullLabelRes())

    @StringRes
    fun shortLabelRes(): Int = if (this == CN) R.string.std_short_cn else R.string.std_short_us

    fun shortLabel(ctx: Context): String = ctx.getString(shortLabelRes())

    companion object {
        fun fromId(id: String?): AqiStandard = values().firstOrNull { it.id == id } ?: US
    }
}
