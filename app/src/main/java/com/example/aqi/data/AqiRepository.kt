package com.example.aqi.data

import android.content.Context
import com.example.aqi.R
import com.example.aqi.core.AqiSource
import com.example.aqi.core.AqiStandard
import com.example.aqi.core.AqiState
import com.example.aqi.core.AreaStat
import com.example.aqi.core.Freshness
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

class AqiRepository(private val ctx: Context) {

    private val prefs = AqiPrefs(ctx)
    private val cache = AqiCache(ctx)
    private val location = LocationProvider(ctx)

    fun refresh(force: Boolean = false): AqiState {
        val standard = prefs.standard
        val primary: AqiSource = if (standard == AqiStandard.CN) AqiSource.OM else prefs.primarySource
        val secondary: AqiSource? = if (standard == AqiStandard.US && prefs.dualSourceEnabled) {
            AqiSource.other(primary)
        } else null
        val token = prefs.token
        val tokenBlank = token.isBlank()

        val manualRaw = prefs.manualCoord
        val manual = prefs.parseManualCoord()
        if (manualRaw.isNotBlank() && manual == null) return fail(
            Freshness.FAILED,
            ctx.getString(R.string.sync_manual_bad),
            standard,
        )

        val coord: AqiPrefs.Coord = if (manual != null) {
            manual
        } else {
            if (!location.hasPermission()) return fail(
                Freshness.NO_PERMISSION,
                ctx.getString(R.string.sync_no_perm),
                standard,
            )
            val l = location.lastKnown() ?: return fail(
                Freshness.NO_LOCATION,
                ctx.getString(R.string.sync_no_loc),
                standard,
            )
            AqiPrefs.Coord(l.latitude, l.longitude)
        }

        if (primary.needsToken && tokenBlank) {
            if (secondary != null) refreshSecondary(secondary, coord, token, tokenBlank, standard)
            return fail(
                Freshness.NO_TOKEN,
                ctx.getString(R.string.sync_no_token),
                standard,
            )
        }

        val last = prefs.lastCoord
        val movedKm = if (last == null) Double.MAX_VALUE
        else distanceKm(last.lat, last.lon, coord.lat, coord.lon)

        val cached = cache.loadRaw(primary, standard)
        val ageMs = System.currentTimeMillis() - (cached?.fetchedAtMillis ?: 0L)
        val intervalMs = prefs.intervalMinutes * 60_000L

        if (!force && movedKm < MOVE_THRESHOLD_KM && ageMs < intervalMs) {
            prefs.recordSync(
                true,
                ctx.getString(R.string.sync_up_to_date, hhmm(cached?.fetchedAtMillis ?: 0L)),
            )
            return cached?.copy(
                freshness = if (ageMs > intervalMs * 2) Freshness.STALE else Freshness.FRESH
            ) ?: fallback(Freshness.FAILED, standard)
        }

        var primaryState: AqiState? = null
        var primaryError: Exception? = null
        try {
            val fetched: AqiState = when (standard) {
                AqiStandard.CN -> OpenMeteoApi.fetch(coord.lat, coord.lon, AqiStandard.CN)
                AqiStandard.US -> if (primary == AqiSource.OM) {
                    OpenMeteoApi.fetch(coord.lat, coord.lon, AqiStandard.US)
                } else {
                    WaqiApi.fetchRecalc(coord.lat, coord.lon, token)
                }
            }
            val withArea = attachArea(fetched, coord, standard)
            cache.save(withArea)
            primaryState = withArea
        } catch (e: Exception) {
            primaryError = e
        }

        if (secondary != null) refreshSecondary(secondary, coord, token, tokenBlank, standard)

        prefs.lastCoord = coord

        val ok = primaryState
        return if (ok != null) {
            val station = when {
                ok.stationName.isNotBlank() -> ok.stationName
                ok.modelGrid -> ctx.getString(R.string.station_model_grid)
                else -> ctx.getString(R.string.station_unknown)
            }
            prefs.recordSync(
                true,
                ctx.getString(R.string.sync_ok, primary.shortLabel(ctx), station, hhmm(ok.fetchedAtMillis)),
            )
            ok
        } else {
            val e = primaryError ?: IOException("primary source returned no data")
            prefs.recordSync(false, describeError(e, standard, primary))
            fallback(Freshness.STALE, standard)
        }
    }

