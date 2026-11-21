package com.example.aqi.core

import kotlin.math.floor

/**
 * 美标 AQI 分段线性插值（纯函数，无 Android 依赖，可用 tools/verify_breakpoints.py 验证）。
 *
 * 断点采用 EPA 2024-05-06 最终规则（PM2.5 已改，很多在线换算器仍是 2012 旧表）：
 *   Good/Moderate 分界 12.0 → 9.0 µg/m³；Unhealthy 上限 150.4 → 125.4；Hazardous 从 225.5 起。
 * 实测证据见 README 第 1.2 节：aqicn 官方计算器至今仍印着 "122: 185"（2012 旧表），
 * 而 2024 表下 122 µg/m³ 应为 198。
 *
 * v0.1.4 起本类不再是死代码：UsAqiCalculator 用它把 Open-Meteo 的原始浓度本地重算成
 * US AQI 2024。其余污染物断点在 2024 规则中未改动，故沿用 EPA 现行表。
 */
object AqiCalculator {

    data class Band(val cLow: Double, val cHigh: Double, val aLow: Int, val aHigh: Int)

    /** PM2.5 24h 平均，µg/m³，EPA 2024 最终规则 */
    val PM25_2024 = listOf(
        Band(0.0, 9.0, 0, 50),
        Band(9.1, 35.4, 51, 100),
        Band(35.5, 55.4, 101, 150),
        Band(55.5, 125.4, 151, 200),
        Band(125.5, 225.4, 201, 300),
        Band(225.5, 325.4, 301, 400),
        Band(325.5, 500.4, 401, 500),
    )

    /** O₃ 8h 平均，ppb，EPA 2015 规则（8h 无 301+ 官方断点，>200 ppb 需改用 1h O₃） */
    val O3_8H_PPB = listOf(
        Band(0.0, 54.0, 0, 50),
        Band(55.0, 70.0, 51, 100),
        Band(71.0, 85.0, 101, 150),
        Band(86.0, 105.0, 151, 200),
        Band(106.0, 200.0, 201, 300),
    )

    /** PM10 24h 平均，µg/m³ */
    val PM10_24H = listOf(
        Band(0.0, 54.0, 0, 50),
        Band(55.0, 154.0, 51, 100),
        Band(155.0, 254.0, 101, 150),
        Band(255.0, 354.0, 151, 200),
        Band(355.0, 424.0, 201, 300),
        Band(425.0, 604.0, 301, 400),
        Band(605.0, 1004.0, 401, 500),
    )

    /** NO₂ 1h 平均，ppb。EPA 现行表，2024 未改动。 */
    val NO2_1H_PPB = listOf(
        Band(0.0, 53.0, 0, 50),
        Band(54.0, 100.0, 51, 100),
        Band(101.0, 360.0, 101, 150),
        Band(361.0, 649.0, 151, 200),
        Band(650.0, 1249.0, 201, 300),
        Band(1250.0, 1649.0, 301, 400),
        Band(1650.0, 2049.0, 401, 500),
    )

    /**
     * SO₂，ppb。EPA 规定 AQI < 200 用 1h 断点、≥200 用 24h 断点；
     * Open-Meteo 只给瞬时值，无法严格区分为 1h/24h 平均，这里按同一浓度合并两张表
     * （低段 1h、高段 24h），属实时近似，见 disclaimer。
     */
    val SO2_PPB = listOf(
        Band(0.0, 35.0, 0, 50),
        Band(36.0, 75.0, 51, 100),
        Band(76.0, 185.0, 101, 150),
        Band(186.0, 304.0, 151, 200),
        Band(305.0, 604.0, 201, 300),
        Band(605.0, 804.0, 301, 400),
        Band(805.0, 1004.0, 401, 500),
    )

    /** CO 8h 平均，ppm。EPA 现行表，2024 未改动。 */
    val CO_8H_PPM = listOf(
        Band(0.0, 4.4, 0, 50),
        Band(4.5, 9.4, 51, 100),
        Band(9.5, 12.4, 101, 150),
        Band(12.5, 15.4, 151, 200),
        Band(15.5, 30.4, 201, 300),
        Band(30.5, 40.4, 301, 400),
        Band(40.5, 50.4, 401, 500),
    )

    /** EPA 规定浓度先截断到 1 位小数（不是四舍五入）。 */
    fun truncate1(v: Double): Double = floor(v * 10.0) / 10.0

    /**
     * 分段线性插值：AQI = (Ihi−Ilo)/(Bhi−Blo) × (C−Blo) + Ilo，结果四舍五入取整。
     * 超出最高断点返回 null（由调用方决定是否封顶/忽略）。
     */
    fun indexOf(rawConcentration: Double, bands: List<Band>): Int? {
        if (rawConcentration.isNaN() || rawConcentration < 0.0) return null
        val c = truncate1(rawConcentration)
        val band = bands.firstOrNull { c <= it.cHigh } ?: return null
        val span = band.cHigh - band.cLow
        if (span <= 0.0) return band.aLow
        val raw = (band.aHigh - band.aLow) / span * (c - band.cLow) + band.aLow
        return kotlin.math.round(raw).toInt().coerceIn(0, 500)
    }

    /** 总 AQI = 各污染物分指数的最大值。 */
    fun overall(vararg parts: Int?): Int? = parts.filterNotNull().maxOrNull()

    /**
     * ppb → µg/m³（25°C / 1013.25 hPa 标准状态）。
     * 24.45 是该状态下的摩尔体积。与 [ugM3ToPpb] 互逆。
     */
    fun ppbToUgM3(
        ppb: Double,
        molecularWeight: Double,
        tempC: Double = 25.0,
        pressureHPa: Double = 1013.25,
    ): Double {
        val kelvin = tempC + 273.15
        return ppb * molecularWeight / 24.45 * (298.15 / kelvin) * (pressureHPa / 1013.25)
    }

    /** µg/m³ → ppb。ppb = µg/m³ × Vm / MW，Vm 随温度正比、随气压反比。 */
    fun ugM3ToPpb(
        ugM3: Double,
        molecularWeight: Double,
        tempC: Double = 25.0,
        pressureHPa: Double = 1013.25,
    ): Double {
        val kelvin = tempC + 273.15
        return ugM3 * 24.45 / molecularWeight * (kelvin / 298.15) * (1013.25 / pressureHPa)
    }

    /** µg/m³ → ppm（CO 断点用 ppm）。 */
    fun ugM3ToPpm(ugM3: Double, molecularWeight: Double): Double =
        ugM3ToPpb(ugM3, molecularWeight) / 1000.0
}
