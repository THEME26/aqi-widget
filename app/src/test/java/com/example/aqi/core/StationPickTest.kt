package com.example.aqi.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 选站规则的单测（v0.4.7）。
 *
 * 这张九宫格现在只有一个语义：**位置 = 距离排名**。所以这里要钉死的不是排版，
 * 而是「第几格放第几近的站」——写错一格，图上看起来一样整齐，但说的是假话。
 *
 * 另外两条容易回归的规则：
 *  - **不限半径**：200 公里外的站只要排进前 9 就该出现（用户明确要求的）；
 *  - **失败站占位不塌陷**：第 3 近的站取不到数据时，第 4 近不能顶到第 3 格去。
 */
class StationPickTest {

    /** 北京天安门附近，按「北 1 km / 东 2 km …」放几个站，便于手算距离。 */
    private val baseLat = 39.9042
    private val baseLon = 116.4074

    private fun station(uid: Int, name: String, kmNorth: Double, kmEast: Double, value: Int = 100): StationPick.Raw {
        // 1° 纬度 ≈ 111.2 km；此处经度按 39.9°N 折算 ≈ 85.3 km/°
        return StationPick.Raw(
            uid = uid,
            name = name,
            lat = baseLat + kmNorth / 111.2,
            lon = baseLon + kmEast / 85.3,
            value = value,
        )
    }

    // ───────── nearest ─────────

    @Test
    fun sortsByDistanceAndTakesLimit() {
        val list = listOf(
            station(1, "远", 8.0, 0.0),
            station(2, "近", 2.0, 0.0),
            station(3, "中", 5.0, 0.0),
        )
        val got = StationPick.nearest(list, baseLat, baseLon, "", 2)
        assertEquals(listOf("近", "中"), got.map { it.name })
    }

    /** 用户明确要求：不要距离限制。200 公里外的站只要够近（这里是第 2 近）就得在。 */
    @Test
    fun noRadiusLimit() {
        val list = listOf(
            station(1, "城里", 3.0, 0.0),
            station(2, "两百公里外", 200.0, 0.0),
        )
        val got = StationPick.nearest(list, baseLat, baseLon, "", 9)
        assertEquals(2, got.size)
        assertEquals("两百公里外", got[1].name)
    }

    @Test
    fun dedupesByUid() {
        val list = listOf(
            station(7, "同名站", 2.0, 0.0),
            station(7, "同名站", 2.0, 0.0),   // 同一站被两条检索结果各回一次
            station(8, "另一个", 4.0, 0.0),
        )
        val got = StationPick.nearest(list, baseLat, baseLon, "", 9)
        assertEquals(2, got.size)
        assertEquals(listOf("同名站", "另一个"), got.map { it.name })
    }

    @Test
    fun excludesCenterStationByName() {
        val list = listOf(
            station(1, "海淀四季青", 0.4, 0.0),
            station(2, "万寿西宫", 3.0, 0.0),
        )
        val got = StationPick.nearest(list, baseLat, baseLon, "海淀四季青", 9)
        // 中心站已经在中心格了，外圈不能再出现一次
        assertEquals(listOf("万寿西宫"), got.map { it.name })
    }

    @Test
    fun dropsNaNCoordinates() {
        val list = listOf(
            StationPick.Raw(1, "没坐标", Double.NaN, Double.NaN, 90),
            station(2, "有坐标", 3.0, 0.0),
        )
        assertEquals(listOf("有坐标"), StationPick.nearest(list, baseLat, baseLon, "", 9).map { it.name })
    }

    /** 距离相同时按 uid 排：没有这条，两次刷新的顺序可能不同，格子会自己跳。 */
    @Test
    fun tiesAreBrokenDeterministically() {
        val list = listOf(
            station(9, "乙", 3.0, 0.0),
            station(4, "甲", 3.0, 0.0),
        )
        val a = StationPick.nearest(list, baseLat, baseLon, "", 9).map { it.uid }
        val b = StationPick.nearest(list.reversed(), baseLat, baseLon, "", 9).map { it.uid }
        assertEquals(listOf(4, 9), a)
        assertEquals(a, b)
    }

    // ───────── assemble ─────────

    @Test
    fun centerTakesSlotZeroWhenValueKnown() {
        val ranked = (1..8).map { station(it, "站$it", it * 2.0, 0.0, 100 + it) }
        val pts = StationPick.assemble("主站", 88, baseLat, baseLon, ranked)
        assertEquals(9, pts.size)
        assertEquals(88, pts[0].value)
        assertEquals("主站", pts[0].name)
        assertEquals(0.0, pts[0].distanceKm, 1e-9)
        // 外圈第 1 格是第 2 近，不是最近的站
        assertEquals("站1", pts[1].name)
        assertEquals("站8", pts[8].name)
        assertTrue("外圈序号必须等于索引", pts.indices.all { pts[it].index == it })
    }

    /** 周边源 ≠ 主源：中心格由最近的站充当，距离是它自己的真实距离。 */
    @Test
    fun centerFallsBackToNearestStation() {
        val ranked = (1..9).map { station(it, "站$it", it * 2.0, 0.0, 100 + it) }
        val pts = StationPick.assemble("", -1, baseLat, baseLon, ranked)
        assertEquals("站1", pts[0].name)
        assertEquals(101, pts[0].value)
        assertTrue("中心格要写出它离你多远", pts[0].distanceKm > 1.0)
        assertEquals("站9", pts[8].name)
    }

    /**
     * 取不到值的站**占住自己的格子**画「–」，后面的不往前挤。
     * 否则「第 3 格」显示的会是第 4 近的站，而格子位置就是它的全部含义。
     */
    @Test
    fun failedStationKeepsItsSlot() {
        val ranked = (1..9).map { station(it, "站$it", it * 2.0, 0.0, if (it == 3) -1 else 100 + it) }
        val pts = StationPick.assemble("主站", 88, baseLat, baseLon, ranked)
        assertEquals(-1, pts[3].value)
        assertEquals("站3", pts[3].name)
        assertEquals("站4", pts[4].name)   // 没有挤到第 3 格去
        assertEquals(104, pts[4].value)
    }

    @Test
    fun missingTailIsPaddedWithMinusOne() {
        val ranked = listOf(station(1, "唯一", 3.0, 0.0))
        val pts = StationPick.assemble("主站", 88, baseLat, baseLon, ranked)
        assertEquals(9, pts.size)
        assertEquals(100, pts[1].value)   // 缺省 value = 100
        assertEquals(-1, pts[8].value)
        assertEquals(-1, pts[5].value)
    }
}
