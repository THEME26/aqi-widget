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

/**
 * 只缓存「上一次成功」的结果，失败/占位态绝不覆盖它。
 *
 * v0.1.6 起**按数据源分槽**，不再按「主/副」分槽：
 *  - `state_us` / `state_cn`：[AqiSource.OM] 链路（按标准分开，防国标值当美标显示）；
 *  - `state_waqi_recalc`：[AqiSource.WAQI_RECALC] 链路，独立 tag。
 *
 * 这样「主源」切换**不改缓存、只改读取源**（规格 §5），切源零网络请求即可换值，
 * 也杜绝了「主源是 OM、却把反算值当主数值画出来」这类串口径。
 *
 * 每条缓存写入 [AqiState.sourceTag]，读取时与本槽期望 tag 比对，不一致直接丢弃：
 *  - v0.1.4 的「瞬时浓度代入 24h 断点」虚高值（413）在 v0.1.5 已被丢弃；
 *  - v0.1.5 的 `us_2012_waqi` 旧口径值在 v0.1.6 会被丢弃，强制按 2024 重新拉取。
 */
class AqiCache(ctx: Context) {

    private val sp = ctx.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
    private val prefs = AqiPrefs(ctx)

    /** 按 sourceTag 自动落到对应槽；无数值的失败态直接忽略。 */
    fun save(state: AqiState) {
        if (!state.hasValue) return
        sp.edit().putString(keyFor(state), toJson(state).toString()).apply()
    }

    /** 供 Repository 判定年龄 / 位移去重 / 网络失败兜底。缓存里的 freshness 恒为 STALE。 */
    fun loadRaw(source: AqiSource, standard: AqiStandard): AqiState? = load(source, standard)

    /**
     * 供 UI 直接读缓存：缓存里的 freshness 固定是 STALE（那是给 repository 判定的），
     * 显示时必须按刷新间隔重新算，否则主界面 / 屏保 / 小部件会永远显示「数据陈旧」。
     * 跟随当前主源，切主源无需重新请求网络。
     */
    fun loadForDisplay(
        intervalMinutes: Int,
        standard: AqiStandard,
        tokenBlank: Boolean,
    ): AqiState = loadForSource(primarySource(standard), intervalMinutes, standard, tokenBlank)

    /** 主卡第二行 / 通知展开区对照行。CN 模式是单源，直接返回 null 让 UI 隐藏该行。 */
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

    /** 副源没数值时给用户看的明确原因，如「需填 WAQI Token」。 */
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

    /** 周边数据源：CN 固定 OM；US 跟随主源，用户可单独覆盖（v0.4.5）。 */
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
        // 区域数据要跟着设置走：关掉开关、改了半径、切换了「取最差格点」、
        // 或者换了周边数据源（格点 ↔ 站点），旧缓存里的统计就不再成立，
        // 直接抹掉（卡片退回单层），等下一次刷新填新的
        // —— 绝不拿 25 km 的中位数冒充「10 km 周边」，也绝不拿格点冒充「最近站点」。
        val wantKind = if (areaSource(standard) == AqiSource.WAQI_RECALC) {
            com.example.aqi.core.AreaKind.STATION
        } else {
            com.example.aqi.core.AreaKind.GRID
        }
        // 站点环失败时会退回模式格点，那份 area 的 kind 是 GRID 但语义仍属「周边源 = WAQI」。
        // 用 noteCode 认它，否则每次读缓存都把这块抹掉：列表里一会儿有一会儿没有。
        val kindOk = c.area?.kind == wantKind || c.area?.noteCode == AreaStat.NOTE_STATION_FAILED
        // 站点模式（v0.4.7）不限半径：radiusKm 存的是 0，拿它和设置里的 10/25/50 比会
        // 永远不等 → 每次读缓存都把周边抹掉。格点模式才需要这条校验。
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
            // 槽与内容里的标准标记必须一致，否则视为脏数据丢弃
            if (j.optString("standard", standard.id) != standard.id) return null
            // 断点/数据源版本必须一致；老缓存没有 source 字段 → 丢弃，强制按新口径刷新
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

    /** CN 模式只有 OM + HJ 633-2012 一种口径；US 模式按源取各自 tag。 */
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
                // 坐标用紧凑四元组 [lat, lon, value, distance]，9 个点约 400 字节；
                // 不存键名是为了让 SharedPreferences 里的这条 JSON 尽量短。
                // v0.4.5 起第 5 项是站名（WAQI 才有），OM 格点省略以省体积。
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
                // 数据源种类决定详情文案（「不是监测站」/「站点实测」），必须跟着缓存走
                put("kind", a.kind.name)
                // 退回说明也要跟着走：否则重启后卡片上只剩「9 个格点」，
                // 用户会以为自己选的站点环生效了
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
            // 缓存本身永远不「新鲜」，由 repository / 渲染端按时间重新判定
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

    /** 老缓存没有 diag 键时返回 null，渲染端会退回 diagnostic 字符串（可能带旧语言）。 */
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

    /** 老缓存没有 area 键时返回 null —— 小部件会退回「只有本站」的单层样式，不报错。 */
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
                // 旧缓存没有第 4/5 项 → 距离 0、站名空，详情会退回「这一格在哪」的说法
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
            // 旧缓存（v0.4.1 及以前）只有 value，没有三个统计量：退回成同一个数，
            // 详情页的「最低/中位/最高」会显示成三行相同值，但不会崩、也不会显示 -1。
            median = o.optInt("median", v),
            min = o.optInt("min", v),
            max = o.optInt("max", v),
            // 旧缓存没有坐标 → 图照画，点击不响应（绝不拿 0,0 冒充坐标）
            points = points,
            observedText = o.optString("obs", ""),
            // v0.4.4 及以前只有 OM 一条链路，缺 kind 就是 GRID
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
