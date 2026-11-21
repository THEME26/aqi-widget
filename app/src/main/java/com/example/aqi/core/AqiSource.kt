package com.example.aqi.core

import android.content.Context
import androidx.annotation.StringRes
import com.example.aqi.R

/**
 * v0.1.6「双源并列」的两个 US AQI 数据源。
 *
 * 重要：这不是「两种标准」，而是**同一套 US EPA 2024 口径下的两条独立数据链路**：
 *  - [OM]：Open-Meteo 原始浓度 → 按 24h/8h/1h 窗口平均 → [UsAqiCalculator] 本地算。
 *    无需 Token，是用户拍板的默认主源。
 *  - [WAQI_RECALC]：WAQI 站点**已发布的 2012 分指数** → 逆推 PM2.5 等效浓度 →
 *    按 EPA 2024 重算 → 与其余 5 项原分指数取 max。需要 Token。
 *
 * 两者口径不同（模型网格 vs 地面站点、平均窗口不同），**数值不可互推、档位可能打架**，
 * 所以缓存按源分槽、主卡两行各自标注口径，状态栏只跟随 [AqiPrefs.primarySource]。
 *
 * CN AQI 模式固定单源 [OM]，不蔓延到反算链路（WAQI 分指数是 US 口径）。
 *
 * [tag] 写进缓存 JSON，读取时校验，防止升级/切源后串口径。
 */
enum class AqiSource(
    val id: String,
    val tag: String,
    val shortLabel: String,
    val needsToken: Boolean,
) {
    OM("om", "us_2024_avg", "OM 本地 2024", false),
    WAQI_RECALC("waqi_recalc", "us_2024_waqi_recalc", "WAQI 反算 2024", true),
    ;

    /** 本地化短名。硬编码的 [shortLabel] 只用于缓存/日志，界面走这里。 */
    @StringRes
    fun shortLabelRes(): Int = if (this == WAQI_RECALC) R.string.src_short_waqi else R.string.src_short_om

    fun shortLabel(ctx: Context): String = ctx.getString(shortLabelRes())

    companion object {
        fun fromId(id: String?): AqiSource = values().firstOrNull { it.id == id } ?: OM
        fun fromTag(tag: String): AqiSource? = values().firstOrNull { it.tag == tag }

        /** 与 [source] 相对的另一源；用于主卡第二行与副槽刷新。 */
        fun other(source: AqiSource): AqiSource =
            values().first { it != source }
    }
}
