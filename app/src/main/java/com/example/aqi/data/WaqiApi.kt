package com.example.aqi.data

import com.example.aqi.core.AqiLevel
import com.example.aqi.core.AqiSource
import com.example.aqi.core.AqiStandard
import com.example.aqi.core.AqiState
import com.example.aqi.core.AreaKind
import com.example.aqi.core.StationPick
import com.example.aqi.core.AreaStat
import com.example.aqi.core.DiagInfo
import com.example.aqi.core.Freshness
import com.example.aqi.core.WaqiRecalc
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale

/**
 * WAQI / aqicn 的 geo feed —— v0.1.6 起作为**第二源「WAQI 反算 2024」**。
 *
 * ## 口径（务必看清，别再和 2024 本地计算混为一谈）
 * `feed` API 的 `iaqi.*.v` 是**按 EPA 2012 旧断点换算后的整数分指数**，不是原始浓度。
 * 实测 10 个端点/参数（`specie` / `units=ugm3` / `raw=1` / `format=raw` / `version=2` /
 * `api` 前缀 / `obs` / `forecast` / `@idx` / `aqicn.org/api`）全返回同一份分指数；
 * 官方 json-api 文档仍把 `Pollutant raw concentration` 列在 "will be added" 清单，
 * 原始浓度/小时数据要走 data-platform 机构申请，ToS 禁止缓存再分发、禁止付费应用。
 *
 * 所以这里**不做「基于实测浓度本地计算」**，而是由 [WaqiRecalc] 把 2012 分指数
 * **逆推等效浓度 → 按 EPA 2024 重算**。结果是非实测的 derived 值，±1~2 AQI 取整抖动，
 * 且 WAQI 分指数的平均窗口**未实测**。归因文案必须原样展示给用户。
 *
 * ## 明确不做的事
 *  - 不回退到 2012 原值当 2024 显示（v0.1.4 之前的老做法已废弃）；
 *  - 无 Token / 无 PM2.5 分指数 / 反算失败一律抛 [ApiException]，由 UI 显示占位，
 *    **绝不静默复用 OM 数值**（规格 §3④ 硬约束）；
 *  - 不把 `iaqi` 里的 t/h/w/p 气象字段当 AQI（由 WaqiRecalc 过滤）。
 *
 * 免费授权约束：禁止商用、必须署名 WAQI 与原始 EPA、禁止缓存再分发。
 * 本项目只在设备本地缓存上一次结果用于离线展示，不对外再分发。
 */
object WaqiApi {

    class ApiException(message: String) : IOException(message)

    private const val ENDPOINT = "https://api.waqi.info/feed/geo:%s;%s/?token=%s"

    /**
     * 周边九宫格的一次取数结果。
     *
     * [reason] 是一句**技术原因**（英文，不翻译）：`HTTP 429`、`Invalid key`、
     * `over quota`、`only 2 usable cells`。它会显示在卡片副标题里 —— 出问题时
     * 用户截一张图就能说清是哪一步坏了，不用连 adb 抓 logcat。
     */
    data class AreaResult(val area: AreaStat?, val reason: String = "")

    /**
     * 周边九宫格（v0.4.7）：**最近的 9 个站，按距离排名**，不限半径、不看方位。
     *
     * ## 为什么又改
     * v0.4.5 用 `map/bounds` 拿清单再按 45° 扇区挑；v0.4.6 改成 8 方位探测 + 半径过滤。
     * 真机上后者报「only 2 usable cells」：探测点本身落在半径上，它返回的站自然也在
     * 半径边缘，再被 `d > radiusKm` 一滤就没了。**半径与「最近的 N 个」互斥** ——
     * 先画一个圈再往里挑，圈外的站永远进不来，而用户要的就是「最近的」。
     *
     * ## 现在的规则
     * 1. 枚举：`map/bounds` 逐级放大矩形（[BOX_HALF_DEG]，0.3°≈33 km 起步），凑够 9 个就停；
     *    参数顺序按官方 spec 是 `lat_north,lng_west,lat_south,lng_east`；
     * 2. 枚举不到（权限 / 配额 / 海上 / 站点太稀）退到 `search?keyword=<城市名>`，同样按距离排；
     * 3. 按距离排序取前 9（[StationPick.nearest]）：中心 = 最近，外圈顺时针第 2–9 近；
     * 4. 逐站 `feed/@uid` 按 2024 重算，串行 + [FEED_GAP_MS] 间隔 —— 免费 Token 并行会被拒。
     *
     * 格子位置从此**只表示远近，不表示方位**。这句话必须同时出现在卡片说明与详情弹层里，
     * 否则用户会拿「东北角」当东北方向看，而那张图里根本没有方向。
     *
     * @param centerValue 主链路的 2024 重算值；<0 表示周边源 ≠ 主源，中心格由最近的站充当
     * @param centerName 主链路站名，用于把它从候选里剔除 —— 否则它会以外圈第 2 近的身份再出现一次
     */
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
        // 预算从枚举就开始算：4 级放大 × 单请求 8 秒，不给上限能把一次刷新拖到半分钟
        val deadline = System.currentTimeMillis() + AREA_BUDGET_MS

