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

/**
 * 唯一数据入口：坐标 → 位移/时间去重 → 按标准与主源取数 → 双槽缓存 → 异常态兜底。
 * 全同步方法，调用方是 WorkManager Worker 的后台线程，不需要协程。
 *
 * 每一次刷新（成功或失败）都会把人类可读的结果写进 AqiPrefs 同步状态，
 * 因为缓存只存成功数值，UI 若不读这个状态就只能看到「––」。
 *
 * ## v0.1.6 双源并列（一次刷新，两源各自独立）
 *  - **主源**（决定状态栏三档图标 / 主卡大数字 / 通知 / 桌面小部件）：
 *    默认 [AqiSource.OM]，用户可切到 [AqiSource.WAQI_RECALC]。
 *  - **副源**（主卡第二行 / 通知展开区对照行）：US 模式下取另一源，独立 try/catch，
 *    失败只写 `secondaryStatus` 文案，**绝不拖垮主源、绝不复用主源数值顶替**。
 *  - CN AQI 模式固定单源 OM，不做两源对照。
 *
 * 缓存按源分槽，切主源不改缓存、只改读取源。
 */
class AqiRepository(private val ctx: Context) {

    private val prefs = AqiPrefs(ctx)
    private val cache = AqiCache(ctx)
    private val location = LocationProvider(ctx)

    fun refresh(force: Boolean = false): AqiState {
        val standard = prefs.standard
        // CN 模式固定单源 OM；US 模式跟随用户设置的主源（默认 OM）
        val primary: AqiSource = if (standard == AqiStandard.CN) AqiSource.OM else prefs.primarySource
        val secondary: AqiSource? = if (standard == AqiStandard.US && prefs.dualSourceEnabled) {
            AqiSource.other(primary)
        } else null
        val token = prefs.token
        val tokenBlank = token.isBlank()

        // ---- 前置校验 1/4：手动坐标格式 ----
        val manualRaw = prefs.manualCoord
        val manual = prefs.parseManualCoord()
        if (manualRaw.isNotBlank() && manual == null) return fail(
            Freshness.FAILED,
            ctx.getString(R.string.sync_manual_bad),
            standard,
        )

        // ---- 前置校验 2/4、3/4：定位权限与系统缓存位置 ----
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

        // ---- 前置校验 4/4：主源需要 Token 但没填 ----
        // 不静默用 OM 顶替主源（规格 §3④）。但仍刷新副源，让主卡第二行有真实对照值。
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

        // 缓存按源分槽 + sourceTag 校验，切换主源后不会把另一口径的值串过来
        val cached = cache.loadRaw(primary, standard)
        val ageMs = System.currentTimeMillis() - (cached?.fetchedAtMillis ?: 0L)
        val intervalMs = prefs.intervalMinutes * 60_000L

        // 位移 < 10km 且未到刷新间隔 → 直接复用缓存，不发请求。手动刷新(force)必须绕过这里
        if (!force && movedKm < MOVE_THRESHOLD_KM && ageMs < intervalMs) {
            prefs.recordSync(
                true,
                ctx.getString(R.string.sync_up_to_date, hhmm(cached?.fetchedAtMillis ?: 0L)),
            )
            return cached?.copy(
                freshness = if (ageMs > intervalMs * 2) Freshness.STALE else Freshness.FRESH
            ) ?: fallback(Freshness.FAILED, standard)
        }

        // ---- 主源：独立成功/失败，不让副源影响它 ----
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

        // ---- 副源：独立 try/catch，失败只写文案，绝不写假数值 ----
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
            // 网络失败：保留上次数值但明确标 STALE，绝不静默当实时值显示
            fallback(Freshness.STALE, standard)
        }
    }

    /**
     * 副源刷新。无 Token / 网络失败 / 反算失败都只写 `secondaryStatus`，
     * 主卡第二行据此显示明确占位，**不显示假数据、不复用 OM 数值、不回退 2012 原值**。
     */
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

    /**
     * 给主值挂上周边九宫格。**独立 try/catch**：周边取不到就退回单层卡片，
     * 绝不让它影响主数值、也绝不重试 —— 主值已经拿到了，用户至少能看口罩结论。
     *
     * v0.4.5：数据源跟随主源（可单独覆盖）。WAQI 模式下是真实站点，
     * 要额外打 1 次 map/bounds + 8 次 feed；任何一步失败都只退回单层，
     * 绝不拿 OM 格点冒充站点环 —— 那会让用户以为「这几个站就是这个值」。
     */
    private fun attachArea(
        s: AqiState,
        coord: AqiPrefs.Coord,
        standard: AqiStandard,
    ): AqiState {
        if (!s.hasValue || !prefs.areaEnabled) return s

        // CN 只有 OM 一条链路；US 才允许切到 WAQI 站点环
        val source = if (standard == AqiStandard.CN) {
            AqiSource.OM
        } else {
            prefs.areaSource ?: prefs.primarySource
        }
        if (source == AqiSource.WAQI_RECALC && prefs.token.isBlank()) return s

        // 只有「周边源 == 主源」时，中心格才能直接取主数字。
        // 否则（例如主源 OM、周边切到 WAQI 站点）中心格必须由周边源自己算，
        // 绝不能把 OM 的 144 塞进一堆 WAQI 站点中间 —— 那不是同一个口径。
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

        // ---- WAQI 站点环 ----
        val res = try {
            // 站点模式不再传半径：要的是「最近的 9 个」，不是一个圈里的
            WaqiApi.fetchArea(
                coord.lat, coord.lon, prefs.token,
                centerValue, s.stationName, prefs.areaWorst, s.distanceKm,
            )
        } catch (e: Exception) {
            WaqiApi.AreaResult(null, "${e.javaClass.simpleName}: ${e.message.orEmpty().take(60)}")
        }
        res.area?.let { return s.copy(area = it) }

        // 站点环整块拿不到（v0.4.6）：退回模式格点，但**必须标明**这是退回来的。
        // 宁可显示一份写着「已退回格点」的格点图，也不要让整块周边凭空消失 ——
        // v0.4.5 就是这样：地图接口对免费 Token 返回 Invalid key，用户看到的是「没数据了」。
        val alt = try {
            OpenMeteoApi.fetchArea(
                // 主源是 WAQI 时，OM 的格点不能借 WAQI 的主数字当中心值
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

    /**
     * 把异常翻译成用户能据以行动的具体原因。
     *
     * v0.4.1 起全部走字符串资源：这段文案会显示在今日页顶部的状态条上，
     * 之前英文界面在这里会突然出现一整句中文。嵌入的 `%s` 仍是原始技术消息
     * （HTTP 状态、异常名），它本来就是英文，不翻译。
     */
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

        /** Haversine，单位 km。用于「换城市才刷新」的去重判断。 */
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
