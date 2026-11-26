package io.github.theme26.aqibar.core

import java.util.Locale

object StationPick {

    const val CELLS = 9

    data class Raw(
        val uid: Int,
        val name: String,
        val lat: Double,
        val lon: Double,
        val value: Int,
    )

    fun nearest(
        stations: List<Raw>,
        lat: Double,
        lon: Double,
        excludeName: String,
        limit: Int = CELLS,
    ): List<Raw> {
        if (limit <= 0) return emptyList()
        val seen = HashSet<String>()
        val scored = ArrayList<Pair<Raw, Double>>(stations.size)
        for (s in stations) {
            if (s.lat.isNaN() || s.lon.isNaN()) continue
            if (excludeName.isNotBlank() && s.name.equals(excludeName, ignoreCase = true)) continue
            val key = if (s.uid > 0) "u${s.uid}" else "n" + s.name.lowercase(Locale.US)
            if (!seen.add(key)) continue
            val d = Geo.distanceKm(lat, lon, s.lat, s.lon)
            if (d.isNaN()) continue
            scored += s to d
        }
        scored.sortWith(compareBy({ it.second }, { it.first.uid }))
        return scored.take(limit).map { it.first }
    }

    fun assemble(
        centerName: String,
        centerValue: Int,
        lat: Double,
        lon: Double,
        ranked: List<Raw>,
        centerDistanceKm: Double = 0.0,
    ): List<AreaPoint> {
        val out = arrayOfNulls<AreaPoint>(CELLS)
        val offset: Int
        if (centerValue >= 0) {
            out[0] = AreaPoint(
                index = 0,
                value = centerValue,
                lat = lat,
                lon = lon,
                name = centerName,
                distanceKm = centerDistanceKm,
            )
            offset = 1
        } else {
            offset = 0
        }
        for ((i, c) in ranked.withIndex()) {
            val slot = i + offset
            if (slot >= CELLS) break
            out[slot] = AreaPoint(
                index = slot,
                value = c.value,
                lat = c.lat,
                lon = c.lon,
                name = c.name,
                distanceKm = Geo.distanceKm(lat, lon, c.lat, c.lon),
            )
        }
        return (0 until CELLS).map { out[it] ?: AreaPoint(index = it, value = -1, lat = 0.0, lon = 0.0) }
    }
}