    private fun refreshSecondary(
        source: AqiSource,
        coord: AqiPrefs.Coord,
        token: String,
        tokenBlank: Boolean,
        standard: AqiStandard,
    ) {
        if (source.needsToken && tokenBlank) {
            prefs.secondaryStatus = ctx.getString(R.string.secondary_no_token)
            return
        }
        try {
            val s = when (source) {
                AqiSource.OM -> OpenMeteoApi.fetch(coord.lat, coord.lon, AqiStandard.US)
                AqiSource.WAQI_RECALC -> WaqiApi.fetchRecalc(coord.lat, coord.lon, token)
            }
            cache.save(s)
            prefs.secondaryStatus = ""
        } catch (e: Exception) {
            prefs.secondaryStatus = ctx.getString(R.string.secondary_failed, e.message.orEmpty().take(80))
        }
    }

    private fun attachArea(
        s: AqiState,
        coord: AqiPrefs.Coord,
        standard: AqiStandard,
    ): AqiState {
        if (!s.hasValue || !prefs.areaEnabled) return s

        val source = if (standard == AqiStandard.CN) {
            AqiSource.OM
        } else {
            prefs.areaSource ?: prefs.primarySource
        }
        if (source == AqiSource.WAQI_RECALC && prefs.token.isBlank()) return s

        val primary = if (standard == AqiStandard.CN) AqiSource.OM else prefs.primarySource
        val centerValue = if (source == primary) s.value else -1

        if (source != AqiSource.WAQI_RECALC) {
            return try {
                val area = OpenMeteoApi.fetchArea(
                    coord.lat, coord.lon, prefs.areaRadiusKm, standard, centerValue, prefs.areaWorst,
                )
                if (area != null) s.copy(area = area) else s
            } catch (e: Exception) {
                s
            }
        }

        val res = try {
            WaqiApi.fetchArea(
                coord.lat, coord.lon, prefs.token,
                centerValue, s.stationName, prefs.areaWorst, s.distanceKm,
            )
        } catch (e: Exception) {
            WaqiApi.AreaResult(null, "${e.javaClass.simpleName}: ${e.message.orEmpty().take(60)}")
        }
        res.area?.let { return s.copy(area = it) }

        val alt = try {
            OpenMeteoApi.fetchArea(
                coord.lat, coord.lon, prefs.areaRadiusKm, standard,
                if (primary == AqiSource.OM) centerValue else -1,
                prefs.areaWorst,
            )
        } catch (e: Exception) {
            null
        }
        val withNote = alt?.copy(
            noteCode = AreaStat.NOTE_STATION_FAILED,
            noteDetail = res.reason.take(60),
        )
        return if (withNote != null) s.copy(area = withNote) else s
    }

    private fun fail(reason: Freshness, message: String, standard: AqiStandard): AqiState {
        prefs.recordSync(false, message)
        return fallback(reason, standard)
    }

    private fun fallback(reason: Freshness, standard: AqiStandard): AqiState {
        val primary = if (standard == AqiStandard.CN) AqiSource.OM else prefs.primarySource
        val cached = cache.loadRaw(primary, standard) ?: return AqiState.placeholder(
            if (reason == Freshness.STALE) Freshness.FAILED else reason,
            standard,
        )
        return cached.copy(freshness = reason)
    }

    private fun describeError(e: Exception, standard: AqiStandard, primary: AqiSource): String {
        val m = e.message.orEmpty()
        val host = if (primary == AqiSource.WAQI_RECALC) "api.waqi.info"
        else "air-quality-api.open-meteo.com"
        return when {
            primary == AqiSource.WAQI_RECALC && m.contains("Invalid key", ignoreCase = true) ->
                ctx.getString(R.string.err_waqi_key)
            m.contains("status=", ignoreCase = true) ->
                ctx.getString(R.string.err_source, m)
            m.contains("HTTP ", ignoreCase = true) ->
                ctx.getString(R.string.err_http, m)
            m.contains("no AQI", ignoreCase = true) ->
                ctx.getString(R.string.err_no_aqi)
            m.contains("recalc failed", ignoreCase = true) ->
                ctx.getString(R.string.err_recalc, m)
            e is SocketTimeoutException -> ctx.getString(R.string.err_timeout, host)
            e is UnknownHostException -> ctx.getString(R.string.err_dns, host)
            e is IOException -> ctx.getString(R.string.err_io, e.javaClass.simpleName, m)
            else -> ctx.getString(R.string.err_other, e.javaClass.simpleName, m)
        }
    }

    private fun hhmm(millis: Long): String =
        if (millis <= 0L) "--:--"
        else SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(millis))

    companion object {
        const val MOVE_THRESHOLD_KM = 10.0

        fun distanceKm(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
            val r = 6371.0
            val dLat = Math.toRadians(lat2 - lat1)
            val dLon = Math.toRadians(lon2 - lon1)
            val a = sin(dLat / 2).pow(2) +
                cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2).pow(2)
            return 2 * r * asin(sqrt(a))
        }
    }
}
