package com.example.aqi.core

import java.util.Locale

/**
 * WAQI 站点分指数 → 等效浓度 → EPA 2024 重算。
 *
 * ## 为什么能做到（v0.1.6，修正 v0.1.4 说过的「无法反推」）
 * aqicn 公开 `feed` API 的 `iaqi.*.v` 不是原始浓度，而是**已按 EPA 2012 旧断点
 * 换算好的整数分指数**（实测 `data.aqi=89` 与 `iaqi.pm25.v=89` 相等）。
 * 原始浓度/小时数据要走 data-platform 机构申请，ToS 还禁止缓存再分发，所以
 * 「基于实测浓度」这条路走不通。
 *
 * 但分指数是分段线性的单调函数，**可逆**：拿 2012 断点表把分指数逆推回等效浓度，
 * 再喂给 [AqiCalculator.PM25_2024] 就能得到 2024 口径的 PM2.5 分指数。
 * 逆推出来的是 **derived 等效浓度，不是实测浓度**，取整误差 ±1~2 AQI。
 *
 * ## 为什么只碰 PM2.5（本轮 probe 7 的关键发现）
 * EPA 2024 最终规则**只改了 PM2.5 断点**，其余 5 项（PM10 / O₃ / NO₂ / SO₂ / CO）
 * 的断点未变。逐污染物实测「2012 分指数 → 逆推浓度 → 2024 重算」差值全为 +0。
 * 所以无需处理气体的 ppb/ppm 换算与平均窗口（那正是误差最大的地方）：
 * **直接沿用 WAQI 返回的其余 5 项原分指数**，只重算 PM2.5，最后重新取 max。
 *
 * ## WAQI 的平均窗口未实测
 * 站点发布的分指数是否按 24h/8h 平均，本项目没有 probe 证据。若它其实是
 * 「即时值套日均断点」（与 v0.1.4 的 bug 同源），反算行会虚高。因此归因文案
 * 必须写明「非实测浓度、平均窗口未实测」，且两行不可互推、不可互相校验。
 */
object WaqiRecalc {

    /**
     * aqicn 官方 calculator.js 的 `pm25.scale` 实测值（2012 旧表，取整后的连续断点）。
     * 不是 EPA 2012 的 35.4 / 150.4 版本：逆推必须用它真正用来换算的那张表，
     * 否则 150 → 55.4 而不是 55.5，重算结果会偏 1 档。
     */
    private val C_2012 = doubleArrayOf(0.0, 12.0, 35.5, 55.5, 150.5, 250.5, 350.5, 500.5)
    private val I_2012 = intArrayOf(0, 50, 100, 150, 200, 300, 400, 500)

    /** 只认这 6 个污染物 key；WAQI 的 iaqi 里还混着 t/h/w/p 气象字段，不能当 AQI 参与取 max。 */
    private val POLLUTANT_KEYS = setOf("pm25", "pm10", "o3", "no2", "so2", "co")

    /** 反算失败必须显式报错，绝不静默回退到 2012 原值或复用 OM 数值。 */
    class RecalcException(message: String) : IllegalArgumentException(message)

    data class Result(
        val aqi: Int,
        val dominant: String,
        /** PM2.5 已替换为 2024 重算值，其余 5 项保持站点原分指数。 */
        val subIndices: Map<String, Int>,
        /** 由 2012 分指数逆推出的 PM2.5 等效浓度 µg/m³（derived，非实测）。 */
        val equivalentPm25: Double,
        val originalPm25Index: Int,
        val recalculatedPm25Index: Int,
        /** 站点自己报的 data.aqi，仅用于自证；不参与计算。 */
        val reportedAqi: Int,
    )

    /** 2012 分指数 → 等效浓度 µg/m³。分段线性反插值，输入钳到 0~500。 */
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

    /**
     * @param rawSub WAQI `iaqi` 的原始 dict（值取自每个对象的 `v`）。
     * @param reportedAqi WAQI `data.aqi`，只写进诊断文案。
     */
    fun compute(rawSub: Map<String, Int>, reportedAqi: Int): Result {
        // 统一小写 + 只留 6 项污染物 + 丢弃负值，避免气象字段污染 max
        val sub = LinkedHashMap<String, Int>()
        rawSub.forEach { (k, v) ->
            val key = k.lowercase(Locale.US)
            if (key in POLLUTANT_KEYS && v >= 0) sub[key] = v
        }
        if (sub.isEmpty()) throw RecalcException("no valid iaqi sub-indices from WAQI")

        val pm25 = sub["pm25"]
            ?: throw RecalcException("station has no PM2.5 sub-index, cannot recompute with US 2024")

        // 2012 scale 上限 500.5 比 2024 表上限 500.4 高 0.1；AQI 爆表本来就封顶 500，
        // 钳到 500.4 即可，不算「降级」，也不该因为 0.1 的尺度差把 500 判成反算失败。
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
