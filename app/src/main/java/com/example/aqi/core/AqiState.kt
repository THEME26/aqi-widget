package com.example.aqi.core

/**
 * 所有界面（桌面小部件 / 常驻通知 / 锁屏 / 息屏屏保 / 设置页）只读这一份状态。
 * 渲染端只负责显示，不发网络请求、不自己算等级。
 */
enum class Freshness {
    FRESH,          // 本次刷新成功
    STALE,          // 保留上次数值，但必须标注陈旧
    FAILED,         // 没有任何缓存，请求失败
    NO_LOCATION,    // 定位关闭 / 拿不到位置
    NO_PERMISSION,  // 定位权限被拒
    NO_TOKEN,       // 开启了「WAQI 站点实测」但没填 Token
}

/**
 * 周边九宫格统计（v0.4.5）。
 *
 * **两种数据源，同一套九宫格**（v0.4.5 用户拍板「area 跟随主源，可单独覆盖」）：
 *
 *  - [AreaKind.GRID] —— Open-Meteo 模式格点。中心是你所在格点，外围 8 格是
 *    半径 R 圆周上的 8 个方位（每点距中心恰好 R）。
 *  - [AreaKind.STATION] —— WAQI 地面站点。中心是离你最近的监测站，外围 8 格是
 *    8 个方位扇区里各自最近的那个站（**按真实方位摆放**，不是按距离排名）。
 *
 * ```
 *        N
 *    NW  ·  NE
 *  W  · (你) ·  E      格子的位置 = 地理方位
 *    SW  ·  SE
 *        S
 * ```
 *
 * **为什么这次回到九宫格而不是圆环**：v0.4.2 的圆环只有一个圆心可点，8 个点
 * 挤在一圈上；v0.4.4 加上详情后用户要「看清每个方向到底是谁」。方格能塞下
 * 9 个三位数而不互相压字，也能让「中心 = 最近的一个」这件事一目了然。
 *
 * **取值口径**：默认中位数（回答「这一片大概什么水平」），可切到最大值
 * （回答「这一片最糟的地方什么水平」，保守、符合预防原则）。
 * 均值一律不用：城区里一个工业区/主干道格点就能把均值拉高几十，而中位数
 * 与最大值都能抗住这种单点噪声中的「拉平」效应（最大值是主动选择极端）。
 *
 * **为什么叫「区域」而不是「全城」**：CAMS 全球模式在国境内是 0.1°…0.4°
 * （约 11…45 公里）分辨率，没有行政区概念。我们能给的只是「你周围 R 公里的
 * 模式格点」，UI 上必须写清半径与分辨率，否则用户会以为这是北京市官方均值。
 */
