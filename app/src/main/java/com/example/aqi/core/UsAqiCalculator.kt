package com.example.aqi.core

object UsAqiCalculator {

    val FIELDS = listOf(
        "pm2_5", "pm10", "ozone", "nitrogen_dioxide", "sulphur_dioxide", "carbon_monoxide",
    )

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

    fun compute(raw: Map<String, Double>): Result {
        val sub = LinkedHashMap<String, Int>()
        SPECIES.forEach { s ->
            val c = raw[s.field] ?: Double.NaN
            if (c.isNaN() || c < 0.0) return@forEach
            val bp = s.toBreakpointUnit(c)
            var idx = AqiCalculator.indexOf(bp, s.bands)
            if (idx == null && s.field == "ozone" && bp > 200.0) idx = 300
            if (idx != null) sub[s.label] = idx
        }
        val top = sub.maxByOrNull { it.value } ?: return Result(-1, "", emptyMap())
        return Result(top.value, top.key, sub)
    }
}
