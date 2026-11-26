package io.github.theme26.aqibar.core

import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

object Geo {

    private const val EARTH_R = 6371.0

    fun distanceKm(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2).pow(2) +
            cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2).pow(2)
        return 2 * EARTH_R * asin(sqrt(a))
    }

    fun bearingDeg(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val φ1 = Math.toRadians(lat1)
        val φ2 = Math.toRadians(lat2)
        val Δλ = Math.toRadians(lon2 - lon1)
        val y = sin(Δλ) * cos(φ2)
        val x = cos(φ1) * sin(φ2) - sin(φ1) * cos(φ2) * cos(Δλ)
        val deg = Math.toDegrees(atan2(y, x))
        return (deg + 360.0) % 360.0
    }

    fun sectorIndex(bearingDeg: Double): Int {
        val b = ((bearingDeg % 360.0) + 360.0) % 360.0
        val sector = (Math.round(b / 45.0).toInt()) % 8
        return sector + 1
    }

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
        val lonDeg = ((Math.toDegrees(λ2) + 540.0) % 360.0) - 180.0
        return Math.toDegrees(φ2) to lonDeg
    }
}
