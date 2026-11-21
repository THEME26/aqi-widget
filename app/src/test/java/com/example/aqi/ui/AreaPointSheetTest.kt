package com.example.aqi.ui

import android.content.Context
import com.example.aqi.R
import com.example.aqi.core.AreaKind
import com.example.aqi.core.AreaPoint
import com.example.aqi.core.AreaStat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * 点位详情的文案（v0.4.4）。
 *
 * 这里要防止的不是排版，而是「说了不该说的话」：
 *  - 9 个点全是模式格点，绝不能出现「XX 监测站」这种编造；
 *  - 中文界面不能夹英文标签，英文界面不能夹中文；
 *  - 同一数值的点必须给出「大概率同格」的解释，否则用户会以为是数据没刷新。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "zh-rCN-xxhdpi", application = android.app.Application::class)
class AreaPointSheetTest {

    private val zh: Context = RuntimeEnvironment.getApplication()

    private fun area(vals: List<Int>): AreaStat {
        val pts = List(9) { i -> AreaPoint(i, vals[i], 39.9 - i * 0.01, 116.4 + i * 0.01) }
        return AreaStat.of(vals, 25, false, pts, "2026-11-16T07:00")!!
    }

    /** WAQI 站点模式：中心有站名、外围点带真实距离。 */
    private fun stationArea(vals: List<Int>): AreaStat {
        val names = listOf("天安门", "奥体中心", "农展馆", "东四", "天坛", "万寿西宫", "官园", "北部新区", "定陵")
        val pts = List(9) { i ->
            AreaPoint(
                i, vals[i], 39.9 - i * 0.01, 116.4 + i * 0.01,
                name = names[i], distanceKm = if (i == 0) 1.2 else 1.2 + i * 2.0,
            )
        }
        return AreaStat.of(vals, 25, false, pts, "2026-11-16 07:00:00", AreaKind.STATION)!!
    }

    private fun bearingOf(c: Context, index: Int): String =
        c.resources.getStringArray(R.array.bearings)[index - 1]

    @Test
    fun chineseTitleUsesBearingAndRadius() {
        val a = area(listOf(174, 203, 203, 174, 174, 203, 174, 203, 203))
        val t = AreaPointSheet.title(zh, a, a.point(6)!!, bearingOf(zh, 6))
        assertEquals("西南 · 25 公里", t)
    }

    @Test
    fun centerTitleHasNoDistance() {
        val a = area(listOf(174, 203, 203, 174, 174, 203, 174, 203, 203))
        val t = AreaPointSheet.title(zh, a, a.point(0)!!, zh.getString(R.string.dlg_center))
        assertEquals("中心（你）", t)
    }

    @Test
    fun noteSaysItIsNotAStation() {
        val a = area(listOf(174, 174, 174, 174, 174, 174, 174, 174, 174))
        val n = AreaPointSheet.note(zh, a, a.point(3)!!)
        assertTrue("必须明确说明不是监测站，否则用户会去找站名", n.contains("不是监测站"))
        assertTrue(n.contains("WAQI"))
    }

    @Test
    fun sameValueAsCenterExplainsTheModelCell() {
        val a = area(listOf(174, 203, 203, 174, 174, 203, 174, 203, 203))
        // 索引 2 = 东北，值 203 ≠ 中心 174 → 不加同格说明
        assertFalse(AreaPointSheet.note(zh, a, a.point(2)!!).contains("同一个模式格"))
        // 索引 3 = 东，值 174 == 中心 → 必须解释
        assertTrue(AreaPointSheet.note(zh, a, a.point(3)!!).contains("同一个模式格"))
    }

    @Test
    fun centerNeverGetsTheSameCellNote() {
        val a = area(listOf(174, 174, 174, 174, 174, 174, 174, 174, 174))
        assertFalse(AreaPointSheet.note(zh, a, a.point(0)!!).contains("同一个模式格"))
    }

    // ───────── v0.4.5 WAQI 站点模式 ─────────

    /**
     * 站点模式外围点：标题必须是「站名 · 距离 · 第几近」。
     *
     * v0.4.7 起格子位置是**距离排名**，不再是方位。标题里不写排名的话，
     * 用户没有任何线索知道「这一格为什么排在这里」—— 方位那套解释已经不成立了。
     */
    @Test
    fun stationOuterTitleShowsNameDistanceAndRank() {
        val a = stationArea(listOf(120, 130, 140, 150, 160, 170, 180, 190, 200))
        val t = AreaPointSheet.title(zh, a, a.point(3)!!, "东")
        assertEquals("东四 · 7.2 公里 · 第 4 近", t)
    }

