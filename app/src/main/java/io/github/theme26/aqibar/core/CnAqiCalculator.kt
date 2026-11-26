package io.github.theme26.aqibar.core

import kotlin.math.round

object CnAqiCalculator {

    val IAQI_LEVELS = listOf(0, 50, 100, 150, 200, 300, 400, 500)

    val PM25_24H = listOf(0.0, 35.0, 75.0, 115.0, 150.0, 250.0, 350.0, 500.0)
    val PM10_24H = listOf(0.0, 50.0, 150.0, 250.0, 350.0, 420.0, 500.0, 600.0)
    val SO2_24H = listOf(0.0, 50.0, 150.0, 475.0, 800.0, 1600.0, 2100.0, 2620.0)
    val NO2_24H = listOf(0.0, 40.0, 80.0, 180.0, 280.0, 565.0, 750.0, 940.0)
    val CO_24H = listOf(0.0, 2.0, 4.0, 14.0, 24.0, 36.0, 48.0, 60.0)

    val O3_8H = listOf(0.0, 100.0, 160.0, 215.0, 265.0, 800.0)
    private val O3_LEVELS = listOf(0, 50, 100, 150, 200, 300)

    data class Pollutant(
        val field: String,
        val label: String,
        val breakpoints: List<Double>,
        val levels: List<Int> = IAQI_LEVELS,
        val scale: Double = 1.0,
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
