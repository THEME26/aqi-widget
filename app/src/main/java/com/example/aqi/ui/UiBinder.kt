package com.example.aqi.ui

import android.content.Context
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.example.aqi.R
import com.example.aqi.core.AqiFormat
import com.example.aqi.core.AqiLevel
import com.example.aqi.core.AqiSource
import com.example.aqi.core.AqiState
import com.example.aqi.core.MaskTier
import com.example.aqi.render.BgPainter
import com.example.aqi.render.GaugePainter

/**
 * 「今日」「详情」两屏的纯渲染逻辑。
 *
 * 为什么从 Activity 里拆出来：这两个函数**只需要一个 View 根节点**，
 * 不碰权限、不碰 prefs、不发请求。于是可以在 Robolectric 里
 * inflate 一份布局 → 塞一份假的 [AqiState] → 直接 draw 成 PNG，
 * 用来验证「文字有没有被裁、格子和环对不对得上」。
 * 如果绑定逻辑长在 Activity 里，就必须起一个带 WorkManager 的 Application 才能测。
 */
class UiBinder(private val ctx: Context) {

    private val density = ctx.resources.displayMetrics.density
    private val dark: Boolean =
        (ctx.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES
    private val palette = GaugePainter.palette(ctx)

    // ─────────────────────────── 今日 ───────────────────────────

    /**
     * @param areaEnabled 用户是否开启了周边区域。关掉时不展示 9 格与对比条，
     *                    但**不会**拿本站值去冒充区域值（那是最坏的一种「看起来正常」）。
     */
    fun bindToday(root: View, s: AqiState, areaEnabled: Boolean) {
        val area = if (areaEnabled) s.area else null
        val level = if (s.hasValue) s.level else null
        val siteInk = level?.textColorInt(ctx) ?: textColor(R.color.text_dim)
        val areaInk = area?.level?.textColorInt(ctx) ?: siteInk

        // ── 位置行 ──
        val station = stationOf(s)
        root.findViewById<TextView>(R.id.nav_city).text = station

        val sub = buildList {
            if (s.distanceKm > 0.05) add(ctx.getString(R.string.station_dist, s.distanceKm))
            if (s.fetchedAtMillis > 0) add(ctx.getString(R.string.widget_time, AqiFormat.time(s.fetchedAtMillis)))
            when {
                !s.hasValue -> add(AqiFormat.freshnessLabel(s.freshness, ctx))
                s.isStale -> add(ctx.getString(R.string.fresh_stale))
            }
        }.joinToString("  ·  ")
        root.findViewById<TextView>(R.id.nav_sub).text = sub

        // ── Hero ──
        val heroBg = root.findViewById<ImageView>(R.id.hero_bg)
        val topColor = area?.let { GaugePainter.ringColor(it.level) } ?: (level?.let { GaugePainter.ringColor(it) } ?: Color.GRAY)
        val bottomColor = level?.let { GaugePainter.ringColor(it) } ?: Color.GRAY
        // App 内的卡片是不透明的 #151C28，不像桌面上那样浮在壁纸上。
        // 直接套小部件的 0.130/0.170 会让深色模式下整张卡变成一块暗红，
        // 「顶端是区域、底端是本站」的层次反而被压掉了 → 这里单独压到 0.100/0.135。
        val (ta, ba) = if (dark) 0.100f to 0.135f else BgPainter.alphaOf(false)
        heroBg.setImageBitmap(BgPainter.strip(topColor, bottomColor, ta, ba))

        val valueText = root.findViewById<TextView>(R.id.hero_value)
        valueText.text = AqiFormat.valueOrDash(s.value)
        valueText.setTextColor(siteInk)

        val gaugePx = dp(96)
        root.findViewById<ImageView>(R.id.hero_gauge).setImageBitmap(
            GaugePainter.ring(gaugePx, if (s.hasValue) s.value else -1, palette, dark),
        )

        val tier = if (s.hasValue) MaskTier.of(s.value) else null
        val action = root.findViewById<TextView>(R.id.hero_action)
        val advice = root.findViewById<TextView>(R.id.hero_advice)
        if (tier != null) {
            action.text = ctx.getString(tier.labelRes)
            action.setTextColor(siteInk)
            advice.text = ctx.getString(tier.adviceRes)
        } else {
            action.text = ctx.getString(R.string.status_no_data)
            action.setTextColor(textColor(R.color.text_secondary))
            // 不再写「原因见下方」：提示条在 hero 之上，说「下方」是错的。
            // 直接把具体原因（缺权限 / 无位置 / 无 Token）放在这里，与顶部副标题一致。
            advice.text = AqiFormat.freshnessLabel(s.freshness, ctx)
        }

        // ── 两条对比 ──
        val rowArea = root.findViewById<View>(R.id.row_area)
        val barArea = root.findViewById<ValueBarView>(R.id.bar_area)
        val valArea = root.findViewById<TextView>(R.id.val_area)
        val barSite = root.findViewById<ValueBarView>(R.id.bar_site)
        val valSite = root.findViewById<TextView>(R.id.val_site)
        val diff = root.findViewById<TextView>(R.id.hero_diff)

        barSite.set(barFraction(s.value), siteInk, 1f, if (dark) 0.13f else 0.13f)
        valSite.text = AqiFormat.valueOrDash(s.value)
        valSite.setTextColor(siteInk)

        if (area != null) {
            rowArea.visibility = View.VISIBLE
            // 口径要在标签上写清楚：最差口径下还写「周边」，用户会把这个数当成平均值。
            root.findViewById<TextView>(R.id.lbl_area).text = ctx.getString(
                if (area.worst) R.string.widget_area_worst else R.string.widget_area,
            )
            barArea.set(barFraction(area.value), areaInk, 0.55f, if (dark) 0.13f else 0.13f)
            valArea.text = area.value.toString()
            valArea.setTextColor(areaInk)
            diff.text = when {
                area.value > s.value + 3 -> ctx.getString(R.string.today_diff_high, area.value - s.value)
                s.value > area.value + 3 -> ctx.getString(R.string.today_diff_low, s.value - area.value)
                else -> ctx.getString(R.string.today_diff_same)
            }
            diff.visibility = View.VISIBLE
        } else {
            rowArea.visibility = View.GONE
            diff.text = ctx.getString(
                if (areaEnabled) R.string.today_no_area_data else R.string.today_no_area,
            )
            diff.visibility = View.VISIBLE
        }

        // ── 周边九宫格（正中 = 你 / 最近站点，外圈 = 8 个方位）──
        val gridCard = root.findViewById<View>(R.id.grid_card)
        val grid = if (area != null) area.grid else emptyList()
        if (area != null && grid.size == 9) {
            gridCard.visibility = View.VISIBLE
            root.findViewById<AreaGridView>(R.id.area_grid).setValues(grid)
            // 两种数据源的标题必须能一眼分开：一个是「格点」，一个是「站点」。
            // 用户看到「9 个站点」才知道可以点开看站名；写成「9 个格点」时点开只有坐标。
            val stationMode = area.kind == com.example.aqi.core.AreaKind.STATION
            // 站点模式不写半径：那一栏根本没有半径这个概念（取的是最近的 9 个）
            root.findViewById<TextView>(R.id.grid_title).text =
                if (stationMode) {
                    ctx.getString(R.string.grid_title_station, area.samples)
                } else {
                    ctx.getString(R.string.grid_title, area.radiusKm, area.samples)
                }
            // 右上角写口径而不是重复中位数：三个数图例里都有了，这里只需要说清
            // 卡片主数字取的是哪一个。颜色用弱色 —— 它是个标签，不是读数，
            // 染成档色会让「中位数」三个字读起来像警报。
            root.findViewById<TextView>(R.id.grid_median).apply {
                text = ctx.getString(
                    if (area.worst) R.string.grid_mode_worst else R.string.grid_mode_med,
                )
                setTextColor(textColor(R.color.text_dim))
            }
            // 模式场时间 + 可点击提示。旧缓存没有 observedText，那就只说「可以点」。
            // 站点模式的格子位置是**距离排名**，不是方位。这句话必须写出来：
            // 图上看不出差别，不说的话用户会拿「东北角」当东北方向看。
            root.findViewById<TextView>(R.id.grid_hint).text =
                if (stationMode) {
                    ctx.getString(R.string.grid_hint_rank)
                } else if (area.observedText.isNotBlank()) {
                    ctx.getString(R.string.grid_hint, area.observedHhmm)
                } else {
                    ctx.getString(R.string.grid_hint_none)
                }
            // 退回说明（v0.4.6）：站点环取不到时这里会写清原因，别让用户以为站点环生效了
            root.findViewById<TextView>(R.id.grid_note).apply {
                when (area.noteCode) {
                    com.example.aqi.core.AreaStat.NOTE_STATION_FAILED -> {
                        text = ctx.getString(
                            R.string.grid_note_station_failed,
                            area.noteDetail.ifBlank { "--" },
                        )
                        visibility = View.VISIBLE
                    }
                    // 站点是取到了，但不是按地理范围枚举的（map/bounds 被拒，退到按城市名检索）
                    com.example.aqi.core.AreaStat.NOTE_VIA_SEARCH -> {
                        text = ctx.getString(R.string.grid_note_via_search)
                        visibility = View.VISIBLE
                    }
                    else -> {
                        text = ""
                        visibility = View.GONE
                    }
                }
            }
            statText(root, R.id.stat_max_value, area.max)
            statText(root, R.id.stat_med_value, area.median)
            statText(root, R.id.stat_min_value, area.min)
        } else {
            gridCard.visibility = View.GONE
        }

        // ── 污染物分指数 ──
        val pollRow = root.findViewById<LinearLayout>(R.id.poll_row)
        val pollCard = root.findViewById<View>(R.id.poll_card)
        val polls = canonicalPolls(s)
        pollCard.visibility = if (polls.isEmpty()) View.GONE else View.VISIBLE
        root.findViewById<TextView>(R.id.poll_sub).text =
            if (s.dominantPollutant.isNotBlank())
                ctx.getString(R.string.poll_sub, displayPoll(s.dominantPollutant))
            else ""
        fillPollutants(pollRow, polls, siteInk, s.dominantPollutant)

        // 页脚：更新时间 + 标准 + 数据源品牌。
        // 不拼完整的 attribution 长句 —— 那句有 60 多个字，会把页脚撑成三行，
        // 中文界面上还会出现「数据来源：Open-Meteo / CAMS 美标 2024 断点 · 24 小时…」的重复。
        root.findViewById<TextView>(R.id.foot_line).text = buildList {
            if (s.fetchedAtMillis > 0) add(ctx.getString(R.string.widget_time, AqiFormat.time(s.fetchedAtMillis)))
            // 用短名：全名带「（本地计算）」，英文 "US AQI · EPA 2024 (computed locally)"
            // 加上品牌会折成两行，末行只剩一个 "CAMS"。
            add(s.standard.shortLabel(ctx))
            add(
                s.attribution.ifBlank {
                    if (s.sourceTag == AqiSource.WAQI_RECALC.tag) "WAQI / aqicn"
                    else "Open-Meteo / CAMS"
                }
            )
        }.joinToString("  ·  ")
    }

    /**
     * 显示用站点名。
     *
     * 模型网格没有站点名，数据层留空并置 modelGrid —— 这里翻译成当前语言，
     * 而不是让数据层写死一句「Open-Meteo 模型网格」（那会在英文界面里露馅）。
     */
    private fun stationOf(s: AqiState): String = when {
        s.stationName.isNotBlank() -> s.stationName
        s.modelGrid -> ctx.getString(R.string.station_model_grid)
        else -> ctx.getString(R.string.widget_no_station)
    }

    /**
     * 图例里的一个统计量：数字按它自己所属的档上色。
     *
     * 「最高」和主数字同档时不能只靠颜色区分，所以最高那一行的标签是
     * 「最高」而不是「最差」—— 只有开关打开、主数字真的取了最大值时，
     * 右上角才会写「最差格点」，两处用词不混。
     */
    private fun statText(root: View, id: Int, value: Int) {
        root.findViewById<TextView>(id).apply {
            text = if (value >= 0) value.toString() else "–"
            setTextColor(
                if (value >= 0) AqiLevel.of(value).textColorInt(ctx)
                else textColor(R.color.text_dim),
            )
        }
    }

    /** 污染物小方块：数值最大的一项（主导污染物）用档色，其余用次级文字色。 */
    /**
     * 固定按 PM2.5 / PM10 / O₃ / NO₂ 排列，**不按数值大小排序**。
     * 排序会让「左上角那个」看起来像主导污染物（第一版截图里 PM10 82 被排到首位、
     * 却被标成 Dominant PM2.5，是明显的误导）。主导靠颜色高亮表达，靠顺序表达是错的。
     */
    private fun canonicalPolls(s: AqiState): List<Map.Entry<String, Int>> {
        val order = listOf("PM2.5", "PM10", "O3", "O₃", "NO2", "NO₂", "SO2", "SO₂", "CO")
        val byName = s.subIndices.entries.associateBy { normalizePoll(it.key) }
        val out = mutableListOf<Map.Entry<String, Int>>()
        order.map { normalizePoll(it) }.distinct().forEach { key ->
            byName[key]?.let { out.add(it) }
        }
        s.subIndices.entries.forEach { if (out.none { e -> e.key == it.key }) out.add(it) }
        return out.take(4)
    }

    /**
     * 显示用化学式：数据层的 key 是 O3 / NO2 / SO2（URL 与 JSON 里只能这么写），
     * 卡片上要画成 O₃ / NO₂ / SO₂。这个不随语言变，中英文都一样。
     */
    private fun displayPoll(name: String): String = when (normalizePoll(name)) {
        "PM25", "PM2.5" -> "PM2.5"
        "PM10" -> "PM10"
        "O3" -> "O₃"
        "NO2" -> "NO₂"
        "SO2" -> "SO₂"
        "CO" -> "CO"
        else -> name
    }

    private fun normalizePoll(name: String): String =
        name.uppercase().replace("₃", "3").replace("₂", "2").replace(".", "").trim()

    private fun fillPollutants(
        box: LinearLayout,
        items: List<Map.Entry<String, Int>>,
        ink: Int,
        dominant: String,
    ) {
        box.removeAllViews()
        if (items.isEmpty()) {
            box.addView(TextView(ctx).apply {
                text = ctx.getString(R.string.poll_empty)
                setTextColor(textColor(R.color.text_dim))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            })
            return
        }
        items.forEachIndexed { i, entry ->
            val name = entry.key
            val value = entry.value
            val col = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                background = rounded(R.color.surface_2, 13f)
                setPadding(dp(8), dp(9), dp(8), dp(9))
            }
            // 子项必须显式给 WRAP_CONTENT：LinearLayout 竖向布局的默认 LayoutParams
            // 是 MATCH_PARENT × WRAP_CONTENT，宽度被撑满后父级的 gravity=CENTER 就不起作用了，
            // 文字于是靠在自己的左边 —— 用户截图里「PM2.5 / 183 贴在方块最左」就是这个原因。
            col.addView(
                TextView(ctx).apply {
                    text = displayPoll(name)
                    gravity = Gravity.CENTER
                    setTextColor(textColor(R.color.text_dim))
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
                    maxLines = 1
                },
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ),
            )
            col.addView(
                TextView(ctx).apply {
                    text = value.toString()
                    gravity = Gravity.CENTER
                    setTextColor(
                        if (normalizePoll(name) == normalizePoll(dominant)) ink
                        else textColor(R.color.text_secondary)
                    )
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
                    setTypeface(typeface, Typeface.BOLD)
                    setPadding(0, dp(2), 0, 0)
                },
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ),
            )
            box.addView(col, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                if (i > 0) marginStart = dp(8)
            })
        }
    }

    // ─────────────────────────── 详情 ───────────────────────────

    fun bindDetail(root: View, s: AqiState) {
        val area = s.area
        val level = if (s.hasValue) s.level else null
        val siteInk = level?.textColorInt(ctx) ?: textColor(R.color.text_dim)
        val areaInk = area?.level?.textColorInt(ctx) ?: siteInk

        root.findViewById<TextView>(R.id.detail_sub).text = ctx.getString(
            R.string.detail_sub,
            s.standard.fullLabel(ctx),
            AqiFormat.freshnessLabel(s.freshness, ctx),
        )

        root.findViewById<ImageView>(R.id.detail_gauge).setImageBitmap(
            GaugePainter.ring(dp(176), if (s.hasValue) s.value else -1, palette, dark),
        )
        // 环内读数：和今日页的小圈同一套排版，只是字号按 176dp 放大
        root.findViewById<TextView>(R.id.detail_value).apply {
            text = AqiFormat.valueOrDash(s.value)
            setTextColor(siteInk)
        }

        val tier = if (s.hasValue) MaskTier.of(s.value) else null
        root.findViewById<TextView>(R.id.detail_level).apply {
            text = if (tier != null && level != null) {
                ctx.getString(R.string.detail_level_line, level.label(ctx, s.standard), ctx.getString(tier.labelRes))
            } else ctx.getString(R.string.status_no_data)
            setTextColor(if (level != null) siteInk else textColor(R.color.text_secondary))
        }

        val (idx, frac) = GaugePainter.segmentOf(s.value.coerceAtLeast(0))
        root.findViewById<TextView>(R.id.detail_cell).text =
            if (s.hasValue) ctx.getString(R.string.detail_cell, idx + 1, Math.round(frac * 100))
            else ctx.getString(R.string.detail_cell_empty)

        root.findViewById<TierRulerView>(R.id.detail_ruler).setPointers(
            if (s.hasValue) s.value else -1,
            siteInk,
            area?.value,
            areaInk,
        )

        // 图例：色点用档色，数字跟随
        val dot = root.findViewById<TextView>(R.id.legend_site_dot)
        dot.backgroundTintList = android.content.res.ColorStateList.valueOf(siteInk)
        root.findViewById<TextView>(R.id.legend_site).text =
            if (s.hasValue) ctx.getString(R.string.legend_site, s.value) else ctx.getString(R.string.label_site)
        val legendAreaRow = root.findViewById<View>(R.id.legend_area_row)
        if (area != null) {
            legendAreaRow.visibility = View.VISIBLE
            root.findViewById<TextView>(R.id.legend_area).text =
                ctx.getString(R.string.legend_area, area.value)
        } else {
            legendAreaRow.visibility = View.GONE
        }

        fillTiers(root.findViewById(R.id.tiers_container), s, siteInk)

        val body = if (s.hasValue) {
            ctx.getString(
                R.string.detail_calc_body,
                displayPoll(s.dominantPollutant.ifBlank { "—" }),
                // 用短名：全名 "美标 AQI 2024（本地计算）" 塞进「按 … 断点计算」里会变成
                // 「按 美标 AQI 2024（本地计算） 断点计算得 152」，英文更是 computed with … (computed locally) breakpoints
                s.standard.shortLabel(ctx),
                s.value,
            )
        } else {
            ctx.getString(R.string.status_no_data_hint)
        }
        val areaLine = if (area != null) {
            ctx.getString(
                when {
                    area.kind == com.example.aqi.core.AreaKind.STATION && area.worst ->
                        R.string.detail_calc_ring_station_worst
                    area.kind == com.example.aqi.core.AreaKind.STATION ->
                        R.string.detail_calc_ring_station
                    area.worst -> R.string.detail_calc_area_worst
                    else -> R.string.detail_calc_area
                },
                area.radiusKm, area.samples, area.value, area.min, area.max,
            ) + "\n" + ctx.getString(
                // 站点环不该说「模式分辨率 45 公里」—— 那是格点的说法。
                // 站点环的注意点是「选址差异大、邻站差 20 很正常」。
                if (area.kind == com.example.aqi.core.AreaKind.STATION) {
                    R.string.detail_calc_res_station
                } else {
                    R.string.detail_calc_res
                },
            )
        } else {
            ctx.getString(R.string.detail_calc_no_area)
        }
        root.findViewById<TextView>(R.id.detail_calc).text = "$body\n$areaLine"

        val diag = root.findViewById<TextView>(R.id.detail_diag)
        val diagText = AqiFormat.diag(s, ctx)
        diag.text = diagText
        diag.visibility = if (diagText.isBlank()) View.GONE else View.VISIBLE

        root.findViewById<TextView>(R.id.detail_notice).text = ctx.getString(R.string.detail_notice)
    }

    /**
     * 六档含义表：当前档加粗 + 主文字色，其余压暗。
     *
     * 列结构是「色点 / 名称+动作 / 区间」三列，**不是**设计稿里的四列。
     * 四列在英文下 "Wear a mask" 需要约 76dp，而 411dp 宽的机器上留给它的只有 58dp，
     * 结果就是换行成两行、行高参差（第一版截图里能看到 0–50Go out 挤在一起）。
     * 把动作词挪到名称下面一行，任何语种都不会挤。
     */
    private fun fillTiers(box: LinearLayout, s: AqiState, ink: Int) {
        box.removeAllViews()
        val bounds = intArrayOf(0, 50, 100, 150, 200, 300, 500)
        val current = if (s.hasValue) GaugePainter.segmentOf(s.value).first else -1
        AqiLevel.values().forEachIndexed { i, lv ->
            val on = i == current
            val row = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(8), 0, dp(8))
                if (i > 0) background = topHairline()
            }
            row.addView(View(ctx).apply {
                background = roundedColor(lv.colorInt(), 99f)
            }, LinearLayout.LayoutParams(dp(8), dp(8)).apply { marginEnd = dp(10) })

            val col = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
            col.addView(TextView(ctx).apply {
                text = lv.label(ctx, s.standard)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
                setTextColor(if (on) textColor(R.color.text_primary) else textColor(R.color.text_secondary))
                if (on) setTypeface(typeface, Typeface.BOLD)
            })
            col.addView(TextView(ctx).apply {
                text = ctx.getString(tierOf(lv).labelRes)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 10.5f)
                setTextColor(if (on) ink else textColor(R.color.text_dim))
                setPadding(0, dp(1), 0, 0)
            })
            row.addView(col, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))

            row.addView(TextView(ctx).apply {
                text = ctx.getString(R.string.tier_range, bounds[i], bounds[i + 1])
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
                setTextColor(textColor(R.color.text_dim))
                maxLines = 1
                gravity = Gravity.END
            })

            box.addView(row)
        }
    }

    // ─────────────────────────── 工具 ───────────────────────────

    /**
     * 对比条长度：以 **300** 为满格，而不是 500。
     * 500 会把常见的 50–150 压成一小截，条就失去「相对大小」的意义；
     * 300 是「非常不健康」的上界，超过它条本就该到底。数字本身始终在旁边写着。
     */
    private fun barFraction(v: Int): Float = (v.coerceAtLeast(0) / 300f).coerceIn(0f, 1f)

    private fun dp(v: Int): Int = (v * density + 0.5f).toInt()

    private fun textColor(res: Int): Int = ContextCompat.getColor(ctx, res)

    private fun rounded(colorRes: Int, radiusDp: Float): GradientDrawable =
        roundedColor(textColor(colorRes), radiusDp)

    private fun roundedColor(color: Int, radiusDp: Float): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = radiusDp * density
            setColor(color)
        }

    /** 档位 → 出行三档：0–100 可出门 / 101–200 需口罩 / ≥201 不应出门（与 MaskTier.of 同一套切点）。 */
    private fun tierOf(level: AqiLevel): MaskTier = when (level) {
        AqiLevel.GOOD, AqiLevel.MODERATE -> MaskTier.GO_OUT
        AqiLevel.USG, AqiLevel.UNHEALTHY -> MaskTier.MASK
        AqiLevel.VERY_UNHEALTHY, AqiLevel.HAZARDOUS -> MaskTier.STAY_IN
    }

    private fun withAlpha(c: Int, a: Float): Int =
        Color.argb((a * 255).toInt().coerceIn(0, 255), Color.red(c), Color.green(c), Color.blue(c))

    private fun topHairline(): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        setColor(Color.TRANSPARENT)
        setStroke(dp(1), textColor(R.color.divider))
    }
}