data class AreaStat(
    val value: Int,
    val level: AqiLevel,
    /** 采样半径（公里），UI 上要显示出来。 */
    val radiusKm: Int,
    /** 有效点数，最少 3 个才算数，否则不展示。 */
    val samples: Int,
    /** 9 个点原始值，顺序 = [中心, 北, 东北, 东, 东南, 南, 西南, 西, 西北]；缺失点 -1。 */
    val grid: List<Int> = emptyList(),
    /** [value] 取的是最大值（「取最差格点」开启）还是中位数。 */
    val worst: Boolean = false,
    /** 三个统计量始终都算，详情页要同时展示，卡片只显示 [value]。 */
    val median: Int = value,
    val min: Int = value,
    val max: Int = value,
    /**
     * 9 个点的坐标与取值（v0.4.4）。用于「点格子看详情」：用户点一个格子，
     * 至少要知道它在哪个方向、多远、坐标是多少 —— 即便它不是站点。
     *
     * 为空表示旧缓存（v0.4.3 及之前只存了数值），此时图仍能画，但不响应点击。
     */
    val points: List<AreaPoint> = emptyList(),
    /** 数据时间（Open-Meteo `current.time` / WAQI 站点时间），点击详情里展示。 */
    val observedText: String = "",
    /**
     * 数据来源种类（v0.4.5）。决定文案：
     *  - GRID：Open-Meteo 模式格点，标题写「方位」，详情写「不是监测站」；
     *  - STATION：WAQI 站点，标题写站名，详情写「站点实测 · 2024 重算」。
     *
     * 默认 GRID 是为了让 v0.4.4 及以前的缓存照常读出来（那时只有 OM 一条链路）。
     */
    val kind: AreaKind = AreaKind.GRID,
    /**
     * 这块周边值是不是「退而求其次」来的（v0.4.6，v0.4.7 多一种）。
     *
     *  - [NOTE_STATION_FAILED]：要的是 WAQI 站点，但一个都取不到，退回 Open-Meteo 格点；
     *  - [NOTE_VIA_SEARCH]：站点取到了，但不是按地理范围枚举、而是按城市名检索来的。
     *
     * **必须写出来**：不然卡片上写着「9 个格点」，用户会以为自己选的站点生效了。
     * 空串 = 正常。
     */
    val noteCode: String = "",
    /** 配合 [noteCode] 的技术原因（`HTTP 429` / `Invalid key` …），原样展示，不翻译。 */
    val noteDetail: String = "",
) {
    /** 用户点的是中心还是某个方位。 */
    fun point(index: Int): AreaPoint? = points.getOrNull(index)

    /** 中心那个点的站名（WAQI 才有；OM 恒为空）。 */
    val centerName: String
        get() = points.getOrNull(0)?.name.orEmpty()

    /**
     * 数据时间的「时分」。
     * Open-Meteo 返回 "2026-11-16T07:00"（本地时区），WAQI 返回 "2026-11-16 07:00:00"。
     * 界面只需要时分：日期对每小时刷新的区域值没有意义，ISO 的 T 还会把一行说明撑长。
     */
    val observedHhmm: String
        get() {
            val t = observedText.substringAfter('T', observedText)
            return t.substringAfter(' ', t).take(5)
        }

    companion object {
        const val MIN_SAMPLES = 3

        /**
         * 从原始点值算出统计量。抽成纯函数是为了能脱离网络单测：
         * 中位数 / 最小值 / 最大值三个口径的差异是这次改动的核心，
         * 必须能用固定输入验证，而不是靠每次请求碰运气。
         *
         * @param values 9 个点（含缺失 -1），索引 0 必须是中心
         * @param points 与 [values] 同序的坐标；缺省为空（旧调用方 / 单测只关心统计量）
         */
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

        /** 站点环取不到、退回模式格点。文案键在 strings.xml 的 `grid_note_station_failed`。 */
        const val NOTE_STATION_FAILED = "station_failed"

        /**
         * 站点环取到了，但站点清单不是靠地理范围枚举来的，而是按城市名检索
         * （`map/bounds` 被拒时的兜底）。文案键 `grid_note_via_search`。
         *
         * 为什么还要写出来：此时「最近的 9 个」只在那次检索结果里最近 ——
         * 检索只覆盖名字里带该城市的关键词，边界处的站可能漏掉。
         * 不写就等于承诺了一个做不到的精度。
         */
        const val NOTE_VIA_SEARCH = "via_search"
    }
}

/** 九宫格的数据来源（v0.4.5）。文案与详情说明都靠它区分，不靠「站名是否为空」猜。 */
enum class AreaKind {
    /** Open-Meteo CAMS 模式格点，约 45 公里分辨率，没有站点。 */
    GRID,

    /** WAQI 地面监测站，有站名、站点编号与真实距离。 */
    STATION,
}

/**
 * 一个采样点。
 *
 * - [AreaKind.GRID] 下**不是监测站**：Open-Meteo 只有模式格点，没有站点名/编号，
 *   名字只能由方位推出来（北 / 东北 / …），[name] 恒为空。
 * - [AreaKind.STATION] 下是真实站点，[name] 为 WAQI 返回的站名，[distanceKm] 为
 *   到你的直线距离，[index] 仍是方位索引（1..8 = 北起顺时针），因为它是按方位摆的。
 */
