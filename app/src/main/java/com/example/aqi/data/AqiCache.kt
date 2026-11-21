package com.example.aqi.data

import android.content.Context
import com.example.aqi.core.AqiLevel
import com.example.aqi.core.AreaStat
import com.example.aqi.core.AqiSource
import com.example.aqi.core.AqiStandard
import com.example.aqi.core.AqiState
import com.example.aqi.core.DiagInfo
import com.example.aqi.core.Freshness
import org.json.JSONObject

class AqiCache(ctx: Context) {

    private val sp = ctx.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
    private val prefs = AqiPrefs(ctx)

    fun save(state: AqiState) {
        if (!state.hasValue) return
        sp.edit().putString(keyFor(state), toJson(state).toString()).apply()
    }

    fun loadRaw(source: AqiSource, standard: AqiStandard): AqiState? = load(source, standard)

    fun loadForDisplay(
        intervalMinutes: Int,
        standard: AqiStandard,
        tokenBlank: Boolean,
    ): AqiState = loadForSource(primarySource(standard), intervalMinutes, standard, tokenBlank)

    fun loadSecondaryForDisplay(
        intervalMinutes: Int,
        standard: AqiStandard,
        tokenBlank: Boolean,
    ): AqiState? {
        if (standard != AqiStandard.US || !prefs.dualSourceEnabled) return null
        return loadForSource(
            AqiSource.other(primarySource(standard)), intervalMinutes, standard, tokenBlank,
        )
    }

    fun secondaryStatus(): String = prefs.secondaryStatus

    fun clear() {
        val e = sp.edit()
        AqiStandard.values().forEach { e.remove(keyForSource(AqiSource.OM, it)) }
        e.remove(KEY_SECONDARY)
        e.remove(LEGACY_KEY_STATE)
        e.apply()
    }

    private fun primarySource(standard: AqiStandard): AqiSource =
        if (standard == AqiStandard.CN) AqiSource.OM else prefs.primarySource

    private fun areaSource(standard: AqiStandard): AqiSource =
        if (standard == AqiStandard.CN) AqiSource.OM else (prefs.areaSource ?: prefs.primarySource)

    private fun loadForSource(
        source: AqiSource,
        intervalMinutes: Int,
        standard: AqiStandard,
        tokenBlank: Boolean,
    ): AqiState {
        val c = load(source, standard) ?: return AqiState.placeholder(
            if (source.needsToken && tokenBlank) Freshness.NO_TOKEN else Freshness.FAILED,
            standard,
        )
        val ageMs = System.currentTimeMillis() - c.fetchedAtMillis
        val staleAfter = intervalMinutes * 60_000L * 2
        val wantKind = if (areaSource(standard) == AqiSource.WAQI_RECALC) {
            com.example.aqi.core.AreaKind.STATION
        } else {
            com.example.aqi.core.AreaKind.GRID
        }
        val kindOk = c.area?.kind == wantKind || c.area?.noteCode == AreaStat.NOTE_STATION_FAILED
        val radiusOk = c.area == null ||
            c.area.kind == com.example.aqi.core.AreaKind.STATION ||
            c.area.radiusKm == prefs.areaRadiusKm
        val areaValid = prefs.areaEnabled && radiusOk && c.area?.worst == prefs.areaWorst && kindOk
        return c.copy(
            freshness = if (ageMs > staleAfter) Freshness.STALE else Freshness.FRESH,
            area = if (areaValid) c.area else null,
        )
    }

    private fun load(source: AqiSource, standard: AqiStandard): AqiState? {
        val legacy = if (source == AqiSource.OM && standard == AqiStandard.US) {
            sp.getString(LEGACY_KEY_STATE, null)
        } else null
        val raw = sp.getString(keyForSource(source, standard), null) ?: legacy ?: return null
        return try {
            val j = JSONObject(raw)
            if (j.optString("standard", standard.id) != standard.id) return null
            if (j.optString("source", "") != expectedTag(source, standard)) return null
            fromJson(j, standard)
        } catch (e: Exception) {
            null
        }
    }

    private fun keyFor(state: AqiState): String =
        if (state.sourceTag == AqiSource.WAQI_RECALC.tag) KEY_SECONDARY
        else KEY_STATE_PREFIX + state.standard.id

    private fun keyForSource(source: AqiSource, standard: AqiStandard): String =
        if (source == AqiSource.WAQI_RECALC) KEY_SECONDARY
        else KEY_STATE_PREFIX + standard.id

    private fun expectedTag(source: AqiSource, standard: AqiStandard): String =
        if (standard == AqiStandard.CN) AqiStandard.CN.sourceTag(false) else source.tag

