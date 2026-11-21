package com.example.aqi.data

import android.content.Context
import android.content.SharedPreferences
import com.example.aqi.core.AqiSource
import com.example.aqi.core.AqiStandard
import com.example.aqi.core.NotifPolicy

class AqiPrefs(ctx: Context) {

    data class Coord(val lat: Double, val lon: Double)

    private val sp: SharedPreferences = ctx.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)

    var token: String
        get() = sp.getString(KEY_TOKEN, "") ?: ""
        set(v) = sp.edit().putString(KEY_TOKEN, v.trim()).apply()

    var standard: AqiStandard
        get() = AqiStandard.fromId(sp.getString(KEY_STANDARD, AqiStandard.US.id))
        set(v) = sp.edit().putString(KEY_STANDARD, v.id).apply()

    var primarySource: AqiSource
        get() = AqiSource.fromId(sp.getString(KEY_PRIMARY_SOURCE, AqiSource.OM.id))
        set(v) = sp.edit().putString(KEY_PRIMARY_SOURCE, v.id).apply()

    var dualSourceEnabled: Boolean
        get() = sp.getBoolean(KEY_DUAL_SOURCE, true)
        set(v) = sp.edit().putBoolean(KEY_DUAL_SOURCE, v).apply()

    var secondaryStatus: String
        get() = sp.getString(KEY_SECONDARY_STATUS, "") ?: ""
        set(v) = sp.edit().putString(KEY_SECONDARY_STATUS, v.trim()).apply()

    var notifPolicy: NotifPolicy
        get() = NotifPolicy.fromId(sp.getString(KEY_NOTIF_POLICY, NotifPolicy.ALERT_ONLY.id))
        set(v) = sp.edit()
            .putString(KEY_NOTIF_POLICY, v.id)
            .putBoolean(KEY_NOTIF, v != NotifPolicy.NEVER)
            .apply()

    var notificationEnabled: Boolean
        get() = sp.getBoolean(KEY_NOTIF, true) && notifPolicy != NotifPolicy.NEVER
        set(v) = sp.edit().putBoolean(KEY_NOTIF, v).apply()

    var lockScreenFull: Boolean
        get() = sp.getBoolean(KEY_LOCK_FULL, false)
        set(v) = sp.edit().putBoolean(KEY_LOCK_FULL, v).apply()

    var liveUpdate: Boolean
        get() = sp.getBoolean(KEY_LIVE, true)
        set(v) = sp.edit().putBoolean(KEY_LIVE, v).apply()

    var intervalMinutes: Int
        get() = sp.getInt(KEY_INTERVAL, 60)
        set(v) = sp.edit().putInt(KEY_INTERVAL, v.coerceAtLeast(15)).apply()

    var areaEnabled: Boolean
        get() = sp.getBoolean(KEY_AREA_ON, true)
        set(v) = sp.edit().putBoolean(KEY_AREA_ON, v).apply()

    var areaRadiusKm: Int
        get() {
            val v = sp.getInt(KEY_AREA_RADIUS, DEFAULT_RADIUS_KM)
            return if (v in RADIUS_CHOICES) v else DEFAULT_RADIUS_KM
        }
        set(v) = sp.edit().putInt(KEY_AREA_RADIUS, if (v in RADIUS_CHOICES) v else DEFAULT_RADIUS_KM).apply()

    var areaWorst: Boolean
        get() = sp.getBoolean(KEY_AREA_WORST, false)
        set(v) = sp.edit().putBoolean(KEY_AREA_WORST, v).apply()

    var areaSource: AqiSource?
        get() {
            val id = sp.getString(KEY_AREA_SOURCE, AREA_SOURCE_FOLLOW)
            return if (id == AREA_SOURCE_FOLLOW) null else AqiSource.fromId(id)
        }
        set(v) = sp.edit().putString(KEY_AREA_SOURCE, v?.id ?: AREA_SOURCE_FOLLOW).apply()

    var lastCoord: Coord?
        get() {
            val lat = sp.getString(KEY_LAT, null)?.toDoubleOrNull() ?: return null
            val lon = sp.getString(KEY_LON, null)?.toDoubleOrNull() ?: return null
            return Coord(lat, lon)
        }
        set(v) {
            val e = sp.edit()
            if (v == null) {
                e.remove(KEY_LAT); e.remove(KEY_LON)
            } else {
                e.putString(KEY_LAT, v.lat.toString())
                e.putString(KEY_LON, v.lon.toString())
            }
            e.apply()
        }

    var manualCoord: String
        get() = sp.getString(KEY_MANUAL_COORD, "") ?: ""
        set(v) = sp.edit().putString(KEY_MANUAL_COORD, v.trim()).apply()

    fun parseManualCoord(): Coord? {
        val parts = manualCoord.replace('，', ',').split(',').map { it.trim() }
        if (parts.size != 2) return null
        val lat = parts[0].toDoubleOrNull() ?: return null
        val lon = parts[1].toDoubleOrNull() ?: return null
        if (lat < -90.0 || lat > 90.0 || lon < -180.0 || lon > 180.0) return null
        return Coord(lat, lon)
    }

    val lastSyncAtMillis: Long get() = sp.getLong(KEY_SYNC_AT, 0L)
    val lastSyncOk: Boolean get() = sp.getBoolean(KEY_SYNC_OK, false)
    val lastSyncMessage: String get() = sp.getString(KEY_SYNC_MSG, "") ?: ""

    fun recordSync(ok: Boolean, message: String) {
        sp.edit()
            .putBoolean(KEY_SYNC_OK, ok)
            .putString(KEY_SYNC_MSG, message)
            .putLong(KEY_SYNC_AT, System.currentTimeMillis())
            .apply()
    }

    fun registerSyncListener(l: SharedPreferences.OnSharedPreferenceChangeListener) =
        sp.registerOnSharedPreferenceChangeListener(l)

    fun unregisterSyncListener(l: SharedPreferences.OnSharedPreferenceChangeListener) =
        sp.unregisterOnSharedPreferenceChangeListener(l)

    companion object {
        const val PREF_NAME = "aqi_prefs"
        private const val KEY_TOKEN = "token"
        private const val KEY_STANDARD = "standard"
        private const val KEY_PRIMARY_SOURCE = "primary_source"
        private const val KEY_DUAL_SOURCE = "dual_source"
        private const val KEY_SECONDARY_STATUS = "secondary_status"
        private const val KEY_NOTIF = "notif_enabled"
        private const val KEY_NOTIF_POLICY = "notif_policy"
        private const val KEY_LOCK_FULL = "lock_full"
        private const val KEY_LIVE = "live_update"
        private const val KEY_INTERVAL = "interval_min"
    private const val KEY_AREA_ON = "area_on"
    private const val KEY_AREA_RADIUS = "area_radius"
    private const val KEY_AREA_WORST = "area_worst"
    private const val KEY_AREA_SOURCE = "area_source"

    const val AREA_SOURCE_FOLLOW = "follow"

    val RADIUS_CHOICES = listOf(10, 25, 50)
    const val DEFAULT_RADIUS_KM = 10
        private const val KEY_LAT = "last_lat"
        private const val KEY_LON = "last_lon"
        private const val KEY_MANUAL_COORD = "manual_coord"
        const val KEY_SYNC_OK = "sync_ok"
        const val KEY_SYNC_MSG = "sync_msg"
        const val KEY_SYNC_AT = "sync_at"
    }
}
