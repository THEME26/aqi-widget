package com.example.aqi.core

import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * 球面几何小工具（v0.4.5）。
 *
 * 为什么单独抽出来：九宫格要把「8 个最近站点」按**真实方位**摆进 8 个格子，
 * 需要距离 + 方位角两个量。方位错一格，用户看到的就是「东北方向那个站」
 * 其实是西北方向 —— 这类错误靠肉眼看截图发现不了，必须能用固定坐标验证。
 */
object Geo {

    private const val EARTH_R = 6371.0

    /** 两点大圆距离，单位公里（Haversine）。 */
    fun distanceKm(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2).pow(2) +
            cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2).pow(2)
        return 2 * EARTH_R * asin(sqrt(a))
    }

    /**
     * 从 (lat1, lon1) 看向 (lat2, lon2) 的初始方位角，0° = 正北，顺时针递增，返回 [0, 360)。
     *
     * 用初始方位角（initial bearing）而不是恒向线方位：几百公里内两者差 < 1°，
     * 而九宫格一个格子就是 45°，这点差别无关紧要；用标准公式是因为它能被独立验证。
     */
    fun bearingDeg(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val φ1 = Math.toRadians(lat1)
        val φ2 = Math.toRadians(lat2)
        val Δλ = Math.toRadians(lon2 - lon1)
        val y = sin(Δλ) * cos(φ2)
        val x = cos(φ1) * sin(φ2) - sin(φ1) * cos(φ2) * cos(Δλ)
        val deg = Math.toDegrees(atan2(y, x))
        return (deg + 360.0) % 360.0
    }

    /**
     * 方位角 → 九宫格索引：1 = 北、2 = 东北、3 = 东…8 = 西北（与 [AreaPoint.index] 同序）。
     *
     * 每个扇区以正方位为中心、±22.5° 为界：北是 [337.5°, 22.5°)，东北是 [22.5°, 67.5°)。
     * 用四舍五入而不是向下取整，否则正北偏东 1° 会被判成东北。
     */
    fun sectorIndex(bearingDeg: Double): Int {
        val b = ((bearingDeg % 360.0) + 360.0) % 360.0
        val sector = (Math.round(b / 45.0).toInt()) % 8   // 0 = 北
        return sector + 1
    }

    /**
     * 从 (lat, lon) 沿 [bearingDeg] 走 [distanceKm] 后的落点（大圆终点，v0.4.6）。
     *
     * 用来算「正北 25 公里在哪」这类探针点：v0.4.5 的 WAQI 链路靠
     * `map/bounds` 拿站点清单，v0.4.6 改成直接在这 8 个点上问「离你最近的站是哪个」，
     * 于是「环上的点」从展示用的坐标变成了**请求参数**，公式必须可靠。
     *
     * 原来 Open-Meteo 用的小角度近似（dlat = cos(b)*R/111）在 50 公里内误差约 1.5 米，
     * 但既然要两处用同一套「八方位」，就统一成球面公式，别留两份各自演化的实现。
     */
    fun destination(
        lat: Double,
        lon: Double,
        bearingDeg: Double,
        distanceKm: Double,
    ): Pair<Double, Double> {
        val δ = distanceKm / EARTH_R
        val θ = Math.toRadians(bearingDeg)
        val φ1 = Math.toRadians(lat)
        val λ1 = Math.toRadians(lon)
        val sinφ2 = sin(φ1) * cos(δ) + cos(φ1) * sin(δ) * cos(θ)
        val φ2 = asin(sinφ2.coerceIn(-1.0, 1.0))
        val λ2 = λ1 + atan2(sin(θ) * sin(δ) * cos(φ1), cos(δ) - sin(φ1) * sinφ2)
        // 经度归一化到 [-180, 180)，跨 180° 经线时不会跳出去
        val lonDeg = ((Math.toDegrees(λ2) + 540.0) % 360.0) - 180.0
        return Math.toDegrees(φ2) to lonDeg
    }
}
