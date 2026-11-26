package io.github.theme26.aqibar.data

import io.github.theme26.aqibar.core.AqiLevel
import io.github.theme26.aqibar.core.AqiSource
import io.github.theme26.aqibar.core.AqiStandard
import io.github.theme26.aqibar.core.AqiState
import io.github.theme26.aqibar.core.AreaKind
import io.github.theme26.aqibar.core.StationPick
import io.github.theme26.aqibar.core.AreaStat
import io.github.theme26.aqibar.core.DiagInfo
import io.github.theme26.aqibar.core.Freshness
import io.github.theme26.aqibar.core.WaqiRecalc
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale

object WaqiApi {

    class ApiException(message: String) : IOException(message)

    private const val ENDPOINT = "https://api.waqi.info/feed/geo:%s;%s/?token=%s"

    data class AreaResult(val area: AreaStat?, val reason: String = "")

    fun fetchArea(
        lat: Double,
        lon: Double,
        token: String,
        centerValue: Int,
        centerName: String = "",
        useWorst: Boolean = false,
        centerDistanceKm: Double = 0.0,
    ): AreaResult {
        if (token.isBlank()) return AreaResult(null, "no token")

        var firstError = ""
        var viaSearch = false
        val deadline = System.currentTimeMillis() + AREA_BUDGET_MS

        val (bounded, bErr) = boundsStations(lat, lon, token, deadline)
        if (bErr.isNotBlank()) firstError = bErr
        var raw = bounded
        if (raw.size < StationPick.CELLS) {
            val (found, sErr) = searchStations(token, centerName)
            if (sErr.isNotBlank() && firstError.isBlank()) firstError = sErr
            if (found.isNotEmpty()) {
                raw = mergeByUid(raw, found)
                viaSearch = bounded.isEmpty()
            }
        }
        if (raw.isEmpty()) {
            return AreaResult(null, firstError.ifBlank { "no stations nearby" })
        }

        val need = if (centerValue >= 0) StationPick.CELLS - 1 else StationPick.CELLS
        val ranked = StationPick.nearest(raw, lat, lon, centerName, need)

        val recalculated = ArrayList<StationPick.Raw>(ranked.size)
        var observed = ""
        for (c in ranked) {
            if (System.currentTimeMillis() > deadline) {
                if (firstError.isBlank()) firstError = "timeout after ${AREA_BUDGET_MS / 1000}s"
                break
            }
            sleep(FEED_GAP_MS)
            val r = recalcByUid(c.uid, token)
            if (r.value < 0) {
                if (firstError.isBlank()) firstError = r.error
                recalculated += c.copy(value = -1)
                continue
            }
            if (observed.isBlank()) observed = r.observed
            recalculated += c.copy(value = r.value)
        }

        val pts = StationPick.assemble(
            centerName = centerName,
            centerValue = centerValue,
            lat = lat,
            lon = lon,
            ranked = recalculated,
            centerDistanceKm = centerDistanceKm,
        )
        val values = pts.map { it.value }
        val usable = values.count { it >= 0 }
        if (usable < AreaStat.MIN_SAMPLES) {
            return AreaResult(
                null,
                firstError.ifBlank { "only $usable usable cells (need ${AreaStat.MIN_SAMPLES})" },
            )
        }
        val area = AreaStat.of(
            values = values,
            radiusKm = 0,
            useWorst = useWorst,
            points = pts,
            observedText = observed,
            kind = AreaKind.STATION,
            noteCode = if (viaSearch) AreaStat.NOTE_VIA_SEARCH else "",
            noteDetail = if (viaSearch) "search" else "",
        ) ?: return AreaResult(null, "only $usable usable cells (need ${AreaStat.MIN_SAMPLES})")
        return AreaResult(area)
    }

    private fun boundsStations(
        lat: Double,
        lon: Double,
        token: String,
        deadline: Long,
    ): Pair<List<StationPick.Raw>, String> {
        var err = ""
        var best = emptyList<StationPick.Raw>()
        for (half in BOX_HALF_DEG) {
            if (System.currentTimeMillis() > deadline) {
                return best to err.ifBlank { "timeout after ${AREA_BUDGET_MS / 1000}s" }
            }
            val cos = Math.cos(Math.toRadians(lat)).coerceAtLeast(0.05)
            val dLon = (half / cos).coerceAtMost(170.0)
            val url = MAP_BOUNDS.format(
                Locale.US,
                fmt(lat + half), fmt(lon - dLon), fmt(lat - half), fmt(lon + dLon),
                URLEncoder.encode(token, "UTF-8"),
            )
            try {
                val j = JSONObject(get(url, 5_000, 8_000))
                if (j.optString("status") != "ok") throw ApiException("bounds: ${j.opt("data")}")
                val list = parseBounds(j)
                if (list.size > best.size) best = list
                if (best.size >= StationPick.CELLS) return best to ""
            } catch (e: ApiException) {
                return best to e.message.orEmpty()
            } catch (e: Exception) {
                return best to "${e.javaClass.simpleName}: ${e.message.orEmpty().take(60)}"
            }
        }
        return best to err
    }

