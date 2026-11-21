package com.example.aqi.data

import com.example.aqi.core.AqiAverager
import com.example.aqi.core.AqiLevel
import com.example.aqi.core.AqiStandard
import com.example.aqi.core.AqiState
import com.example.aqi.core.DiagInfo
import com.example.aqi.core.AreaKind
import com.example.aqi.core.AreaPoint
import com.example.aqi.core.AreaStat
import com.example.aqi.core.CnAqiCalculator
import com.example.aqi.core.Freshness
import com.example.aqi.core.UsAqiCalculator
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

object OpenMeteoApi {

    class ApiException(message: String) : IOException(message)

    private val FIELDS = AqiAverager.FIELDS.joinToString(",")

    private const val ENDPOINT =
        "https://air-quality-api.open-meteo.com/v1/air-quality" +
            "?latitude=%s&longitude=%s" +
            "&current=%s,us_aqi,us_aqi_pm2_5" +
            "&hourly=%s" +
            "&past_days=2&forecast_days=1" +
            "&timezone=auto"

    private const val AREA_ENDPOINT =
        "https://air-quality-api.open-meteo.com/v1/air-quality" +
            "?latitude=%s&longitude=%s" +
            "&current=us_aqi&timezone=auto"

    fun fetch(lat: Double, lon: Double, standard: AqiStandard = AqiStandard.US): AqiState {
        val url = ENDPOINT.format(
            Locale.US,
            String.format(Locale.US, "%.4f", lat),
            String.format(Locale.US, "%.4f", lon),
            FIELDS,
            FIELDS,
        )
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 10_000
            readTimeout = 15_000
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
            parse(JSONObject(body), standard)
        } finally {
            conn.disconnect()
        }
    }

    private fun parse(j: JSONObject, standard: AqiStandard): AqiState {
        if (j.optBoolean("error", false)) {
            throw ApiException("Open-Meteo: ${j.optString("reason", "unknown error")}")
        }
        val cur = j.optJSONObject("current") ?: throw ApiException("no current data from Open-Meteo")
        val hourly = j.optJSONObject("hourly")
        val observed = cur.optString("time", "")

        val times = hourly?.optJSONArray("time")
        var endIndex = -1
        if (times != null) {
            for (i in 0 until times.length()) {
                if (times.optString(i, "") == observed) endIndex = i
            }
            if (endIndex < 0 && times.length() > 0) endIndex = times.length() - 1
        }
        if (endIndex < 0) {
            throw ApiException("no hourly series from Open-Meteo, cannot average")
        }

        val series = HashMap<String, DoubleArray>()
        AqiAverager.FIELDS.forEach { f ->
            series[f] = toDoubles(hourly?.optJSONArray(f))
        }
        val current = AqiAverager.FIELDS.associate { f -> f to cur.optDouble(f, Double.NaN) }
        val avg = AqiAverager.build(series, current, endIndex, standard)

        val officialUsAqi = cur.optInt("us_aqi", -1)

        return if (standard == AqiStandard.US) parseUs(avg, observed, officialUsAqi)
        else parseCn(avg, observed)
    }

    private fun toDoubles(arr: JSONArray?): DoubleArray {
        if (arr == null) return DoubleArray(0)
        val out = DoubleArray(arr.length())
        for (i in 0 until arr.length()) {
            out[i] = if (arr.isNull(i)) Double.NaN else arr.optDouble(i, Double.NaN)
        }
        return out
    }

    fun fetchArea(
        lat: Double,
        lon: Double,
        radiusKm: Int,
        standard: AqiStandard,
        centerValue: Int,
        useWorst: Boolean = false,
    ): AreaStat? {
        if (standard != AqiStandard.US) return null

        val pts = ringPoints(lat, lon, radiusKm)
        val lats = pts.joinToString(",") { String.format(Locale.US, "%.4f", it.first) }
        val lons = pts.joinToString(",") { String.format(Locale.US, "%.4f", it.second) }
        val url = AREA_ENDPOINT.format(Locale.US, lats, lons)

        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 8_000
            readTimeout = 12_000
            requestMethod = "GET"
            setRequestProperty("Accept", "application/json")
            instanceFollowRedirects = true
        }
        val body = try {
            if (conn.responseCode != HttpURLConnection.HTTP_OK) return null
            conn.inputStream.bufferedReader().use { it.readText() }
        } catch (e: Exception) {
            return null
        } finally {
            conn.disconnect()
        }

        val arr = try {
            JSONArray(body)
        } catch (e: Exception) {
            return null
        }
        val values = ArrayList<Int>(arr.length())
        var observed = ""
        for (i in 0 until arr.length()) {
            val j = arr.optJSONObject(i) ?: continue
            val cur = j.optJSONObject("current") ?: continue
            values += cur.optInt("us_aqi", -1)
            if (observed.isBlank()) observed = cur.optString("time", "")
        }
        if (values.size < AreaStat.MIN_SAMPLES) return null
        if (centerValue >= 0 && values.isNotEmpty()) values[0] = centerValue

        val points = pts.mapIndexed { i, p ->
            AreaPoint(
                index = i,
                value = values.getOrElse(i) { -1 },
                lat = p.first,
                lon = p.second,
                distanceKm = if (i == 0) 0.0 else radiusKm.toDouble(),
            )
        }
        return AreaStat.of(
            values, radiusKm, useWorst, points, observed, AreaKind.GRID,
        )
    }

    private fun ringPoints(lat: Double, lon: Double, radiusKm: Int): List<Pair<Double, Double>> {
        val cosLat = kotlin.math.cos(Math.toRadians(lat)).coerceAtLeast(0.05)
        val out = ArrayList<Pair<Double, Double>>(9)
        out += lat to lon
        for (i in 0 until 8) {
            val bearing = Math.toRadians(i * 45.0)
            val dLat = kotlin.math.cos(bearing) * radiusKm
            val dLon = kotlin.math.sin(bearing) * radiusKm
            out += (lat + dLat / 111.0) to (lon + dLon / (111.0 * cosLat))
        }
        return out
    }

    private fun diagOf(
        standard: AqiStandard,
        dominant: String,
        raw: Map<String, Double>,
        localAqi: Int,
        officialUsAqi: Int,
        fellBack: List<String>,
    ): DiagInfo {
        val field = AqiAverager.fieldOf(dominant)
        val hours = field?.let { AqiAverager.windowHours(standard, it) } ?: 24
        val c = field?.let { raw[it] } ?: Double.NaN
        return DiagInfo(
            kind = DiagInfo.Kind.LOCAL,
            pollutant = dominant,
            windowHours = hours,
            concentration = c,
            localAqi = localAqi,
            officialAqi = if (standard == AqiStandard.US) officialUsAqi else -1,
            fellBackCount = fellBack.size,
        )
    }

    private fun parseUs(avg: AqiAverager.Result, observed: String, officialUsAqi: Int): AqiState {
        val r = UsAqiCalculator.compute(avg.raw)
        if (r.aqi < 0) throw ApiException("no pollutant concentrations in this grid cell")
        return AqiState(
            value = r.aqi,
            level = AqiLevel.of(r.aqi),
            dominantPollutant = r.dominant,
            subIndices = r.subIndices,
            stationName = "",
            modelGrid = true,
            distanceKm = 0.0,
            observedText = observed,
            fetchedAtMillis = System.currentTimeMillis(),
            freshness = Freshness.FRESH,
            attribution = "",
            standard = AqiStandard.US,
            sourceTag = AqiStandard.US.sourceTag(false),
            diag = diagOf(
                AqiStandard.US, r.dominant, avg.raw, r.aqi, officialUsAqi, avg.fellBackToInstant,
            ),
        )
    }

    private fun parseCn(avg: AqiAverager.Result, observed: String): AqiState {
        val r = CnAqiCalculator.compute(avg.raw)
        if (r.aqi < 0) throw ApiException("no pollutant concentrations in this grid cell")
        return AqiState(
            value = r.aqi,
            level = AqiLevel.of(r.aqi),
            dominantPollutant = r.dominant,
            subIndices = r.subIndices,
            stationName = "",
            modelGrid = true,
            distanceKm = 0.0,
            observedText = observed,
            fetchedAtMillis = System.currentTimeMillis(),
            freshness = Freshness.FRESH,
            attribution = "",
            standard = AqiStandard.CN,
            sourceTag = AqiStandard.CN.sourceTag(false),
            diag = diagOf(
                AqiStandard.CN, r.dominant, avg.raw, r.aqi, -1, avg.fellBackToInstant,
            ),
        )
    }
}
