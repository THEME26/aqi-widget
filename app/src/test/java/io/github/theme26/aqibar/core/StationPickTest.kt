package io.github.theme26.aqibar.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StationPickTest {

    private val baseLat = 39.9042
    private val baseLon = 116.4074

    private fun station(uid: Int, name: String, kmNorth: Double, kmEast: Double, value: Int = 100): StationPick.Raw {
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
            station(7, "同名站", 2.0, 0.0),
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
        assertEquals("站1", pts[1].name)
        assertEquals("站8", pts[8].name)
        assertTrue("外圈序号必须等于索引", pts.indices.all { pts[it].index == it })
    }

    @Test
    fun centerFallsBackToNearestStation() {
        val ranked = (1..9).map { station(it, "站$it", it * 2.0, 0.0, 100 + it) }
        val pts = StationPick.assemble("", -1, baseLat, baseLon, ranked)
        assertEquals("站1", pts[0].name)
        assertEquals(101, pts[0].value)
        assertTrue("中心格要写出它离你多远", pts[0].distanceKm > 1.0)
        assertEquals("站9", pts[8].name)
    }

    @Test
    fun failedStationKeepsItsSlot() {
        val ranked = (1..9).map { station(it, "站$it", it * 2.0, 0.0, if (it == 3) -1 else 100 + it) }
        val pts = StationPick.assemble("主站", 88, baseLat, baseLon, ranked)
        assertEquals(-1, pts[3].value)
        assertEquals("站3", pts[3].name)
        assertEquals("站4", pts[4].name)
        assertEquals(104, pts[4].value)
    }

    @Test
    fun missingTailIsPaddedWithMinusOne() {
        val ranked = listOf(station(1, "唯一", 3.0, 0.0))
        val pts = StationPick.assemble("主站", 88, baseLat, baseLon, ranked)
        assertEquals(9, pts.size)
        assertEquals(100, pts[1].value)
        assertEquals(-1, pts[8].value)
        assertEquals(-1, pts[5].value)
    }
}
