package com.example.aqi.data

import android.content.Context
import android.content.SharedPreferences
import com.example.aqi.core.AqiSource
import com.example.aqi.core.AqiStandard
import com.example.aqi.core.NotifPolicy

/**
 * 配置存储。用 SharedPreferences 而非 DataStore：一期数据结构简单、全同步读、
 * 少一层依赖。token 存在这里，已在备份规则里排除，不上传云端。
 */
class AqiPrefs(ctx: Context) {

    data class Coord(val lat: Double, val lon: Double)

    private val sp: SharedPreferences = ctx.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)

    var token: String
        get() = sp.getString(KEY_TOKEN, "") ?: ""
        set(v) = sp.edit().putString(KEY_TOKEN, v.trim()).apply()

    /** AQI 显示标准，默认美标 US AQI（EPA 2024 本地计算，无需 Token）。 */
    var standard: AqiStandard
        get() = AqiStandard.fromId(sp.getString(KEY_STANDARD, AqiStandard.US.id))
        set(v) = sp.edit().putString(KEY_STANDARD, v.id).apply()

    /**
     * v0.1.6 主源：决定状态栏三档图标、主卡大数字、通知与桌面小部件的数值。
     * **默认 [AqiSource.OM]**（用户拍板）——无需 Token、链路已由 v0.1.5 验证、
     * 与 Open-Meteo 官方 us_aqi 差 ≤5。用户可自行切到 [AqiSource.WAQI_RECALC]。
     * CN AQI 模式忽略此项，固定单源 OM。
     */
    var primarySource: AqiSource
        get() = AqiSource.fromId(sp.getString(KEY_PRIMARY_SOURCE, AqiSource.OM.id))
        set(v) = sp.edit().putString(KEY_PRIMARY_SOURCE, v.id).apply()

    /** 主卡 / 通知展开区是否常驻「两源对照」第二行。默认开（规格决策 5）。 */
    var dualSourceEnabled: Boolean
        get() = sp.getBoolean(KEY_DUAL_SOURCE, true)
        set(v) = sp.edit().putBoolean(KEY_DUAL_SOURCE, v).apply()

    /**
     * 副源最近一次状态文案。第二行没数值时显示它（如「需填 WAQI Token」），
     * 成功时清空。**绝不存假数值**，也不复用主源数值顶替。
     */
    var secondaryStatus: String
        get() = sp.getString(KEY_SECONDARY_STATUS, "") ?: ""
        set(v) = sp.edit().putString(KEY_SECONDARY_STATUS, v.trim()).apply()

    /**
     * 通知时机，默认 [NotifPolicy.ALERT_ONLY]：优 / 良不推送，AQI ≥ 101 才占状态栏。
     * 该 ROM 会把 smallIcon 换成 launcher 图标，所以「通知是否存在」才是可靠的信息位。
     */
    var notifPolicy: NotifPolicy
        get() = NotifPolicy.fromId(sp.getString(KEY_NOTIF_POLICY, NotifPolicy.ALERT_ONLY.id))
        set(v) = sp.edit()
            .putString(KEY_NOTIF_POLICY, v.id)
            // 同步旧字段，避免 worker / 渠道创建处出现「已选 NEVER 但仍建渠道」的分叉
            .putBoolean(KEY_NOTIF, v != NotifPolicy.NEVER)
            .apply()

    /** 通知总开关（由 [notifPolicy] 派生）。false 时 NotificationManager 渠道也不建。 */
    var notificationEnabled: Boolean
        get() = sp.getBoolean(KEY_NOTIF, true) && notifPolicy != NotifPolicy.NEVER
        set(v) = sp.edit().putBoolean(KEY_NOTIF, v).apply()

    /** 锁屏是否显示站点名与距离，默认关（隐私优先）。 */
    var lockScreenFull: Boolean
        get() = sp.getBoolean(KEY_LOCK_FULL, false)
        set(v) = sp.edit().putBoolean(KEY_LOCK_FULL, v).apply()

    /**
     * 是否请求把常驻通知提升为 Live Update（v0.5.0，Android 16+）。
     *
     * 这是进入系统息屏界面的唯一第三方通道。默认开：低版本系统读到未知 extra
     * 直接忽略，没有副作用；Android 16+ 上装完就能验证到底行不行。
     * 若系统不采纳（类别不符或 ROM 白名单），关掉它退回普通常驻通知。
     */
    var liveUpdate: Boolean
        get() = sp.getBoolean(KEY_LIVE, true)
        set(v) = sp.edit().putBoolean(KEY_LIVE, v).apply()

    var intervalMinutes: Int
        get() = sp.getInt(KEY_INTERVAL, 60)
        set(v) = sp.edit().putInt(KEY_INTERVAL, v.coerceAtLeast(15)).apply()

    /**
     * 是否同时取周边区域值（v0.3.0）。关掉就只显示本站，卡片退回单层，
     * 也省掉每次刷新的那次多坐标请求。
     */
    var areaEnabled: Boolean
        get() = sp.getBoolean(KEY_AREA_ON, true)
        set(v) = sp.edit().putBoolean(KEY_AREA_ON, v).apply()

    /**
     * 区域采样半径（公里）。10 km 是一个区，25 km 覆盖主城区，50 km 才接近「全城」。
     *
     * v0.4.2 去掉了 3 km 档：国境内用的是 CAMS 全球模式（0.4°≈45 公里），
     * 3 公里圆周上的 8 个点全落在同一个模式格子里 —— 实测 5 个城市 9 点
     * 数值完全相同，这个档位等于没开。旧版本存下的 3 一律迁移到 10。
     */
    var areaRadiusKm: Int
        get() {
            val v = sp.getInt(KEY_AREA_RADIUS, DEFAULT_RADIUS_KM)
            return if (v in RADIUS_CHOICES) v else DEFAULT_RADIUS_KM
        }
        set(v) = sp.edit().putInt(KEY_AREA_RADIUS, if (v in RADIUS_CHOICES) v else DEFAULT_RADIUS_KM).apply()

    /**
     * 区域取值口径（v0.4.2）：true = 9 个点里取最大值（「最差格点」），
     * false = 取中位数（默认）。
     *
     * 为什么不默认最大值：实测同一时刻 9 个点的极差在 10 km 档只有 0~9，
     * 在 50 km 档才有 20~80；取最大值换来的是「宁可高报」的保守语义，
     * 代价是数字会随单个点的波动而跳。默认中位数回答「这一片大概什么水平」，
     * 想要防护口径的用户可以自己打开。
     */
    var areaWorst: Boolean
        get() = sp.getBoolean(KEY_AREA_WORST, false)
        set(v) = sp.edit().putBoolean(KEY_AREA_WORST, v).apply()

    /**
     * 周边数据源（v0.4.5）。
     *
     * 用户拍板：「area 就用城市数据，直接跟随用户选择；选了 WAQI 就用 WAQI，
     * 选了 OM 就用 OM」，同时又允许单独覆盖 —— 因为两种格子的语义完全不同：
     * OM 是 45 公里的模式格点，WAQI 是真实监测站（有站名、有距离）。
     * 有人只想要站名，有人不想多花 8 次请求，所以给一个显式开关。
     *
     * null = 跟随主源（默认）。
     */
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

    /**
     * 手动坐标，格式「纬度,经度」。非空时优先于系统定位，
     * 可完全绕开定位权限与 getLastKnownLocation 无缓存的国产 ROM 场景。
     */
    var manualCoord: String
        get() = sp.getString(KEY_MANUAL_COORD, "") ?: ""
        set(v) = sp.edit().putString(KEY_MANUAL_COORD, v.trim()).apply()

    /** 空白或格式错误都返回 null，调用方用 manualCoord.isNotBlank() 区分两者。 */
    fun parseManualCoord(): Coord? {
        val parts = manualCoord.replace('，', ',').split(',').map { it.trim() }
        if (parts.size != 2) return null
        val lat = parts[0].toDoubleOrNull() ?: return null
        val lon = parts[1].toDoubleOrNull() ?: return null
        if (lat < -90.0 || lat > 90.0 || lon < -180.0 || lon > 180.0) return null
        return Coord(lat, lon)
    }

    // ---- 同步状态 ----
    // AqiCache 只保存「上一次成功」的数值，失败态不会写入缓存。
    // 若没有这里的状态，UI 只能看到占位「––」，无法区分没权限 / 没位置 / token 无效 / 断网。
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

    /** 「跟随主源」的哨兵值。单独成一个常量，避免和 [AqiSource.id] 撞车。 */
    const val AREA_SOURCE_FOLLOW = "follow"

    /** 可选半径档位，必须与设置页分段控件、缓存校验保持一致。 */
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
