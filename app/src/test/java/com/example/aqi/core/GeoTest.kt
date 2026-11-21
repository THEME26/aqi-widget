package com.example.aqi.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GeoTest {

    @Test
    fun dueNorthIsZero() {
        val b = Geo.bearingDeg(39.9042, 116.4074, 40.0042, 116.4074)
        assertEquals(0.0, b, 0.5)
    }

    @Test
    fun dueEastIsNinety() {
        val b = Geo.bearingDeg(39.9042, 116.4074, 39.9042, 116.5274)
        assertEquals(90.0, b, 0.6)
    }

    @Test
    fun dueSouthIsOneEighty() {
        val b = Geo.bearingDeg(39.9042, 116.4074, 39.8042, 116.4074)
        assertEquals(180.0, b, 0.5)
    }

    @Test
    fun dueWestIsTwoSeventy() {
        val b = Geo.bearingDeg(39.9042, 116.4074, 39.9042, 116.2874)
        assertEquals(270.0, b, 0.6)
    }

    @Test
    fun oneDegreeLatitudeIsAbout111km() {
        val d = Geo.distanceKm(39.9042, 116.4074, 40.9042, 116.4074)
        assertTrue("实际 $d", d in 110.0..112.5)
    }

    @Test
    fun samePointIsZero() {
        assertEquals(0.0, Geo.distanceKm(39.9, 116.4, 39.9, 116.4), 1e-9)
        val b = Geo.bearingDeg(39.9, 116.4, 39.9, 116.4)
        assertTrue("同点方位不应是 NaN", !b.isNaN())
    }

    @Test
    fun sectorBoundariesAreRoundToNearest() {
        assertEquals(1, Geo.sectorIndex(0.0))
        assertEquals(1, Geo.sectorIndex(20.0))
        assertEquals(1, Geo.sectorIndex(337.5))
        assertEquals(2, Geo.sectorIndex(23.0))
        assertEquals(2, Geo.sectorIndex(45.0))
        assertEquals(3, Geo.sectorIndex(90.0))
        assertEquals(5, Geo.sectorIndex(180.0))
        assertEquals(8, Geo.sectorIndex(315.0))
        assertEquals(1, Geo.sectorIndex(359.9))
    }

    @Test
    fun destinationNorthRaisesLatitude() {
        val (la, lo) = Geo.destination(39.9042, 116.4074, 0.0, 111.2)
        assertTrue("纬度实际 $la", la in 40.85..40.95)
        assertEquals(116.4074, lo, 0.02)
    }

    @Test
    fun destinationEastIncreasesLongitude() {
        val (la, lo) = Geo.destination(39.9042, 116.4074, 90.0, 25.0)
        assertEquals(39.9042, la, 0.05)
        assertTrue("经度应该东移，实际 $lo", lo > 116.4074)
        assertTrue("经度差实际 ${lo - 116.4074}", lo - 116.4074 in 0.25..0.34)
    }

    @Test
    fun destinationZeroDistanceIsIdentity() {
        val (la, lo) = Geo.destination(39.9042, 116.4074, 137.0, 0.0)
        assertEquals(39.9042, la, 1e-9)
        assertEquals(116.4074, lo, 1e-9)
    }

    @Test
    fun destinationAndDistanceAgree() {
        val (la, lo) = Geo.destination(39.9042, 116.4074, 225.0, 18.0)
        val d = Geo.distanceKm(39.9042, 116.4074, la, lo)
        assertTrue("回量距离 $d 应接近 18", kotlin.math.abs(d - 18.0) < 0.05)
        assertEquals(6, Geo.sectorIndex(Geo.bearingDeg(39.9042, 116.4074, la, lo)))
    }
}
