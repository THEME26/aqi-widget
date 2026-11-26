package io.github.theme26.aqibar.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.view.View
import android.widget.RemoteViews
import androidx.core.content.ContextCompat
import io.github.theme26.aqibar.R
import io.github.theme26.aqibar.core.AqiFormat
import io.github.theme26.aqibar.core.AqiState
import io.github.theme26.aqibar.core.MaskTier
import io.github.theme26.aqibar.data.AqiCache
import io.github.theme26.aqibar.data.AqiPrefs
import io.github.theme26.aqibar.render.BgPainter
import io.github.theme26.aqibar.render.GaugePainter
import io.github.theme26.aqibar.ui.MainActivity
import io.github.theme26.aqibar.worker.RefreshScheduler

enum class WidgetKind(val layoutRes: Int) {
    MAIN(R.layout.widget_main),
    COMPACT(R.layout.widget_compact),
    MINI_TALL(R.layout.widget_mini_tall),
    MINI_SQUARE(R.layout.widget_mini_square),
}

abstract class BaseAqiWidgetProvider : AppWidgetProvider() {
    abstract val kind: WidgetKind

    override fun onUpdate(context: Context, mgr: AppWidgetManager, appWidgetIds: IntArray) {
        RefreshScheduler.ensurePeriodic(context)
        val p = AqiPrefs(context)
        val state = AqiCache(context).loadForDisplay(p.intervalMinutes, p.standard, p.token.isBlank())
        val rv = WidgetRenderer.render(context, kind, state)
        appWidgetIds.forEach { mgr.updateAppWidget(it, rv) }
        if (!state.hasValue || state.isStale) RefreshScheduler.selfHeal(context)
    }

    override fun onEnabled(context: Context) = RefreshScheduler.ensurePeriodic(context)
}

class AqiWidgetMain : BaseAqiWidgetProvider() {
    override val kind = WidgetKind.MAIN
}

class AqiWidgetCompact : BaseAqiWidgetProvider() {
    override val kind = WidgetKind.COMPACT
}

class AqiWidgetMiniTall : BaseAqiWidgetProvider() {
    override val kind = WidgetKind.MINI_TALL
}

class AqiWidgetMiniSquare : BaseAqiWidgetProvider() {
    override val kind = WidgetKind.MINI_SQUARE
}

object WidgetRenderer {