    /** 站点模式中心：有距离就写「站名 · 距离」，没有就只写站名。 */
    @Test
    fun stationCenterTitleIsJustTheName() {
        val a = stationArea(listOf(120, 130, 140, 150, 160, 170, 180, 190, 200))
        val t = AreaPointSheet.title(zh, a, a.point(0)!!, zh.getString(R.string.dlg_center))
        assertEquals("天安门 · 1.2 公里", t)

        // 主链路站点没有距离（feed 不回传），这时不能写「0.0 公里」
        val noDist = AreaStat.of(
            listOf(120, 130, 140, 150, 160, 170, 180, 190, 200), 0, false,
            List(9) { i -> AreaPoint(i, 120 + i, 39.9, 116.4, name = "天安门", distanceKm = 0.0) },
            "2026-11-16 07:00:00", AreaKind.STATION,
        )!!
        assertEquals(
            "天安门",
            AreaPointSheet.title(zh, noDist, noDist.point(0)!!, zh.getString(R.string.dlg_center)),
        )
    }

    /** 站点模式说明的第一句必须交代「位置 = 距离排名」，否则用户会当方位看。 */
    @Test
    fun stationNoteExplainsRankingFirst() {
        val a = stationArea(listOf(120, 130, 140, 150, 160, 170, 180, 190, 200))
        val n = AreaPointSheet.note(zh, a, a.point(3)!!)
        assertTrue("必须先说位置含义，实际：$n", n.startsWith("九宫格按距离排"))
        assertTrue(n.contains("不表示方向"))
    }

    /** 站点模式的说明必须写清「不是站点自报值」，否则用户拿 aqicn 一对就以为算错了。 */
    @Test
    fun stationNoteExplainsRecalculation() {
        val a = stationArea(listOf(120, 120, 140, 150, 160, 170, 180, 190, 200))
        val n = AreaPointSheet.note(zh, a, a.point(2)!!)
        assertTrue(n.contains("2024"))
        assertTrue("必须说明与站点自报值不同", n.contains("不是站点自报值"))
        assertFalse("站点模式不该再说「不是监测站」", n.contains("这不是监测站"))
    }

    /** 站点模式下同值解释不能复用「同一个模式格」—— 那是 OM 的说法。 */
    @Test
    fun stationSameValueUsesStationWording() {
        val a = stationArea(listOf(120, 120, 140, 150, 160, 170, 180, 190, 200))
        val n = AreaPointSheet.note(zh, a, a.point(1)!!)
        assertTrue(n.contains("相邻站点"))
        assertFalse(n.contains("同一个模式格"))
    }

    /** 时间格式两种都要能出时分：OM 用 T 分隔，WAQI 用空格分隔。 */
    @Test
    fun bothTimeFormatsYieldHhmm() {
        assertEquals("07:00", area(listOf(1, 2, 3, 4, 5, 6, 7, 8, 9)).observedHhmm)
        val station = stationArea(listOf(120, 130, 140, 150, 160, 170, 180, 190, 200))
        assertEquals("07:00", station.observedHhmm)
    }

    @Test
    @Config(qualifiers = "en-rUS-xxhdpi", sdk = [34], application = android.app.Application::class)
    fun englishHasNoChinese() {
        val ctx = RuntimeEnvironment.getApplication()
        val a = area(listOf(174, 203, 203, 174, 174, 203, 174, 203, 203))
        val t = AreaPointSheet.title(ctx, a, a.point(6)!!, bearingOf(ctx, 6))
        assertEquals("Southwest · 25 km", t)
        val n = AreaPointSheet.note(ctx, a, a.point(3)!!)
        assertTrue(n.contains("not a monitoring station"))
        assertFalse("英文说明里混进了中文", n.any { it.code in 0x4E00..0x9FFF })

        // 站点模式也不能夹中文（站名除外，站名本来就可能是中文）
        val st = stationArea(listOf(120, 130, 140, 150, 160, 170, 180, 190, 200))
        val sn = AreaPointSheet.note(ctx, st, st.point(3)!!)
        assertTrue(sn.contains("2024"))
    }
}
