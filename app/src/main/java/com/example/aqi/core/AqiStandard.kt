package com.example.aqi.core

import android.content.Context
import androidx.annotation.StringRes
import com.example.aqi.R

/**
 * AQI 计算标准。
 *
 * - [US]：默认走 **EPA 2024** 断点，由 Open-Meteo 原始浓度 + UsAqiCalculator 本地重算。
 *   之所以不能直接用 WAQI 的 data.aqi：实测 aqicn 官方计算器至今仍是 2012 旧表
 *   （122 µg/m³ → 185），且 WAQI 不返回原始浓度。详见 README 1.2 / 1.4。
 *   v0.1.6 起 US 模式有两条独立链路（见 AqiSource）：OM 本地 2024（默认）与
 *   WAQI 分指数逆推等效浓度后按 2024 重算，两者口径不同、数值不可互推。
 * - [CN]：Open-Meteo 原始浓度按 HJ 633-2012 本地计算，固定单源 OM，不做两源对照。
 *
 * 等级分界（0/50/100/150/200/300）一致，颜色与状态栏 6 档方框图标共用；
 * 不同的只有 AQI 数值与等级中文名。v0.1.6 的状态栏改用三档形状图标，
 * 但 6 档颜色/方框 drawable 仍保留给桌面小部件与屏保。
 *
 * 等级分界（0/50/100/150/200/300）一致，颜色与状态栏 6 档方框图标共用；
 * 不同的只有 AQI 数值与等级中文名。
 */
enum class AqiStandard(
    val id: String,
    val shortLabel: String,
    val fullLabel: String,
) {
    US("us", "US AQI 2024", "美标 US AQI · EPA 2024（本地计算）"),
    CN("cn", "CN AQI", "国标 CN AQI · HJ 633-2012"),
    ;

    /**
     * 缓存口径标签。写进缓存 JSON，读取时校验，防止升级/切换数据源后
     * 把旧口径的数值当成新口径显示（这是最危险的串口径）。
     *
     * v0.1.5 把 `us_2024_local` / `cn_hj633` 升级为 `_avg` 后缀：
     * v0.1.4 缓存的是「瞬时浓度代入 24h 断点」的虚高值（如 413），
     * 升级后必须丢弃，强制按 24h/8h/1h 平均重新拉取，不能把 413 继续显示出来。
     * v0.1.6 起 `us_2012_waqi`（2012 旧口径）整条链路已移除，缓存里若残留会被
     * 期望标签不匹配而丢弃；WAQI 反算源改用独立标签 `us_2024_waqi_recalc`
     * （见 AqiSource.WAQI_RECALC.tag）。本方法现在只用于 OM 链路的标签，
     * 保留 legacyWaqi 形参以兼容旧调用签名，但不再产生 2012 标签。
     */
    @Suppress("UNUSED_PARAMETER")
    fun sourceTag(legacyWaqi: Boolean): String = if (this == CN) "cn_hj633_avg" else "us_2024_avg"

    /**
     * 界面上要用的本地化全名。
     *
     * [fullLabel] 是硬编码中文，只适合写进同步日志/通知这类「技术人员看」的位置；
     * v0.4.0 起详情页副标题、Today 页脚都会直接展示标准名，必须走进字符串资源，
     * 否则英/日/韩界面里会突然冒出一句中文。
     */
    @StringRes
    fun fullLabelRes(): Int = if (this == CN) R.string.std_full_cn else R.string.std_full_us

    fun fullLabel(ctx: Context): String = ctx.getString(fullLabelRes())

    /** 一行内用的短名（通知「口径」行、屏保角标）：美标 2024 / 国标。 */
    @StringRes
    fun shortLabelRes(): Int = if (this == CN) R.string.std_short_cn else R.string.std_short_us

    fun shortLabel(ctx: Context): String = ctx.getString(shortLabelRes())

    companion object {
        fun fromId(id: String?): AqiStandard = values().firstOrNull { it.id == id } ?: US
    }
}
