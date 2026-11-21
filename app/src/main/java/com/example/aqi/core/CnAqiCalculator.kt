package com.example.aqi.core

import kotlin.math.round

/**
 * 国标 AQI（HJ 633-2012 / GB 3095-2012）分段线性插值。纯函数、无 Android 依赖，
 * 由 tools/verify_cn_breakpoints.py 用独立 Python 实现跑边界探针验证。
 *
 * 公式：IAQI = (Ihi − Ilo)/(BP_hi − BP_lo) × (C − BP_lo) + Ilo，四舍五入取整。
 * AQI = 各污染物 IAQI 的最大值，最大者即首要污染物。
 *
 * 输入浓度先由 [AqiAverager] 按 HJ 633-2012 的窗口做平均：
 * 颗粒物与气态污染物 24h、O₃ 8h。v0.1.5 之前用瞬时值近似代入日均断点，
 * 属口径错配；现在与美标共用同一批 hourly 序列、按各自标准窗口平均。
 *
 * 口径限制（必须对用户可见，见 strings.xml standard_desc_cn）：
 * Open-Meteo 是 CAMS 模型网格数据，不是地面站点实测；即使按窗口平均，
 * 与生态环境部门发布的日均 AQI 仍会因数据源、空间代表性、观测时刻不同而有差异，
 * 不是官方 AQI。
 */
object CnAqiCalculator {

    /** 表 1 的 IAQI 分级，与美标分界一致 */
    val IAQI_LEVELS = listOf(0, 50, 100, 150, 200, 300, 400, 500)

    // ---- 24 小时平均浓度断点（CO 为 mg/m³，其余 µg/m³）----
    val PM25_24H = listOf(0.0, 35.0, 75.0, 115.0, 150.0, 250.0, 350.0, 500.0)
    val PM10_24H = listOf(0.0, 50.0, 150.0, 250.0, 350.0, 420.0, 500.0, 600.0)
    val SO2_24H = listOf(0.0, 50.0, 150.0, 475.0, 800.0, 1600.0, 2100.0, 2620.0)
    val NO2_24H = listOf(0.0, 40.0, 80.0, 180.0, 280.0, 565.0, 750.0, 940.0)
    val CO_24H = listOf(0.0, 2.0, 4.0, 14.0, 24.0, 36.0, 48.0, 60.0)

    /** O₃ 8 小时滑动平均（µg/m³）。标准规定浓度超过 800 时不再计算其分指数。 */
    val O3_8H = listOf(0.0, 100.0, 160.0, 215.0, 265.0, 800.0)
    private val O3_LEVELS = listOf(0, 50, 100, 150, 200, 300)

    data class Pollutant(
        val field: String,                          // Open-Meteo current 字段名
        val label: String,                          // 展示简称
        val breakpoints: List<Double>,
        val levels: List<Int> = IAQI_LEVELS,
        /** 原始单位 → 断点单位的除数：CO 由 µg/m³ 转 mg/m³ 时为 1000 */
        val scale: Double = 1.0,
        /** 超出最高断点：500 = 按爆表记，null = 不参与评价（O₃ 8h 的规则） */
        val overflow: Int? = 500,
    )

    val POLLUTANTS = listOf(
        Pollutant("pm2_5", "PM2.5", PM25_24H),
        Pollutant("pm10", "PM10", PM10_24H),
        Pollutant("sulphur_dioxide", "SO₂", SO2_24H),
        Pollutant("nitrogen_dioxide", "NO₂", NO2_24H),
        Pollutant("carbon_monoxide", "CO", CO_24H, scale = 1000.0),
        Pollutant("ozone", "O₃", O3_8H, levels = O3_LEVELS, overflow = null),
    )

    data class Result(val aqi: Int, val dominant: String, val subIndices: Map<String, Int>)

    /** 单个污染物分指数。NaN / 负浓度返回 null（缺测，不参与评价）。 */
    fun indexOf(concentration: Double, p: Pollutant): Int? {
        if (concentration.isNaN() || concentration < 0.0) return null
        val c = concentration / p.scale
        if (c <= p.breakpoints.first()) return p.levels.first()
        if (c > p.breakpoints.last()) return p.overflow
        for (i in 1 until p.breakpoints.size) {
            val hi = p.breakpoints[i]
            if (c <= hi) {
                val lo = p.breakpoints[i - 1]
                if (hi <= lo) return p.levels[i]
                val raw = (p.levels[i] - p.levels[i - 1]).toDouble() / (hi - lo) * (c - lo) + p.levels[i - 1]
                return round(raw).toInt().coerceIn(0, 500)
            }
        }
        return p.overflow
    }

    /** 输入为 Open-Meteo current 的原始浓度（µg/m³）。全部缺测时返回 aqi = -1。 */
    fun compute(raw: Map<String, Double>): Result {
        val sub = LinkedHashMap<String, Int>()
        POLLUTANTS.forEach { p ->
            val idx = indexOf(raw[p.field] ?: Double.NaN, p)
            if (idx != null) sub[p.label] = idx
        }
        val top = sub.maxByOrNull { it.value } ?: return Result(-1, "", emptyMap())
        return Result(top.value, top.key, sub)
    }
}
