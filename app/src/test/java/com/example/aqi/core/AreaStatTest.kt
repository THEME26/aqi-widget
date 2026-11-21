package com.example.aqi.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class AreaStatTest {

    private val real = listOf(219, 273, 194, 194, 249, 273, 225, 215, 215)

    @Test
    fun medianIsTheDefault() {
        val a = AreaStat.of(real, 25, useWorst = false)!!
        assertEquals(219, a.value)
        assertEquals(219, a.median)
        assertEquals(194, a.min)
        assertEquals(273, a.max)
        assertEquals(false, a.worst)
        assertEquals(9, a.samples)
    }

    @Test
    fun worstTakesTheMaximum() {
        val a = AreaStat.of(real, 25, useWorst = true)!!
        assertEquals(273, a.value)
        assertEquals(219, a.median)
        assertEquals(273, a.max)
        assertTrue(a.worst)
    }

    @Test
    fun medianOfEvenCount() {
        val a = AreaStat.of(listOf(10, 40, 30, 20, -1, -1, -1, -1, -1), 10, useWorst = false)!!
        assertEquals(25, a.median)
        assertEquals(4, a.samples)
    }

    @Test
    fun tooFewSamplesIsNull() {
        assertNull(AreaStat.of(listOf(10, 40, -1, -1, -1, -1, -1, -1, -1), 10, false))
    }

    @Test
    fun missingPointsDoNotBecomeZero() {
        val a = AreaStat.of(listOf(80, -1, 90, -1, 70, -1, -1, -1, -1), 10, false)!!
        assertEquals(3, a.samples)
        assertEquals(80, a.median)
        assertEquals(70, a.min)
        assertEquals(90, a.max)
        assertEquals(-1, a.grid[1])
    }

    @Test
    fun identicalPointsMakeBothModesAgree() {
        val flat = List(9) { 111 }
        assertEquals(111, AreaStat.of(flat, 10, false)!!.value)
        assertEquals(111, AreaStat.of(flat, 10, true)!!.value)
    }

    @Test
    fun levelFollowsTheChosenValue() {
        val cross = listOf(131, 117, 138, 121, 133, 168, 90, 95, 120)
        val med = AreaStat.of(cross, 25, useWorst = false)!!
        val worst = AreaStat.of(cross, 25, useWorst = true)!!
        assertEquals(121, med.value)
        assertEquals(AqiLevel.of(121), med.level)
        assertEquals(168, worst.value)
        assertEquals(AqiLevel.of(168), worst.level)
        assertTrue("这组数据必须跨档，否则这个测试证明不了任何事", med.level != worst.level)
    }

    @Test
    fun pointsAndTimeSurviveTheStatistics() {
        val pts = List(9) { i ->
            AreaPoint(index = i, value = real[i], lat = 39.9 - i * 0.01, lon = 116.4 + i * 0.01)
        }
        val a = AreaStat.of(real, 25, false, pts, "2026-11-16T07:00")!!
        assertEquals(9, a.points.size)
        assertEquals(0, a.point(0)!!.index)
        assertTrue(a.point(0)!!.isCenter)
        assertEquals(39.9 - 8 * 0.01, a.point(8)!!.lat, 1e-9)
        assertEquals("2026-11-16T07:00", a.observedText)
    }

    @Test
    fun statisticsWorkWithoutCoordinates() {
        val a = AreaStat.of(real, 25, true)!!
        assertTrue(a.points.isEmpty())
        assertEquals("", a.observedText)
        assertNull(a.point(3))
    }
}