    private fun searchStations(
        token: String,
        centerName: String,
    ): Pair<List<StationPick.Raw>, String> {
        val keyword = cityOf(centerName).ifBlank { centerName.trim() }
        if (keyword.isBlank()) return emptyList<StationPick.Raw>() to "search: no city name"
        val body = try {
            val url = SEARCH.format(
                Locale.US,
                URLEncoder.encode(keyword, "UTF-8"),
                URLEncoder.encode(token, "UTF-8"),
            )
            get(url, 5_000, 8_000)
        } catch (e: ApiException) {
            return emptyList<StationPick.Raw>() to e.message.orEmpty()
        } catch (e: Exception) {
            return emptyList<StationPick.Raw>() to
                "${e.javaClass.simpleName}: ${e.message.orEmpty().take(60)}"
        }
        return try {
            val j = JSONObject(body)
            if (j.optString("status") != "ok") throw ApiException("search: ${j.opt("data")}")
            val arr = j.optJSONArray("data")
                ?: return emptyList<StationPick.Raw>() to "search: no data"
            val out = ArrayList<StationPick.Raw>(arr.length())
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val st = o.optJSONObject("station")
                val geo = st?.optJSONArray("geo")
                if (geo == null || geo.length() < 2) continue
                val v = aqiOf(o.opt("aqi"))
                if (v < 0) continue
                out += StationPick.Raw(
                    uid = o.optInt("uid", -1),
                    name = st.optString("name", ""),
                    lat = geo.optDouble(0, Double.NaN),
                    lon = geo.optDouble(1, Double.NaN),
                    value = v,
                )
            }
            out to ""
        } catch (e: ApiException) {
            emptyList<StationPick.Raw>() to e.message.orEmpty()
        } catch (e: Exception) {
            emptyList<StationPick.Raw>() to
                "${e.javaClass.simpleName}: ${e.message.orEmpty().take(60)}"
        }
    }

    internal fun parseBounds(j: JSONObject): List<StationPick.Raw> {
        val arr = j.optJSONArray("data") ?: return emptyList()
        val out = ArrayList<StationPick.Raw>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val v = aqiOf(o.opt("aqi"))
            if (v < 0) continue
            out += StationPick.Raw(
                uid = o.optInt("uid", -1),
                name = o.optJSONObject("station")?.optString("name", "") ?: "",
                lat = o.optDouble("lat", Double.NaN),
                lon = o.optDouble("lon", Double.NaN),
                value = v,
            )
        }
        return out
    }

    internal fun cityOf(name: String): String = name
        .split(',')
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .lastOrNull { it.length > 3 || !it.all { c -> c.isUpperCase() } }
        .orEmpty()

    private fun mergeByUid(
        a: List<StationPick.Raw>,
        b: List<StationPick.Raw>,
    ): List<StationPick.Raw> {
        val seen = HashSet<String>()
        val out = ArrayList<StationPick.Raw>(a.size + b.size)
        for (s in a + b) {
            val key = if (s.uid > 0) "u${s.uid}" else "n" + s.name.lowercase(Locale.US)
            if (seen.add(key)) out += s
        }
        return out
    }

    private data class Rec(val value: Int, val observed: String, val error: String)

    private fun recalcByUid(uid: Int, token: String): Rec {
        if (uid <= 0) return Rec(-1, "", "no station uid")
        return try {
            val url = FEED_BY_UID.format(Locale.US, uid, URLEncoder.encode(token, "UTF-8"))
            val s = sampleOf(JSONObject(get(url, 6_000, 8_000)))
            Rec(s.recalc.aqi, s.observed, "")
        } catch (e: ApiException) {
            Rec(-1, "", e.message.orEmpty())
        } catch (e: Exception) {
            Rec(-1, "", "${e.javaClass.simpleName}: ${e.message.orEmpty().take(60)}")
        }
    }

    private fun aqiOf(raw: Any?): Int = when (raw) {
        is Number -> raw.toInt()
        is String -> raw.toIntOrNull() ?: -1
        else -> -1
    }

    private fun fmt(d: Double): String = String.format(Locale.US, "%.4f", d)

    private fun sleep(ms: Long) {
        try {
            Thread.sleep(ms)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    private fun get(url: String, connectMs: Int, readMs: Int): String {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = connectMs
            readTimeout = readMs
            requestMethod = "GET"
            setRequestProperty("Accept", "application/json")
            instanceFollowRedirects = true
        }
        return try {
            if (conn.responseCode != HttpURLConnection.HTTP_OK) {
                throw ApiException("HTTP ${conn.responseCode}")
            }
            conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }

    fun fetchRecalc(lat: Double, lon: Double, token: String): AqiState {
        if (token.isBlank()) {
            throw ApiException("WAQI Token 为空：请到 aqicn.org/data-platform/token 免费申请后填入，或把主源切回 OM")
        }
        val url = ENDPOINT.format(
            Locale.US,
            String.format(Locale.US, "%.4f", lat),
            String.format(Locale.US, "%.4f", lon),
            URLEncoder.encode(token, "UTF-8"),
        )
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 10_000
            readTimeout = 10_000
            requestMethod = "GET"
            setRequestProperty("Accept", "application/json")
            instanceFollowRedirects = true
        }
        return try {
            val code = conn.responseCode
            if (code != HttpURLConnection.HTTP_OK) {
                val detail = conn.errorStream?.bufferedReader()?.use { it.readText() }
                    ?.take(200).orEmpty()
                throw ApiException("HTTP $code $detail")
            }
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            parseRecalc(JSONObject(body))
        } finally {
            conn.disconnect()
        }
    }

    private class Sample(
        val recalc: WaqiRecalc.Result,
        val name: String,
        val lat: Double,
        val lon: Double,
        val uid: Int,
        val observed: String,
        val attribution: String,
    )

    private fun sampleOf(j: JSONObject): Sample {
        val status = j.optString("status")
        if (status != "ok") throw ApiException("WAQI status=$status, data=${j.opt("data")}")

        val d = j.optJSONObject("data") ?: throw ApiException("missing data")
        val rawAqi = d.opt("aqi")
        val reported = when (rawAqi) {
            is Number -> rawAqi.toInt()
            is String -> rawAqi.toIntOrNull() ?: -1
            else -> -1
        }
        if (reported < 0) throw ApiException("station has no AQI (aqi=$rawAqi)")

        val rawSub = mutableMapOf<String, Int>()
        d.optJSONObject("iaqi")?.let { iaqi ->
            val keys = iaqi.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                val v = iaqi.optJSONObject(k)?.optInt("v", -1) ?: -1
                if (v >= 0) rawSub[k] = v
            }
        }

        val r = try {
            WaqiRecalc.compute(rawSub, reported)
        } catch (e: IllegalArgumentException) {
            throw ApiException("WAQI recalc failed: ${e.message}")
        }

        val city = d.optJSONObject("city")
        val geo = city?.optJSONArray("geo")
        val lat = if (geo != null && geo.length() >= 2) geo.optDouble(0, Double.NaN) else Double.NaN
        val lon = if (geo != null && geo.length() >= 2) geo.optDouble(1, Double.NaN) else Double.NaN

        val attribution = buildString {
            d.optJSONArray("attributions")?.let { arr ->
                for (i in 0 until arr.length()) {
                    val name = arr.optJSONObject(i)?.optString("name", "") ?: ""
                    if (name.isNotBlank()) {
                        if (isNotEmpty()) append(" · ")
                        append(name)
                    }
                }
            }
            if (isEmpty()) append("WAQI / aqicn")
        }

        return Sample(
            recalc = r,
            name = city?.optString("name", "") ?: "",
            lat = lat,
            lon = lon,
            uid = d.optInt("idx", -1),
            observed = d.optJSONObject("time")?.optString("s", "") ?: "",
            attribution = attribution,
        )
    }

    internal fun parseRecalc(j: JSONObject): AqiState {
        val s = sampleOf(j)
        val r = s.recalc
        return AqiState(
            value = r.aqi,
            level = AqiLevel.of(r.aqi),
            dominantPollutant = displayLabel(r.dominant),
            subIndices = r.subIndices.mapKeys { displayLabel(it.key) },
            stationName = s.name,
            distanceKm = 0.0,
            observedText = s.observed,
            fetchedAtMillis = System.currentTimeMillis(),
            freshness = Freshness.FRESH,
            attribution = s.attribution,
            standard = AqiStandard.US,
            sourceTag = AqiSource.WAQI_RECALC.tag,
            diag = DiagInfo(
                kind = DiagInfo.Kind.WAQI_RECALC,
                pollutant = displayLabel(r.dominant),
                concentration = r.equivalentPm25,
                localAqi = r.aqi,
                originalIndex = r.originalPm25Index,
                equivalent = r.equivalentPm25,
                recalcIndex = r.recalculatedPm25Index,
                reportedAqi = r.reportedAqi,
            ),
        )
    }

    private fun displayLabel(key: String): String = when (key.lowercase(Locale.US)) {
        "pm25" -> "PM2.5"
        "pm10" -> "PM10"
        "o3" -> "O₃"
        "no2" -> "NO₂"
        "so2" -> "SO₂"
        "co" -> "CO"
        else -> key.uppercase(Locale.US)
    }

    private const val MAP_BOUNDS = "https://api.waqi.info/map/bounds/?latlng=%s,%s,%s,%s&token=%s"

    private const val SEARCH = "https://api.waqi.info/search/?keyword=%s&token=%s"

    private const val FEED_BY_UID = "https://api.waqi.info/feed/@%d/?token=%s"

    private const val FEED_GAP_MS = 180L

    private const val AREA_BUDGET_MS = 30_000L

    private val BOX_HALF_DEG = doubleArrayOf(0.30, 1.20, 4.00)
}
