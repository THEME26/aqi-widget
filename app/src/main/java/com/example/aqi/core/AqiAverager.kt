package com.example.aqi.core

/**
 * 时间平均层：把 Open-Meteo 的**瞬时小时浓度序列**，按 EPA / HJ 633-2012
 * 断点表所要求的平均期做滑动平均，再喂给 [UsAqiCalculator] / [CnAqiCalculator]。
 *
 * ## 为什么必须做（v0.1.5 修正的实测 bug）
 * AQI 断点表定义的是「24 小时平均 PM2.5」「8 小时平均 O₃」这类**平均浓度**，
 * 不是瞬时浓度。Open-Meteo 文档明确 current 与 hourly 的污染物都是 `Instant`。
 * v0.1.4 把瞬时小时值直接代入 24h 断点，在浓度快速爬升时会严重虚高：
 *
 * ```
 * 北京 2026-11-14 23:00  瞬时 PM2.5 = 347.4 µg/m³
 *   瞬时 → EPA 2024 断点 = 413      ← v0.1.4 显示的错误值
 *   24h 平均 = 205.3 µg/m³ → 280   ← 正确口径
 *   Open-Meteo 官方 us_aqi = 278    ← 与 280 吻合
 * ```
 *
 * ## 各标准的平均窗口（小时；1 = 直接用瞬时值）
 * | 字段 | US EPA | HJ 633-2012 |
 * |---|---|---|
 * | pm2_5 / pm10 | 24h | 24h |
 * | ozone | 8h | 8h |
 * | carbon_monoxide | 8h | 24h |
 * | nitrogen_dioxide | 1h | 24h |
 * | sulphur_dioxide | 1h | 24h |
 *
 * US 的 NO₂ / SO₂ 用 1h 瞬时：EPA 现行表就是 1h 断点，且实测
 * `us_aqi_nitrogen_dioxide=29`、`us_aqi_sulphur_dioxide=55` 与瞬时值算出的分指数逐项吻合。
 * HJ 633-2012 的颗粒物与气态污染物断点均为 24h 平均（O₃ 为 8h 滑动），故全部取 24h。
 *
 * 逐项实测对照见 tools/verify_averaging.py。
 */
object AqiAverager {

    /** Open-Meteo 的 6 个原始浓度字段，US / CN 共用同一批 hourly 数据。 */
    val FIELDS = listOf(
        "pm2_5", "pm10", "ozone", "nitrogen_dioxide", "sulphur_dioxide", "carbon_monoxide",
    )

    /** calculator 里的展示简称 → Open-Meteo 字段名（US / CN 的 label 一致）。 */
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

    /**
     * 取 [endIndex] 往前共 [window] 个小时（含当小时）的算术平均。
     * 跳过 NaN / 负值（缺测）；有效点不足窗口一半时返回 NaN，由调用方决定是否回退瞬时。
     * 纯数值函数，tools/verify_averaging.py 有独立 Python 复刻与边界探针。
     */
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
        /** 按窗口平均后的浓度（µg/m³），直接喂给 calculator.compute()。 */
        val raw: Map<String, Double>,
        /** 历史数据不足、被迫回退到瞬时值的字段。UI 必须提示，不能静默。 */
        val fellBackToInstant: List<String>,
        /** 是否完全没有 hourly 数据（此时全部用瞬时，属降级路径）。 */
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
