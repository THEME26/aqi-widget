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

class UiBinder(private val ctx: Context) {

    private val density = ctx.resources.displayMetrics.density
    private val dark: Boolean =
        (ctx.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES
    private val palette = GaugePainter.palette(ctx)

    fun bindToday(root: View, s: AqiState, areaEnabled: Boolean) {
        val area = if (areaEnabled) s.area else null
        val level = if (s.hasValue) s.level else null
        val siteInk = level?.textColorInt(ctx) ?: textColor(R.color.text_dim)
        val areaInk = area?.level?.textColorInt(ctx) ?: siteInk

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
            advice.text = AqiFormat.freshnessLabel(s.freshness, ctx)
        }

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

        val gridCard = root.findViewById<View>(R.id.grid_card)
        val grid = if (area != null) area.grid else emptyList()
        if (area != null && grid.size == 9) {
            gridCard.visibility = View.VISIBLE
            root.findViewById<AreaGridView>(R.id.area_grid).setValues(grid)
            val stationMode = area.kind == com.example.aqi.core.AreaKind.STATION
            root.findViewById<TextView>(R.id.grid_title).text =
                if (stationMode) {
                    ctx.getString(R.string.grid_title_station, area.samples)
                } else {
                    ctx.getString(R.string.grid_title, area.radiusKm, area.samples)
                }
            root.findViewById<TextView>(R.id.grid_median).apply {
                text = ctx.getString(
                    if (area.worst) R.string.grid_mode_worst else R.string.grid_mode_med,
                )
                setTextColor(textColor(R.color.text_dim))
            }
            root.findViewById<TextView>(R.id.grid_hint).text =
                if (stationMode) {
                    ctx.getString(R.string.grid_hint_rank)
                } else if (area.observedText.isNotBlank()) {
                    ctx.getString(R.string.grid_hint, area.observedHhmm)
                } else {
                    ctx.getString(R.string.grid_hint_none)
                }
            root.findViewById<TextView>(R.id.grid_note).apply {
                when (area.noteCode) {
                    com.example.aqi.core.AreaStat.NOTE_STATION_FAILED -> {
                        text = ctx.getString(
                            R.string.grid_note_station_failed,
                            area.noteDetail.ifBlank { "--" },
                        )
                        visibility = View.VISIBLE
                    }
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

        val pollRow = root.findViewById<LinearLayout>(R.id.poll_row)
        val pollCard = root.findViewById<View>(R.id.poll_card)
        val polls = canonicalPolls(s)
        pollCard.visibility = if (polls.isEmpty()) View.GONE else View.VISIBLE
        root.findViewById<TextView>(R.id.poll_sub).text =
            if (s.dominantPollutant.isNotBlank())
                ctx.getString(R.string.poll_sub, displayPoll(s.dominantPollutant))
            else ""
        fillPollutants(pollRow, polls, siteInk, s.dominantPollutant)

        root.findViewById<TextView>(R.id.foot_line).text = buildList {
            if (s.fetchedAtMillis > 0) add(ctx.getString(R.string.widget_time, AqiFormat.time(s.fetchedAtMillis)))
            add(s.standard.shortLabel(ctx))
            add(
                s.attribution.ifBlank {
                    if (s.sourceTag == AqiSource.WAQI_RECALC.tag) "WAQI / aqicn"
                    else "Open-Meteo / CAMS"
                }
            )
        }.joinToString("  ·  ")
    }

    private fun stationOf(s: AqiState): String = when {
        s.stationName.isNotBlank() -> s.stationName
        s.modelGrid -> ctx.getString(R.string.station_model_grid)
        else -> ctx.getString(R.string.widget_no_station)
    }

    private fun statText(root: View, id: Int, value: Int) {
        root.findViewById<TextView>(id).apply {
            text = if (value >= 0) value.toString() else "–"
            setTextColor(
                if (value >= 0) AqiLevel.of(value).textColorInt(ctx)
                else textColor(R.color.text_dim),
            )
        }
    }

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
