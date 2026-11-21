package com.example.aqi.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 球面几何（v0.4.5）。
 *
 * 九宫格靠「方位角」决定一个站放在哪一格。这个映射错了不会崩、也不会报警，
 * 只会让用户点「东北」看到西北的站名 —— 所以必须用已知坐标验证。
 */
class GeoTest {

    /** 天安门 → 正北约 11 km 处（纬度 +0.1°），方位应为 0。 */
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

    /** 1 纬度 ≈ 111.2 km。用固定参照点验证，避免把所有距离都算成 0 或不合理值。 */
    @Test
    fun oneDegreeLatitudeIsAbout111km() {
        val d = Geo.distanceKm(39.9042, 116.4074, 40.9042, 116.4074)
        assertTrue("实际 $d", d in 110.0..112.5)
    }

    /** 同一点距离必须严格为 0，方位退化时不能返回 NaN。 */
    @Test
    fun samePointIsZero() {
        assertEquals(0.0, Geo.distanceKm(39.9, 116.4, 39.9, 116.4), 1e-9)
        val b = Geo.bearingDeg(39.9, 116.4, 39.9, 116.4)
        assertTrue("同点方位不应是 NaN", !b.isNaN())
    }

    /** 扇区：以正方位为中心 ±22.5°。正北偏东 20° 仍是「北」，偏 25° 才是「东北」。 */
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

    /** 正北走 111 km ≈ 纬度 +1°。九宫格的 8 个探针点全靠这个公式落位。 */
    @Test
    fun destinationNorthRaisesLatitude() {
        val (la, lo) = Geo.destination(39.9042, 116.4074, 0.0, 111.2)
        assertTrue("纬度实际 $la", la in 40.85..40.95)
        assertEquals(116.4074, lo, 0.02)
    }

    /** 正东走一段：纬度不动，经度增加；高纬处同样的公里数对应更大的经度差。 */
    @Test
    fun destinationEastIncreasesLongitude() {
        val (la, lo) = Geo.destination(39.9042, 116.4074, 90.0, 25.0)
        assertEquals(39.9042, la, 0.05)
        assertTrue("经度应该东移，实际 $lo", lo > 116.4074)
        // 39.9°N 处 1° 经度 ≈ 85 km，所以 25 km 约 0.29°
        assertTrue("经度差实际 ${lo - 116.4074}", lo - 116.4074 in 0.25..0.34)
    }

    /** 距离为 0 时终点就是原点；方位退化也不能返回 NaN。 */
    @Test
    fun destinationZeroDistanceIsIdentity() {
        val (la, lo) = Geo.destination(39.9042, 116.4074, 137.0, 0.0)
        assertEquals(39.9042, la, 1e-9)
        assertEquals(116.4074, lo, 1e-9)
    }

    /** destination 与 distanceKm 必须自洽：走出去再量回来，距离要一样。 */
    @Test
    fun destinationAndDistanceAgree() {
        val (la, lo) = Geo.destination(39.9042, 116.4074, 225.0, 18.0)
        val d = Geo.distanceKm(39.9042, 116.4074, la, lo)
        assertTrue("回量距离 $d 应接近 18", kotlin.math.abs(d - 18.0) < 0.05)
        // 而且方位要回到西南：225° → 索引 6（1=北，顺时针）
        assertEquals(6, Geo.sectorIndex(Geo.bearingDeg(39.9042, 116.4074, la, lo)))
    }
}
