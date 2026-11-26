package io.github.theme26.aqibar.core

import android.content.Context
import androidx.annotation.StringRes
import io.github.theme26.aqibar.R

enum class AqiSource(
    val id: String,
    val tag: String,
    val shortLabel: String,
    val needsToken: Boolean,
) {
    OM("om", "us_2024_avg", "OM 本地 2024", false),
    WAQI_RECALC("waqi_recalc", "us_2024_waqi_recalc", "WAQI 反算 2024", true),
    ;

    @StringRes
    fun shortLabelRes(): Int = if (this == WAQI_RECALC) R.string.src_short_waqi else R.string.src_short_om

    fun shortLabel(ctx: Context): String = ctx.getString(shortLabelRes())

    companion object {
        fun fromId(id: String?): AqiSource = values().firstOrNull { it.id == id } ?: OM
        fun fromTag(tag: String): AqiSource? = values().firstOrNull { it.tag == tag }

        fun other(source: AqiSource): AqiSource =
            values().first { it != source }
    }
}
