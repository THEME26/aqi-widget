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

/**
 * Open-Meteo Air Quality API（CAMS 模型网格）。US / CN 两个标准共用同一次请求。
 *
 * 选它的原因：它直接返回原始污染物浓度 µg/m³，而 WAQI 的 iaqi.*.v 是
 * 「已按 2012 旧断点换算好的分指数」，既不能反推国标，也无法按 EPA 2024 重算。
 * 免费、无需 token；数据为 CC BY 4.0，须署名。
 *
 * ## v0.1.5：按断点表规定的平均期取浓度（关键修正）
 * 官方文档明确 current 与 hourly 的污染物都是 `Instant`（瞬时值），
 * 而 EPA / HJ 633-2012 的断点定义的是 24h / 8h / 1h **平均浓度**。
 * v0.1.4 把瞬时峰值直接代入 24h 断点，实测北京 PM2.5 瞬时 347.4 µg/m³
 * 会算成 413，而 24h 平均 205.3 µg/m³ 应为 280（Open-Meteo 官方 us_aqi = 278）。
 * 现在由 [AqiAverager] 先按标准窗口做滑动平均，再交给 calculator；
 * 断点表、插值、单位换算全部未改动。
 *
 * 请求同时要 `past_days=2&forecast_days=1` 的 hourly 序列，以保证任何时刻
 * 都有完整的 24h 历史窗口；并额外取 `us_aqi` 作为官方口径对照值展示给用户。
 */
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

    /**
     * 周边区域请求：**只取 current=us_aqi**，不带 hourly。
     *
     * 为什么不像主请求那样算 24h 平均：9 个格点的 hourly 序列约 240 KB，
     * 每次刷新多打这么多流量，而区域值只用于「概览」——它不参与口罩判定。
     * Open-Meteo 的 us_aqi 本身就是按 24h 平均浓度算的，做概览够用。
     * 代价：区域值与本 App 的本地口径可能有 ±1~3 的差异，App 里已注明。
     *
     * 多坐标用逗号串联，响应是 JSON 数组，顺序与请求一致。
     */
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

        // 把 current.time 对齐到 hourly.time 的下标；找不到就退回最后一个时刻
        val times = hourly?.optJSONArray("time")
        var endIndex = -1
        if (times != null) {
            for (i in 0 until times.length()) {
                if (times.optString(i, "") == observed) endIndex = i
            }
            if (endIndex < 0 && times.length() > 0) endIndex = times.length() - 1
        }
        // 没有 hourly 就不能做 24h 平均：明确报错，绝不静默退回瞬时值（那会虚高到 413）
        if (endIndex < 0) {
            throw ApiException("no hourly series from Open-Meteo, cannot average")
        }

        val series = HashMap<String, DoubleArray>()
        AqiAverager.FIELDS.forEach { f ->
            series[f] = toDoubles(hourly?.optJSONArray(f))
        }
        val current = AqiAverager.FIELDS.associate { f -> f to cur.optDouble(f, Double.NaN) }
        val avg = AqiAverager.build(series, current, endIndex, standard)

        // 官方 us_aqi 只作对照展示，不参与本 App 的数值计算（本 App 坚持本地按断点算）
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

    /**
     * 取周边区域统计。失败一律返回 null（卡片退回单层），绝不抛给主链路。
     *
     * 9 个点 = 中心 + 半径 R 圆周上的 8 个方位（北起、顺时针 45° 一步）。
     * v0.3.0 用的是 3×3 方阵，对角顶点在 1.41R 处，「周边 10 公里」其实是
     * 20×20 公里的方形；取最大值时更是被最远的四个角点主导。改成圆周后，
     * 每个点到中心的距离都严格等于设置里的半径。
     *
     * 中心点用主请求的本地值覆盖，保证「九宫格正中的数字 == 卡片主数字」，
     * 否则用户会在「今日」页看到两个不一样的数。
     *
     * @param useWorst true 取 9 点最大值（保守口径），false 取中位数
     */
    fun fetchArea(
        lat: Double,
        lon: Double,
        radiusKm: Int,
        standard: AqiStandard,
        centerValue: Int,
        useWorst: Boolean = false,
    ): AreaStat? {
        // CN 标准下 us_aqi 不适用，而 8 点 hourly 请求体太大 —— 先不支持，返回 null
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
            // 9 个点的 current.time 是同一时刻，取第一个非空的即可
            if (observed.isBlank()) observed = cur.optString("time", "")
        }
        if (values.size < AreaStat.MIN_SAMPLES) return null
        // 中心点（索引 0）与主数字对齐；取不到主值时保留官方值
        if (centerValue >= 0 && values.isNotEmpty()) values[0] = centerValue

        // 坐标必须和值一一对应，点击详情要显示「这一点到底在哪」。
        // 距离恒等于采样半径：这是圆周采样，不是「最近的 8 个」。
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

    /**
     * 中心 + 半径 R 圆周上的 8 个方位点，北起顺时针，索引 0 是中心。
     *
     * 纬度每度 111 km；经度每度 111·cos(lat) km。cos 下限 0.05 避免极区除零。
     */
    private fun ringPoints(lat: Double, lon: Double, radiusKm: Int): List<Pair<Double, Double>> {
        val cosLat = kotlin.math.cos(Math.toRadians(lat)).coerceAtLeast(0.05)
        val out = ArrayList<Pair<Double, Double>>(9)
        out += lat to lon
        for (i in 0 until 8) {
            val bearing = Math.toRadians(i * 45.0)   // 0° = 正北，顺时针
            val dLat = kotlin.math.cos(bearing) * radiusKm
            val dLon = kotlin.math.sin(bearing) * radiusKm
            out += (lat + dLat / 111.0) to (lon + dLon / (111.0 * cosLat))
        }
        return out
    }

    /**
     * 口径的结构化版本（v0.4.1）。
     *
     * 这里以前拼的是一整句中文（"计算口径：PM2.5 24h 平均 58.0 µg/m³ → AQI 152"），
     * 存进缓存后原样显示 —— 英文界面会突然冒出一句中文。现在只交数字，
     * 句子在 UiBinder / AqiNotifier 里按当前语言拼。
     */
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

    /** EPA 2024：PM2.5 用 9.0 / 125.4 / 225.5 新断点，浓度按 24h/8h/1h 平均。 */
    private fun parseUs(avg: AqiAverager.Result, observed: String, officialUsAqi: Int): AqiState {
        val r = UsAqiCalculator.compute(avg.raw)
        if (r.aqi < 0) throw ApiException("no pollutant concentrations in this grid cell")
        return AqiState(
            value = r.aqi,
            level = AqiLevel.of(r.aqi),
            dominantPollutant = r.dominant,
            subIndices = r.subIndices,
            // 站点名不在这里写死中文：模型网格是一种「不是站点的站点」，交给渲染端本地化
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
