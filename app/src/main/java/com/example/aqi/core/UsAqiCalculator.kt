package com.example.aqi.core

/**
 * US AQI（EPA 2024）本地计算器。输入按 EPA 窗口平均后的原始污染物浓度（µg/m³），
 * 输出各污染物分指数与总 AQI。纯函数、无 Android 依赖，由 tools/verify_epa2024.py
 * 用独立 Python 实现跑边界探针验证。
 *
 * 为什么必须本地算：WAQI 的 data.aqi 是已按 **2012 旧断点** 换算好的整数分指数
 * （aqicn 官方计算器实测 "122 → 185"），且 API 不提供原始浓度，数学上不可逆。
 * 要满足 2024 口径（122 → 198）只能换成给原始浓度的数据源再本地重算。
 *
 * 输入浓度必须先由 [AqiAverager] 按 EPA 规定的窗口做平均：
 * PM2.5 / PM10 24h、O₃ / CO 8h、NO₂ / SO₂ 1h。v0.1.5 之前把瞬时值直接代入
 * 24h 断点，实测北京瞬时 347.4 µg/m³ 会算成 413，而 24h 平均 205.3 → 280
 * （Open-Meteo 官方 us_aqi = 278）。本类只负责断点插值，不做平均。
 *
 * 口径限制（与 CN 模式同源，须对用户可见）：
 *  - Open-Meteo 是 CAMS 模型网格数据，不是地面监测站点实测；即使按窗口平均，
 *    与 EPA / AirNow 官方发布的 AQI 仍会因数据源、空间代表性、观测时刻不同而有差异。
 *  - O₃ 8h 断点只定义到 AQI 300（200 ppb）。更高时 EPA 要求改用 1h O₃，
 *    本数据源不提供，故保守封顶 300，不虚构数值。
 *  - SO₂ 高段借用 EPA 24h 断点（见 AqiCalculator.SO2_PPB 注释）。
 */
object UsAqiCalculator {

    /** Open-Meteo 需要的字段，两个标准共用同一次请求。 */
    val FIELDS = listOf(
        "pm2_5", "pm10", "ozone", "nitrogen_dioxide", "sulphur_dioxide", "carbon_monoxide",
    )

    // 分子量 g/mol（25°C / 1013.25 hPa）
    const val MW_O3 = 48.00
    const val MW_NO2 = 46.0055
    const val MW_SO2 = 64.066
    const val MW_CO = 28.01

    private data class Species(
        val field: String,
        val label: String,
        val bands: List<AqiCalculator.Band>,
        val toBreakpointUnit: (Double) -> Double,
    )

    private val SPECIES = listOf(
        Species("pm2_5", "PM2.5", AqiCalculator.PM25_2024) { it },
        Species("pm10", "PM10", AqiCalculator.PM10_24H) { it },
        Species("ozone", "O₃", AqiCalculator.O3_8H_PPB) {
            AqiCalculator.ugM3ToPpb(it, MW_O3)
        },
        Species("nitrogen_dioxide", "NO₂", AqiCalculator.NO2_1H_PPB) {
            AqiCalculator.ugM3ToPpb(it, MW_NO2)
        },
        Species("sulphur_dioxide", "SO₂", AqiCalculator.SO2_PPB) {
            AqiCalculator.ugM3ToPpb(it, MW_SO2)
        },
        Species("carbon_monoxide", "CO", AqiCalculator.CO_8H_PPM) {
            AqiCalculator.ugM3ToPpm(it, MW_CO)
        },
    )

    data class Result(val aqi: Int, val dominant: String, val subIndices: Map<String, Int>)

    /** 输入为按 EPA 窗口平均后的浓度（µg/m³）。全部缺测时返回 aqi = -1。 */
    fun compute(raw: Map<String, Double>): Result {
        val sub = LinkedHashMap<String, Int>()
        SPECIES.forEach { s ->
            val c = raw[s.field] ?: Double.NaN
            if (c.isNaN() || c < 0.0) return@forEach
            val bp = s.toBreakpointUnit(c)
            var idx = AqiCalculator.indexOf(bp, s.bands)
            // 8h O₃ 断点只到 200 ppb / AQI 300；更高需 1h O₃，本源没有 → 封顶，不臆造
            if (idx == null && s.field == "ozone" && bp > 200.0) idx = 300
            if (idx != null) sub[s.label] = idx
        }
        val top = sub.maxByOrNull { it.value } ?: return Result(-1, "", emptyMap())
        return Result(top.value, top.key, sub)
    }
}