        val (bounded, bErr) = boundsStations(lat, lon, token, deadline)
        if (bErr.isNotBlank()) firstError = bErr
        var raw = bounded
        if (raw.size < StationPick.CELLS) {
            val (found, sErr) = searchStations(token, centerName)
            if (sErr.isNotBlank() && firstError.isBlank()) firstError = sErr
            if (found.isNotEmpty()) {
                raw = mergeByUid(raw, found)
                // 只有 bounds 一个都没给、完全靠检索时才算「降级」：
                // bounds 给了 8 个、search 补了 1 个，那是补全，不是降级。
                viaSearch = bounded.isEmpty()
            }
        }
        if (raw.isEmpty()) {
            return AreaResult(null, firstError.ifBlank { "no stations nearby" })
        }

        // 中心格若已知就占掉一个位置，外圈只需要 8 个
        val need = if (centerValue >= 0) StationPick.CELLS - 1 else StationPick.CELLS
        val ranked = StationPick.nearest(raw, lat, lon, centerName, need)

        // 逐站重算。中心格复用主数字时不再请求它自己。
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
                recalculated += c.copy(value = -1)   // 保留位置，格子画「–」，不往后挤
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
            radiusKm = 0,                 // 0 = 不限半径；站点模式不再用这个概念
            useWorst = useWorst,
            points = pts,
            observedText = observed,
            kind = AreaKind.STATION,
            noteCode = if (viaSearch) AreaStat.NOTE_VIA_SEARCH else "",
            noteDetail = if (viaSearch) "search" else "",
        ) ?: return AreaResult(null, "only $usable usable cells (need ${AreaStat.MIN_SAMPLES})")
        return AreaResult(area)
    }

    /**
     * `map/bounds` 逐级放大，直到凑够 9 个站。
     *
     * 为什么是「逐级」而不是「一次开到 10°」：一次开大，几十公里外那些站也会被拉进来，
     * 白占候选位、还要多花几次 `feed/@uid`；而城区里 0.3° 往往就够了。
     * 反过来只发一次小框，郊区一个站都框不到 —— 所以要放大。
     *
     * 遇到 `Invalid key` / 参数错就**不再放大**：那是权限问题，放大只会多错几次，
     * 直接把已有结果交出去，交给 [searchStations] 兜底。
     */
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

    /**
     * `map/bounds` 用不了时的兜底：按城市名检索站点。
     *
     * `search` 与 `feed` 是同一档权限（demo Token 对它放行，实测见 README 18.2），
     * 所以「bounds 被拒但 search 可用」这个组合是存在的，值得试一次。
     * 关键词取站名里最后一个不是国家代码的逗号段：`海淀四季青, Beijing` → `Beijing`。
     */
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

    /**
     * `map/bounds` 的 `data[]`：
     * `{"lat":..,"lon":..,"uid":..,"aqi":"45","station":{"name":..,"time":..}}`
     * （字段与顺序依据官方 OpenAPI spec，`latlng` 的四个数依次是 north,west,south,east）。
     *
     * `aqi` 是**字符串**且可能是 `"-"`：那是站点的自报值（2012 断点），这里只用来做
     * 「这个站在不在线」的粗筛，真正入格前每个站都会重新拉一次 `feed/@uid` 按 2024 重算。
     */
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

    /** 站名里最后一个不是国家代码的逗号段。`海淀四季青, Beijing` → `Beijing`。 */
    internal fun cityOf(name: String): String = name
        .split(',')
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .lastOrNull { it.length > 3 || !it.all { c -> c.isUpperCase() } }
        .orEmpty()

    /** 两份候选按 uid 合并（uid 缺失时按站名），保持先到先得。 */
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

    /** 按站点编号取一份 feed 并按 2024 重算。失败不抛，返回 -1 与原因。 */
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

    /**
     * 反算源唯一入口。Token 为空时必须显式报错，不允许「先拿 demo 凑数」这类降级。
     */
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

    /**
     * 一份 `feed` 响应里我们用得上的全部字段。
     *
     * 抽出来是因为它现在有两个消费者：主链路 [parseRecalc] 要整份 [AqiState]，
     * 九宫格探测只关心「哪个站、在哪、值多少」。两边各解析一遍必然写岔
     * （尤其 `aqi` 可能是 `"-"`、`city.geo` 可能缺失这些坑）。
     */
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
        // 部分站点返回 "-" 或 -1，表示无有效读数；不能当 0 显示，也不能拿去反算
        if (reported < 0) throw ApiException("station has no AQI (aqi=$rawAqi)")

        // iaqi.*.v 是「分指数 AQI」，不是原始浓度 µg/m³；全量取出交给 WaqiRecalc 过滤
        val rawSub = mutableMapOf<String, Int>()
        d.optJSONObject("iaqi")?.let { iaqi ->
            val keys = iaqi.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                val v = iaqi.optJSONObject(k)?.optInt("v", -1) ?: -1
                if (v >= 0) rawSub[k] = v
            }
        }

        // 反算失败会抛 RecalcException / IllegalArgumentException，直接冒泡给 Repository
        val r = try {
            WaqiRecalc.compute(rawSub, reported)
        } catch (e: IllegalArgumentException) {
            throw ApiException("WAQI recalc failed: ${e.message}")
        }

        val city = d.optJSONObject("city")
        // city.geo 是 [lat, lon]；缺了就留 NaN，让九宫格把这一点判成「没有坐标可用」
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
            // 只留站点署名。口径说明以前也拼在这里（中文），英文界面会露馅，
            // 现在由 diag.kind = WAQI_RECALC 表达，句子交给渲染端。
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

    /**
     * 把一份 `feed` 响应解析成 [AqiState]。
     *
     * `internal` 是为了让单测直接喂真实响应体（`app/src/test/resources/waqi_feed_demo.json`
     * 是 2026-11-16 从 `feed/geo` 抓的原文），不必联网、也不必在测试里伪造字段名。
     */
    internal fun parseRecalc(j: JSONObject): AqiState {
        val s = sampleOf(j)
        val r = s.recalc
        return AqiState(
            value = r.aqi,
            level = AqiLevel.of(r.aqi),
            dominantPollutant = displayLabel(r.dominant),
            subIndices = r.subIndices.mapKeys { displayLabel(it.key) },
            stationName = s.name,
            // geo feed 不返回站点距离，未知就留 0，渲染时省略「约 x km」，不猜测
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

    /** WAQI 的小写 key → 展示标签，与 OM 链路保持一致。 */
    private fun displayLabel(key: String): String = when (key.lowercase(Locale.US)) {
        "pm25" -> "PM2.5"
        "pm10" -> "PM10"
        "o3" -> "O₃"
        "no2" -> "NO₂"
        "so2" -> "SO₂"
        "co" -> "CO"
        else -> key.uppercase(Locale.US)
    }

    /** 矩形范围枚举站点。参数顺序按官方 spec：lat_north,lng_west,lat_south,lng_east。 */
    private const val MAP_BOUNDS = "https://api.waqi.info/map/bounds/?latlng=%s,%s,%s,%s&token=%s"

    /** 按城市名检索站点，`map/bounds` 不可用时的兜底。 */
    private const val SEARCH = "https://api.waqi.info/search/?keyword=%s&token=%s"

    /** 按站点编号取详情，九宫格要它来逐站按 2024 重算。 */
    private const val FEED_BY_UID = "https://api.waqi.info/feed/@%d/?token=%s"

    /**
     * 逐站重算之间的固定间隔。
     *
     * 免费 Token 的配额细节官方没写死，实测只能确定「并行打会被拒」（v0.4.5 的
     * 4 线程并发就是这么翻车的）。180ms 让 8 个站多花 1.5 秒，换来一轮不被打空。
     */
    private const val FEED_GAP_MS = 180L

    /** 整块周边的总预算。超了就不再发后续请求，缺的格子留空。 */
    private const val AREA_BUDGET_MS = 30_000L

    /**
     * 枚举矩形的半边长（度），从近到远逐级放大。
     * 0.3° ≈ 33 km（城区够用），1.2° ≈ 133 km（郊区 / 城市群），4° ≈ 445 km（站点稀疏地区）。
     * 只放三级：再大就跨越几个省，那些站已经不是「附近」了。
     */
    private val BOX_HALF_DEG = doubleArrayOf(0.30, 1.20, 4.00)
}