    fun render(ctx: Context, kind: WidgetKind, s: AqiState): RemoteViews {
        val rv = RemoteViews(ctx.packageName, kind.layoutRes)
        val dark = isNight(ctx)
        val dim = s.hasValue && s.isStale
        val palette = if (dim) GaugePainter.mutedPalette(ctx) else GaugePainter.palette(ctx)
        val neutral = ContextCompat.getColor(ctx, R.color.text_secondary)
        val mutedInk = ContextCompat.getColor(ctx, R.color.text_secondary)
        val mutedSoft = ContextCompat.getColor(ctx, R.color.text_dim)
        val area = if (s.hasValue) s.area else null

        val (topA, botA) = BgPainter.alphaOf(dark)
        val topColor = when {
            dim -> mutedSoft
            else -> area?.level?.let { GaugePainter.ringColor(it) } ?: GaugePainter.ringColor(s.level)
        }
        val botColor = if (dim) mutedSoft else GaugePainter.ringColor(s.level)
        rv.setImageViewBitmap(R.id.aqi_bg, BgPainter.strip(topColor, botColor, topA, botA))

        if (kind != WidgetKind.MINI_SQUARE) {
            if (area != null) {
                rv.setViewVisibility(R.id.aqi_area_row, View.VISIBLE)
                rv.setTextViewText(R.id.aqi_area_value, area.value.toString())
                rv.setTextColor(
                    R.id.aqi_area_value,
                    if (dim) mutedInk else area.level.textColorInt(ctx),
                )
                rv.setContentDescription(
                    R.id.aqi_area_row,
                    ctx.getString(
                        if (area.worst) R.string.widget_area_worst else R.string.widget_area,
                    ) + " " + area.value,
                )
            } else {
                rv.setViewVisibility(R.id.aqi_area_row, View.GONE)
            }
        }

        rv.setTextViewText(R.id.aqi_value, AqiFormat.valueOrDash(s.value))
        rv.setTextColor(
            R.id.aqi_value,
            when {
                !s.hasValue -> ContextCompat.getColor(ctx, R.color.text_dim)
                dim -> mutedInk
                kind == WidgetKind.MAIN -> ContextCompat.getColor(ctx, R.color.text_primary)
                else -> s.level.textColorInt(ctx)
            },
        )
        if (kind != WidgetKind.MAIN) {
            val (base, threeDigit) = when (kind) {
                WidgetKind.MINI_SQUARE -> 32f to 23f
                WidgetKind.MINI_TALL -> 36f to 26f
                WidgetKind.COMPACT -> 17f to 15f
                else -> 36f to 26f
            }
            rv.setTextViewTextSize(
                R.id.aqi_value,
                android.util.TypedValue.COMPLEX_UNIT_SP,
                if (s.value >= 100) threeDigit else base,
            )
        }

        val tier = MaskTier.of(s.value)
        rv.setTextViewText(
            R.id.aqi_level,
            if (tier != null) ctx.getString(tier.labelRes) else AqiFormat.freshnessLabel(s.freshness, ctx),
        )
        rv.setTextColor(
            R.id.aqi_level,
            when {
                !s.hasValue -> neutral
                dim -> mutedSoft
                else -> s.level.textColorInt(ctx)
            },
        )

        if (kind == WidgetKind.MAIN) {
            val px = dp(ctx, 66f)
            rv.setImageViewBitmap(
                R.id.aqi_gauge,
                GaugePainter.ring(px, if (s.hasValue) s.value else -1, palette, dark, dim),
            )

            val alert = s.hasValue && !s.isStale && s.value >= 101
            rv.setViewVisibility(R.id.aqi_pulse, if (alert) View.VISIBLE else View.GONE)
            if (alert) {
                val halo = GaugePainter.halo(dp(ctx, 78f), s.value, palette)
                PULSE_IDS.forEach { id -> rv.setImageViewBitmap(id, halo) }
            }
        }

        if (kind != WidgetKind.MAIN) {
            val wDp = if (kind == WidgetKind.MINI_SQUARE) 74f else 0f
            val px = if (wDp > 0f) dp(ctx, wDp) else dp(ctx, 120f)
            rv.setImageViewBitmap(
                R.id.aqi_bar,
                GaugePainter.bar(
                    px,
                    if (s.hasValue) s.value else -1,
                    palette,
                    dark,
                    dp(ctx, 4f),
                    dim,
                ),
            )
        }

        if (kind == WidgetKind.MAIN) {
            rv.setTextViewText(R.id.aqi_meta, metaLine(ctx, s))
        }

        val showStale = s.hasValue && s.isStale
        when (kind) {
            WidgetKind.MINI_SQUARE ->
                rv.setViewVisibility(R.id.aqi_stale_dot, if (showStale) View.VISIBLE else View.GONE)
            WidgetKind.MAIN, WidgetKind.MINI_TALL -> {
                rv.setViewVisibility(R.id.aqi_stale, if (showStale) View.VISIBLE else View.GONE)
                if (showStale) {
                    val ago = AqiFormat.ago(s.fetchedAtMillis, ctx)
                    rv.setTextViewText(
                        R.id.aqi_stale,
                        ago.ifBlank { AqiFormat.freshnessLabel(s.freshness, ctx) },
                    )
                    rv.setTextColor(R.id.aqi_stale, mutedInk)
                }
            }
            WidgetKind.COMPACT -> Unit
        }

        val open = PendingIntent.getActivity(
            ctx, kind.ordinal, Intent(ctx, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        rv.setBoolean(R.id.widget_root, "setClipToOutline", true)

        rv.setOnClickPendingIntent(R.id.widget_root, open)

        return rv
    }

    private val PULSE_IDS =
        intArrayOf(R.id.aqi_pulse_1, R.id.aqi_pulse_2, R.id.aqi_pulse_3)

    private fun dp(ctx: Context, v: Float): Int =
        (v * ctx.resources.displayMetrics.density).toInt().coerceAtLeast(1)

    private fun isNight(ctx: Context): Boolean =
        (ctx.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES

    private fun metaLine(ctx: Context, s: AqiState): String {
        if (!s.hasValue) return AqiFormat.freshnessLabel(s.freshness, ctx)
        val parts = mutableListOf<String>()
        parts += when {
            s.stationName.isNotBlank() -> s.stationName
            s.modelGrid -> ctx.getString(R.string.station_model_grid_short)
            else -> ctx.getString(R.string.widget_no_station)
        }
        s.dominantPollutant.uppercase().takeIf { it.isNotBlank() }?.let { parts += it }
        if (!s.isStale) parts += AqiFormat.time(s.fetchedAtMillis)
        return parts.joinToString(" · ")
    }
}

object WidgetUpdater {

    private val BINDINGS = listOf(
        AqiWidgetMain::class.java to WidgetKind.MAIN,
        AqiWidgetCompact::class.java to WidgetKind.COMPACT,
        AqiWidgetMiniTall::class.java to WidgetKind.MINI_TALL,
        AqiWidgetMiniSquare::class.java to WidgetKind.MINI_SQUARE,
    )

    fun updateAll(ctx: Context, state: AqiState) {
        val mgr = AppWidgetManager.getInstance(ctx)
        BINDINGS.forEach { (cls, kind) ->
            val ids = mgr.getAppWidgetIds(ComponentName(ctx, cls))
            if (ids.isEmpty()) return@forEach
            val rv = WidgetRenderer.render(ctx, kind, state)
            ids.forEach { id -> mgr.updateAppWidget(id, rv) }
        }
    }
}