    private fun toJson(s: AqiState): JSONObject = JSONObject().apply {
        put("value", s.value)
        put("level", s.level.name)
        put("pollutant", s.dominantPollutant)
        put("sub", JSONObject().apply { s.subIndices.forEach { (k, v) -> put(k, v) } })
        put("station", s.stationName)
        put("distance", s.distanceKm)
        put("observed", s.observedText)
        put("fetched", s.fetchedAtMillis)
        put("attribution", s.attribution)
        put("standard", s.standard.id)
        put("source", s.sourceTag)
        put("diagnostic", s.diagnostic)
        if (s.modelGrid) put("modelGrid", true)
        s.diag?.let { d ->
            put("diag", JSONObject().apply {
                put("kind", d.kind.name)
                put("poll", d.pollutant)
                put("hours", d.windowHours)
                put("conc", d.concentration)
                put("local", d.localAqi)
                put("official", d.officialAqi)
                put("fellBack", d.fellBackCount)
                put("orig", d.originalIndex)
                put("equiv", d.equivalent)
                put("recalc", d.recalcIndex)
                put("reported", d.reportedAqi)
            })
        }
        s.area?.let { a ->
            put("area", JSONObject().apply {
                put("value", a.value)
                put("level", a.level.name)
                put("radius", a.radiusKm)
                put("samples", a.samples)
                put("grid", org.json.JSONArray(a.grid))
                put("worst", a.worst)
                put("median", a.median)
                put("min", a.min)
                put("max", a.max)
                put("obs", a.observedText)
                put("pts", org.json.JSONArray().apply {
                    a.points.forEach { p ->
                        put(org.json.JSONArray().apply {
                            put(p.lat)
                            put(p.lon)
                            put(p.value)
                            put(p.distanceKm)
                            if (p.name.isNotBlank()) put(p.name)
                        })
                    }
                })
                put("kind", a.kind.name)
                if (a.noteCode.isNotBlank()) {
                    put("note", a.noteCode)
                    put("noteD", a.noteDetail)
                }
            })
        }
    }

    private fun fromJson(j: JSONObject, standard: AqiStandard): AqiState {
        val sub = mutableMapOf<String, Int>()
        j.optJSONObject("sub")?.let { obj ->
            val keys = obj.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                val v = obj.optInt(k, -1)
                if (v >= 0) sub[k] = v
            }
        }
        val value = j.optInt("value", -1)
        val level = try {
            AqiLevel.valueOf(j.optString("level", AqiLevel.GOOD.name))
        } catch (e: Exception) {
            AqiLevel.of(value)
        }
        return AqiState(
            value = value,
            level = level,
            dominantPollutant = j.optString("pollutant", ""),
            subIndices = sub,
            stationName = j.optString("station", ""),
            distanceKm = j.optDouble("distance", 0.0),
            observedText = j.optString("observed", ""),
            fetchedAtMillis = j.optLong("fetched", 0L),
            freshness = Freshness.STALE,
            attribution = j.optString("attribution", ""),
            standard = standard,
            sourceTag = j.optString("source", ""),
            diagnostic = j.optString("diagnostic", ""),
            area = readArea(j.optJSONObject("area")),
            modelGrid = j.optBoolean("modelGrid", false),
            diag = readDiag(j.optJSONObject("diag")),
        )
    }

    private fun readDiag(o: JSONObject?): DiagInfo? {
        if (o == null) return null
        return DiagInfo(
            kind = try {
                DiagInfo.Kind.valueOf(o.optString("kind", DiagInfo.Kind.LOCAL.name))
            } catch (e: Exception) {
                DiagInfo.Kind.LOCAL
            },
            pollutant = o.optString("poll", ""),
            windowHours = o.optInt("hours", 24),
            concentration = o.optDouble("conc", Double.NaN),
            localAqi = o.optInt("local", -1),
            officialAqi = o.optInt("official", -1),
            fellBackCount = o.optInt("fellBack", 0),
            originalIndex = o.optInt("orig", -1),
            equivalent = o.optDouble("equiv", Double.NaN),
            recalcIndex = o.optInt("recalc", -1),
            reportedAqi = o.optInt("reported", -1),
        )
    }

    private fun readArea(o: JSONObject?): AreaStat? {
        if (o == null) return null
        val v = o.optInt("value", -1)
        if (v < 0) return null
        val gridArr = o.optJSONArray("grid")
        val grid = if (gridArr == null) emptyList() else (0 until gridArr.length()).map {
            gridArr.optInt(it, -1)
        }
        val ptsArr = o.optJSONArray("pts")
        val points = if (ptsArr == null) emptyList() else (0 until ptsArr.length()).mapNotNull { i ->
            val t = ptsArr.optJSONArray(i) ?: return@mapNotNull null
            com.example.aqi.core.AreaPoint(
                index = i,
                value = t.optInt(2, -1),
                lat = t.optDouble(0, 0.0),
                lon = t.optDouble(1, 0.0),
                distanceKm = t.optDouble(3, 0.0),
                name = t.optString(4, ""),
            )
        }
        return AreaStat(
            value = v,
            level = AqiLevel.of(v),
            radiusKm = o.optInt("radius", AqiPrefs.DEFAULT_RADIUS_KM),
            samples = o.optInt("samples", grid.count { it >= 0 }),
            grid = grid,
            worst = o.optBoolean("worst", false),
            median = o.optInt("median", v),
            min = o.optInt("min", v),
            max = o.optInt("max", v),
            points = points,
            observedText = o.optString("obs", ""),
            kind = try {
                com.example.aqi.core.AreaKind.valueOf(o.optString("kind", "GRID"))
            } catch (e: Exception) {
                com.example.aqi.core.AreaKind.GRID
            },
            noteCode = o.optString("note", ""),
            noteDetail = o.optString("noteD", ""),
        )
    }

    companion object {
        const val PREF_NAME = "aqi_cache"
        private const val KEY_STATE_PREFIX = "state_"
        private const val KEY_SECONDARY = "state_waqi_recalc"
        private const val LEGACY_KEY_STATE = "state"
    }
}
