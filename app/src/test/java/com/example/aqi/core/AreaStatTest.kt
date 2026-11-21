package com.example.aqi.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 区域统计口径（v0.4.2）。
 *
 * 这里验证的是「同一组 9 个点，中位数 / 最大值 / 最小值分别是多少」——
 * 用户问「是不是用最大值更科学」之后，两种口径必须能被固定输入验证，
 * 而不是靠每次请求碰运气。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class AreaStatTest {

    /** 实测自北京 25 km 一档的一组真实数值，含一个明显偏低的点。 */
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
        assertEquals(219, a.median)   // 中位数不跟着变，图例要同时显示三个数
        assertEquals(273, a.max)
        assertTrue(a.worst)
    }

    /** 偶数个点：中位数取中间两个的均值（四舍五入），不能退化成取其中一个。 */
    @Test
    fun medianOfEvenCount() {
        val a = AreaStat.of(listOf(10, 40, 30, 20, -1, -1, -1, -1, -1), 10, useWorst = false)!!
        assertEquals(25, a.median)
        assertEquals(4, a.samples)
    }

    /** 少于 3 个有效点：整个区域值都不展示（2 个点的「中位数」没有统计意义）。 */
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
        // 缺失点保留 -1，渲染端画「–」，绝不能变成 0 号色（优）
        assertEquals(-1, a.grid[1])
    }

    /** 全部相等（3 km 档的真实情况：9 点落在同一个 45 km 模式格里）。 */
    @Test
    fun identicalPointsMakeBothModesAgree() {
        val flat = List(9) { 111 }
        assertEquals(111, AreaStat.of(flat, 10, false)!!.value)
        assertEquals(111, AreaStat.of(flat, 10, true)!!.value)
    }

    @Test
    fun levelFollowsTheChosenValue() {
        // 中位 121（三级）、最高 168（四级）：切口径会换档，
        // 这正是「最差」值得单独提供一个开关、而不是替换掉中位数的理由。
        val cross = listOf(131, 117, 138, 121, 133, 168, 90, 95, 120)
        val med = AreaStat.of(cross, 25, useWorst = false)!!
        val worst = AreaStat.of(cross, 25, useWorst = true)!!
        assertEquals(121, med.value)
        assertEquals(AqiLevel.of(121), med.level)
        assertEquals(168, worst.value)
        assertEquals(AqiLevel.of(168), worst.level)
        assertTrue("这组数据必须跨档，否则这个测试证明不了任何事", med.level != worst.level)
    }

    /** v0.4.4：坐标与模式场时间必须原样带进 AreaStat，否则点位详情没有任何可显示的东西。 */
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

    /** 旧调用方（没有坐标）仍然能算出统计量，只是 points 为空、点击不响应。 */
    @Test
    fun statisticsWorkWithoutCoordinates() {
        val a = AreaStat.of(real, 25, true)!!
        assertTrue(a.points.isEmpty())
        assertEquals("", a.observedText)
        assertNull(a.point(3))
    }
}
