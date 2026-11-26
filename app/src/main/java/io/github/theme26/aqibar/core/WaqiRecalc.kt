package io.github.theme26.aqibar.core

import java.util.Locale

object WaqiRecalc {

    private val C_2012 = doubleArrayOf(0.0, 12.0, 35.5, 55.5, 150.5, 250.5, 350.5, 500.5)
    private val I_2012 = intArrayOf(0, 50, 100, 150, 200, 300, 400, 500)

    private val POLLUTANT_KEYS = setOf("pm25", "pm10", "o3", "no2", "so2", "co")

    class RecalcException(message: String) : IllegalArgumentException(message)

    data class Result(
        val aqi: Int,
        val dominant: String,
        val subIndices: Map<String, Int>,
        val equivalentPm25: Double,
        val originalPm25Index: Int,
        val recalculatedPm25Index: Int,
        val reportedAqi: Int,
    )

    fun equivalentConcentration(index2012: Int): Double {
        val v = index2012.coerceIn(0, 500)
        for (i in 1 until I_2012.size) {
            if (v <= I_2012[i]) {
                val iLo = I_2012[i - 1]
                val iHi = I_2012[i]
                if (iHi == iLo) return C_2012[i]
                return C_2012[i - 1] +
                    (v - iLo).toDouble() / (iHi - iLo) * (C_2012[i] - C_2012[i - 1])
            }
        }
        return C_2012.last()
    }

    fun compute(rawSub: Map<String, Int>, reportedAqi: Int): Result {
        val sub = LinkedHashMap<String, Int>()
        rawSub.forEach { (k, v) ->
            val key = k.lowercase(Locale.US)
            if (key in POLLUTANT_KEYS && v >= 0) sub[key] = v
        }
        if (sub.isEmpty()) throw RecalcException("no valid iaqi sub-indices from WAQI")

        val pm25 = sub["pm25"]
            ?: throw RecalcException("station has no PM2.5 sub-index, cannot recompute with US 2024")

        val c = equivalentConcentration(pm25).coerceAtMost(500.4)
        val recalculated = AqiCalculator.indexOf(c, AqiCalculator.PM25_2024)
            ?: throw RecalcException(
                "equivalent concentration ${String.format(Locale.US, "%.1f", c)} µg/m³ 超出 2024 断点上限，无法重算"
            )

        val merged = LinkedHashMap(sub)
        merged["pm25"] = recalculated
        val top = merged.maxByOrNull { it.value }
            ?: throw RecalcException("empty sub-index map")

        return Result(
            aqi = top.value,
            dominant = top.key,
            subIndices = merged,
            equivalentPm25 = c,
            originalPm25Index = pm25,
            recalculatedPm25Index = recalculated,
            reportedAqi = reportedAqi,
        )
    }
}
