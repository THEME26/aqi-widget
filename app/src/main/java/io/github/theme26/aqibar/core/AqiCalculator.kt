package io.github.theme26.aqibar.core

import kotlin.math.floor

object AqiCalculator {

    data class Band(val cLow: Double, val cHigh: Double, val aLow: Int, val aHigh: Int)

    val PM25_2024 = listOf(
        Band(0.0, 9.0, 0, 50),
        Band(9.1, 35.4, 51, 100),
        Band(35.5, 55.4, 101, 150),
        Band(55.5, 125.4, 151, 200),
        Band(125.5, 225.4, 201, 300),
        Band(225.5, 325.4, 301, 400),
        Band(325.5, 500.4, 401, 500),
    )

    val O3_8H_PPB = listOf(
        Band(0.0, 54.0, 0, 50),
        Band(55.0, 70.0, 51, 100),
        Band(71.0, 85.0, 101, 150),
        Band(86.0, 105.0, 151, 200),
        Band(106.0, 200.0, 201, 300),
    )

    val PM10_24H = listOf(
        Band(0.0, 54.0, 0, 50),
        Band(55.0, 154.0, 51, 100),
        Band(155.0, 254.0, 101, 150),
        Band(255.0, 354.0, 151, 200),
        Band(355.0, 424.0, 201, 300),
        Band(425.0, 604.0, 301, 400),
        Band(605.0, 1004.0, 401, 500),
    )

    val NO2_1H_PPB = listOf(
        Band(0.0, 53.0, 0, 50),
        Band(54.0, 100.0, 51, 100),
        Band(101.0, 360.0, 101, 150),
        Band(361.0, 649.0, 151, 200),
        Band(650.0, 1249.0, 201, 300),
        Band(1250.0, 1649.0, 301, 400),
        Band(1650.0, 2049.0, 401, 500),
    )

    val SO2_PPB = listOf(
        Band(0.0, 35.0, 0, 50),
        Band(36.0, 75.0, 51, 100),
        Band(76.0, 185.0, 101, 150),
        Band(186.0, 304.0, 151, 200),
        Band(305.0, 604.0, 201, 300),
        Band(605.0, 804.0, 301, 400),
        Band(805.0, 1004.0, 401, 500),
    )

    val CO_8H_PPM = listOf(
        Band(0.0, 4.4, 0, 50),
        Band(4.5, 9.4, 51, 100),
        Band(9.5, 12.4, 101, 150),
        Band(12.5, 15.4, 151, 200),
        Band(15.5, 30.4, 201, 300),
        Band(30.5, 40.4, 301, 400),
        Band(40.5, 50.4, 401, 500),
    )

    fun truncate1(v: Double): Double = floor(v * 10.0) / 10.0

    fun indexOf(rawConcentration: Double, bands: List<Band>): Int? {
        if (rawConcentration.isNaN() || rawConcentration < 0.0) return null
        val c = truncate1(rawConcentration)
        val band = bands.firstOrNull { c <= it.cHigh } ?: return null
        val span = band.cHigh - band.cLow
        if (span <= 0.0) return band.aLow
        val raw = (band.aHigh - band.aLow) / span * (c - band.cLow) + band.aLow
        return kotlin.math.round(raw).toInt().coerceIn(0, 500)
    }

    fun overall(vararg parts: Int?): Int? = parts.filterNotNull().maxOrNull()

    fun ppbToUgM3(
        ppb: Double,
        molecularWeight: Double,
        tempC: Double = 25.0,
        pressureHPa: Double = 1013.25,
    ): Double {
        val kelvin = tempC + 273.15
        return ppb * molecularWeight / 24.45 * (298.15 / kelvin) * (pressureHPa / 1013.25)
    }

    fun ugM3ToPpb(
        ugM3: Double,
        molecularWeight: Double,
        tempC: Double = 25.0,
        pressureHPa: Double = 1013.25,
    ): Double {
        val kelvin = tempC + 273.15
        return ugM3 * 24.45 / molecularWeight * (kelvin / 298.15) * (1013.25 / pressureHPa)
    }

    fun ugM3ToPpm(ugM3: Double, molecularWeight: Double): Double =
        ugM3ToPpb(ugM3, molecularWeight) / 1000.0
}
