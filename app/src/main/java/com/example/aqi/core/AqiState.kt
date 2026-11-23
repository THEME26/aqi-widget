package com.example.aqi.core

enum class Freshness {
    FRESH,
    STALE,
    FAILED,
    NO_LOCATION,
    NO_PERMISSION,
    NO_TOKEN,
}

data class AreaStat(
    val value: Int,
    val level: AqiLevel,
    val radiusKm: Int,
    val samples: Int,
    val grid: List<Int> = emptyList(),
    val worst: Boolean = false,
    val median: Int = value,
    val min: Int = value,
    val max: Int = value,
    val points: List<AreaPoint> = emptyList(),
    val observedText: String = "",
    val kind: AreaKind = AreaKind.GRID,
    val noteCode: String = "",
    val noteDetail: String = "",
) {
    fun point(index: Int): AreaPoint? = points.getOrNull(index)

    val centerName: String
        get() = points.getOrNull(0)?.name.orEmpty()

    val observedHhmm: String
        get() {
            val t = observedText.substringAfter('T', observedText)
            return t.substringAfter(' ', t).take(5)
        }

    companion object {
        const val MIN_SAMPLES = 3

        fun of(
            values: List<Int>,
            radiusKm: Int,
            useWorst: Boolean,
            points: List<AreaPoint> = emptyList(),
            observedText: String = "",
            kind: AreaKind = AreaKind.GRID,
            noteCode: String = "",
            noteDetail: String = "",
        ): AreaStat? {
            val valid = values.filter { it >= 0 }
            if (valid.size < MIN_SAMPLES) return null
            val sorted = valid.sorted()
            val n = sorted.size
            val median = if (n % 2 == 1) {
                sorted[n / 2]
            } else {
                Math.round((sorted[n / 2 - 1] + sorted[n / 2]) / 2.0).toInt()
            }
            val min = sorted.first()
            val max = sorted.last()
            val value = if (useWorst) max else median
            return AreaStat(
                value = value,
                level = AqiLevel.of(value),
                radiusKm = radiusKm,
                samples = valid.size,
                grid = values,
                worst = useWorst,
                median = median,
                min = min,
                max = max,
                points = points,
                observedText = observedText,
                kind = kind,
                noteCode = noteCode,
                noteDetail = noteDetail,
            )
        }

        const val NOTE_STATION_FAILED = "station_failed"

        const val NOTE_VIA_SEARCH = "via_search"
    }
}

enum class AreaKind {
    GRID,

    STATION,
}

data class AreaPoint(
    val index: Int,
    val value: Int,
    val lat: Double,
    val lon: Double,
    val name: String = "",
    val distanceKm: Double = 0.0,
) {
    val isCenter: Boolean get() = index == 0
}

data class DiagInfo(
    val kind: Kind = Kind.LOCAL,
    val pollutant: String = "",
    val windowHours: Int = 24,
    val concentration: Double = Double.NaN,
    val localAqi: Int = -1,
    val officialAqi: Int = -1,
    val fellBackCount: Int = 0,
    val originalIndex: Int = -1,
    val equivalent: Double = Double.NaN,
    val recalcIndex: Int = -1,
    val reportedAqi: Int = -1,
) {
    enum class Kind { LOCAL, WAQI_RECALC }
}

data class AqiState(
    val value: Int,
    val level: AqiLevel,
    val dominantPollutant: String,
    val subIndices: Map<String, Int>,
    val stationName: String,
    val distanceKm: Double,
    val observedText: String,
    val fetchedAtMillis: Long,
    val freshness: Freshness,
    val attribution: String,
    val standard: AqiStandard = AqiStandard.US,
    val sourceTag: String = "",
    val diagnostic: String = "",
    val area: AreaStat? = null,
    val modelGrid: Boolean = false,
    val diag: DiagInfo? = null,
) {
    val hasValue: Boolean get() = value >= 0
    val isStale: Boolean get() = freshness != Freshness.FRESH

    companion object {
        fun placeholder(freshness: Freshness, standard: AqiStandard = AqiStandard.US): AqiState =
            AqiState(
                value = -1,
                level = AqiLevel.GOOD,
                dominantPollutant = "",
                subIndices = emptyMap(),
                stationName = "",
                distanceKm = 0.0,
                observedText = "",
                fetchedAtMillis = 0L,
                freshness = freshness,
                attribution = "",
                standard = standard,
            )
    }
}

object FreshnessRule {

    const val WINDOW_MULTIPLIER = 2L

    fun of(fetchedAtMillis: Long, nowMillis: Long, intervalMinutes: Int): Freshness {
        val window = intervalMinutes.coerceAtLeast(1) * 60_000L * WINDOW_MULTIPLIER
        return if (nowMillis - fetchedAtMillis > window) Freshness.STALE else Freshness.FRESH
    }
}
