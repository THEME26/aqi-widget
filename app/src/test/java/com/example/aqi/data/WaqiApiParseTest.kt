package com.example.aqi.data

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * `feed` 响应解析（v0.4.6）。
 *
 * 用的是**真实抓下来的响应体**：`app/src/test/resources/waqi_feed_demo.json` 是
 * 2026-11-16 从 `https://api.waqi.info/feed/geo:40.04;116.30/?token=demo` 原样保存的
 * （demo Token 对所有 feed 端点固定返回上海这一个站）。这样测的是「WAQI 真实长什么样」，
 * 而不是「我以为它长什么样」—— 上一版就是栽在后者上（`map/bounds` 对免费 Token 直接报错）。
 *
 * 走 Robolectric 是因为 `org.json` 在纯 JVM 单测里是「未实现的桩」，`optString` 会直接抛
 * `Method ... not mocked`。这不是绕路：真机上跑的就是 Android 的 org.json 实现。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class WaqiApiParseTest {

    private fun fixture(): JSONObject {
        val text = javaClass.classLoader!!
            .getResourceAsStream("waqi_feed_demo.json")!!
            .bufferedReader().use { it.readText() }
        return JSONObject(text)
    }

    @Test
    fun parsesStationIdentityFromFeed() {
        val s = WaqiApi.parseRecalc(fixture())
        assertEquals("Shanghai (上海)", s.stationName)
        // 站名 + 坐标都要能带出来，九宫格靠 id/坐标判重与算距离
        assertTrue("should carry a value", s.value > 0)
    }

    @Test
    fun recomputesInsteadOfUsingSelfReportedAqi() {
        val s = WaqiApi.parseRecalc(fixture())
        // 夹具里站点自报 aqi = 85（2012 断点 + 瞬时值）
        assertEquals(85, s.diag!!.reportedAqi)
        // 按 2024 断点重算后不是 85 —— 这条断言的意义就是「确实重算了」，
        // 如果哪天有人把重算换成直读 aqi，这个数字会立刻变回 85，测试当场变红。
        assertNotEquals(85, s.value)
        assertEquals(87, s.value)
    }

    @Test
    fun keepsPollutantSubIndicesWithDisplayLabels() {
        val s = WaqiApi.parseRecalc(fixture())
        assertTrue("PM2.5 必须在", s.subIndices.containsKey("PM2.5"))
        assertTrue("O₃ 标签要统一", s.subIndices.containsKey("O₃"))
        // iaqi 里的气象字段（t/h/w/p）不能混进污染物：夹具里 10 个 key，只有 6 个是污染物
        assertEquals(6, s.subIndices.size)
    }

    @Test
    fun carriesStationTimeAndAttribution() {
        val s = WaqiApi.parseRecalc(fixture())
        assertTrue("时间要原样带出：${s.observedText}", s.observedText.startsWith("2026-11-16"))
        assertTrue("要署名机构", s.attribution.contains("World Air Quality Index Project"))
    }

    // ───────── map/bounds（v0.4.7） ─────────

    /**
     * `map/bounds` 的响应体按官方 OpenAPI 的示例写成：
     * `{"status":"ok","data":[{"lat":..,"lon":..,"uid":..,"aqi":"45",
     *   "station":{"name":..,"time":..}}]}`
     *
     * 这是**唯一没法实测的一环**：demo Token 对 map 系列一律 `Invalid key`
     * （`feed` 系列放行，见 18.2 的实测表）。所以这里钉的是「按 spec 解析不会崩、
     * 该扔的扔」；真机如果字段对不上，会走 `search` 兜底，并在卡片上写明。
     */
    @Test
    fun parsesBoundsAccordingToSpec() {
        val json = """
        {"status":"ok","data":[
          {"lat":40.6943,"lon":-73.9249,"uid":7536,"aqi":"45",
           "station":{"name":"Brooklyn, New York, USA","time":"2024-01-15T12:00:00-05:00"}},
          {"lat":40.7000,"lon":-73.9000,"uid":7537,"aqi":"-",
           "station":{"name":"Offline station","time":"2024-01-15T12:00:00-05:00"}},
          {"lat":40.7100,"lon":-73.9100,"uid":7538,"aqi":61,
           "station":{"name":"Numeric aqi","time":"2024-01-15T12:00:00-05:00"}}
        ]}
        """.trimIndent()
        val list = WaqiApi.parseBounds(JSONObject(json))
        // aqi 为 "-" 的站直接丢：它没有读数，占着候选位只会让格子少一个有用的站
        assertEquals(2, list.size)
        assertEquals(7536, list[0].uid)
        assertEquals("Brooklyn, New York, USA", list[0].name)
        assertEquals(45, list[0].value)
        assertEquals(40.6943, list[0].lat, 1e-9)
        // aqi 是数字而不是字符串时也要认（spec 写的是 string，但别的地方出现过 number）
        assertEquals(61, list[1].value)
    }

    @Test
    fun boundsErrorYieldsEmptyList() {
        val list = WaqiApi.parseBounds(JSONObject("""{"status":"error","data":"Invalid key"}"""))
        assertTrue(list.isEmpty())
    }

    /**
     * 城市名取自站名里最后一个「不是国家代码」的逗号段。
     * 取错会让 `search` 兜底去搜一个不存在的城市，然后静默返回 0 条。
     */
    @Test
    fun cityNameForSearchFallback() {
        assertEquals("Beijing", WaqiApi.cityOf("海淀四季青, Beijing"))
        assertEquals("California", WaqiApi.cityOf("Los Angeles – N. Main Street, Los Angeles, California, USA"))
        assertEquals("东京", WaqiApi.cityOf("东京"))
        assertEquals("", WaqiApi.cityOf(""))
    }
}
