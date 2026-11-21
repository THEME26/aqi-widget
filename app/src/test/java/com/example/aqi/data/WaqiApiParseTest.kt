package com.example.aqi.data

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

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
        assertTrue("should carry a value", s.value > 0)
    }

    @Test
    fun recomputesInsteadOfUsingSelfReportedAqi() {
        val s = WaqiApi.parseRecalc(fixture())
        assertEquals(85, s.diag!!.reportedAqi)
        assertNotEquals(85, s.value)
        assertEquals(87, s.value)
    }

    @Test
    fun keepsPollutantSubIndicesWithDisplayLabels() {
        val s = WaqiApi.parseRecalc(fixture())
        assertTrue("PM2.5 必须在", s.subIndices.containsKey("PM2.5"))
        assertTrue("O₃ 标签要统一", s.subIndices.containsKey("O₃"))
        assertEquals(6, s.subIndices.size)
    }

    @Test
    fun carriesStationTimeAndAttribution() {
        val s = WaqiApi.parseRecalc(fixture())
        assertTrue("时间要原样带出：${s.observedText}", s.observedText.startsWith("2026-11-16"))
        assertTrue("要署名机构", s.attribution.contains("World Air Quality Index Project"))
    }

    // ───────── map/bounds（v0.4.7） ─────────

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
        assertEquals(2, list.size)
        assertEquals(7536, list[0].uid)
        assertEquals("Brooklyn, New York, USA", list[0].name)
        assertEquals(45, list[0].value)
        assertEquals(40.6943, list[0].lat, 1e-9)
        assertEquals(61, list[1].value)
    }

    @Test
    fun boundsErrorYieldsEmptyList() {
        val list = WaqiApi.parseBounds(JSONObject("""{"status":"error","data":"Invalid key"}"""))
        assertTrue(list.isEmpty())
    }

    @Test
    fun cityNameForSearchFallback() {
        assertEquals("Beijing", WaqiApi.cityOf("海淀四季青, Beijing"))
        assertEquals("California", WaqiApi.cityOf("Los Angeles – N. Main Street, Los Angeles, California, USA"))
        assertEquals("东京", WaqiApi.cityOf("东京"))
        assertEquals("", WaqiApi.cityOf(""))
    }
}
