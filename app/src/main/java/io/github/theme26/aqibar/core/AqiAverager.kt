package io.github.theme26.aqibar.core

object AqiAverager {

    val FIELDS = listOf(
        "pm2_5", "pm10", "ozone", "nitrogen_dioxide", "sulphur_dioxide", "carbon_monoxide",
    )

    val LABEL_TO_FIELD = mapOf(
        "PM2.5" to "pm2_5",
        "PM10" to "pm10",
        "O₃" to "ozone",
        "NO₂" to "nitrogen_dioxide",
        "SO₂" to "sulphur_dioxide",
        "CO" to "carbon_monoxide",
    )

    private val US_WINDOWS = mapOf(
        "pm2_5" to 24, "pm10" to 24, "ozone" to 8,
        "nitrogen_dioxide" to 1, "sulphur_dioxide" to 1, "carbon_monoxide" to 8,
    )

    private val CN_WINDOWS = mapOf(
        "pm2_5" to 24, "pm10" to 24, "ozone" to 8,
        "nitrogen_dioxide" to 24, "sulphur_dioxide" to 24, "carbon_monoxide" to 24,
    )

    fun windowHours(standard: AqiStandard, field: String): Int =
        (if (standard == AqiStandard.CN) CN_WINDOWS else US_WINDOWS)[field] ?: 24

    fun fieldOf(label: String): String? = LABEL_TO_FIELD[label]

    fun trailingMean(series: DoubleArray, endIndex: Int, window: Int): Double {
        if (endIndex < 0 || window <= 0) return Double.NaN
        val from = maxOf(0, endIndex - window + 1)
        var sum = 0.0
        var n = 0
        for (i in from..endIndex) {
            val v = series.getOrElse(i) { Double.NaN }
            if (!v.isNaN() && v >= 0.0) {
                sum += v
                n++
            }
        }
        val needed = maxOf(1, (window + 1) / 2)
        return if (n < needed) Double.NaN else sum / n
    }

    data class Result(
        val raw: Map<String, Double>,
        val fellBackToInstant: List<String>,
        val hourlyMissing: Boolean,
    )

    fun build(
        hourly: Map<String, DoubleArray>,
        current: Map<String, Double>,
        endIndex: Int,
        standard: AqiStandard,
    ): Result {
        val raw = LinkedHashMap<String, Double>()
        val fellBack = ArrayList<String>()
        FIELDS.forEach { f ->
            val w = windowHours(standard, f)
            val mean = if (w <= 1) Double.NaN
            else trailingMean(hourly[f] ?: DoubleArray(0), endIndex, w)
            val instant = current[f] ?: Double.NaN
            raw[f] = when {
                w <= 1 -> instant
                !mean.isNaN() -> mean
                !instant.isNaN() -> {
                    fellBack.add(f)
                    instant
                }
                else -> Double.NaN
            }
        }
        return Result(raw, fellBack, endIndex < 0)
    }
}