data class AreaPoint(
    /** 0 = 中心，1..8 = 北起顺时针 45° 一步。 */
    val index: Int,
    val value: Int,
    val lat: Double,
    val lon: Double,
    /** 站点名（WAQI 才有）；OM 格点为空。 */
    val name: String = "",
    /** 到用户的直线距离（公里）。OM 格点恒等于采样半径；中心点 0。 */
    val distanceKm: Double = 0.0,
) {
    val isCenter: Boolean get() = index == 0
}

/**
 * 计算口径的结构化描述（v0.4.1）。
 *
 * 之前这里是一段拼好的中文句子（`diagnostic`），存在缓存里直接显示。
 * 问题是英文/日文界面会把那段中文原样显示出来 —— 界面语言不统一，
 * 而缓存里存自然语言也意味着"界面文案"被固化进了数据层。
 * 现在数据层只交数字，句子由渲染端用字符串资源拼。
 */
data class DiagInfo(
    /** 本地重算（OM 原始浓度 → 断点） */
    val kind: Kind = Kind.LOCAL,
    val pollutant: String = "",
    /** 平均窗口小时数；1 表示用瞬时值。 */
    val windowHours: Int = 24,
    /** 参与计算的浓度，未知为 NaN。 */
    val concentration: Double = Double.NaN,
    val localAqi: Int = -1,
    /** Open-Meteo 官方 us_aqi，-1 表示没有对照。 */
    val officialAqi: Int = -1,
    /** 历史缺测、回退瞬时值的污染物项数。 */
    val fellBackCount: Int = 0,
    /** WAQI 反算：站点已发布的 2012 分指数。 */
    val originalIndex: Int = -1,
    /** WAQI 反算：逆推出来的等效 PM2.5 浓度。 */
    val equivalent: Double = Double.NaN,
    /** WAQI 反算：按 2024 断点重算后的值。 */
    val recalcIndex: Int = -1,
    /** WAQI 反算：站点自报的 AQI。 */
    val reportedAqi: Int = -1,
) {
    enum class Kind { LOCAL, WAQI_RECALC }
}

data class AqiState(
    val value: Int,
    val level: AqiLevel,
    val dominantPollutant: String,
    /** 各污染物分指数。注意 WAQI 的 iaqi.*.v 是分指数 AQI，不是原始浓度。 */
    val subIndices: Map<String, Int>,
    val stationName: String,
    /** WAQI geo feed 不返回站点距离，未知时为 0.0，渲染时省略距离。 */
    val distanceKm: Double,
    /** 站点观测时间原样保存（站点本地时间，不做时区换算，避免误标）。 */
    val observedText: String,
    /** 本机上次成功刷新时间。 */
    val fetchedAtMillis: Long,
    val freshness: Freshness,
    val attribution: String,
    /** 这份数值是哪套标准算出来的，切换标准时用于防止把旧口径的值当成新口径显示。 */
    val standard: AqiStandard = AqiStandard.US,
    /** 具体数据源 + 断点版本（us_2024_avg / us_2024_waqi_recalc / cn_hj633_avg）。 */
    val sourceTag: String = "",
    /** 给用户自证的计算口径：主导污染物 + 平均窗口 + 浓度 + 本地 AQI + 官方对照。 */
    val diagnostic: String = "",
    /** 周边区域统计；取不到时为 null，卡片会自动退回单层（不显示区域胶囊）。 */
    val area: AreaStat? = null,
    /**
     * 站点名不是「真实站点」，而是 Open-Meteo 模型网格。
     * 数据层不再写死「Open-Meteo 模型网格」这句中文，改由渲染端本地化。
     */
    val modelGrid: Boolean = false,
    /** 结构化口径；为空时退回 [diagnostic]（旧缓存），再空则不显示诊断行。 */
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
