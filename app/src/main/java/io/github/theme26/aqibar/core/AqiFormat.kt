package io.github.theme26.aqibar.core

import android.content.Context
import io.github.theme26.aqibar.R
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object AqiFormat {

    fun valueOrDash(v: Int): String = if (v < 0) "– –" else v.toString()

    fun miniTextSizeSp(v: Int): Float = if (v >= 100) 18f else 25f

    fun time(millis: Long): String =
        if (millis <= 0L) "--:--"
        else SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(millis))

    fun ago(
        sinceMillis: Long,
        ctx: Context,
        now: Long = System.currentTimeMillis(),
    ): String {
        if (sinceMillis <= 0L) return ""
        val minutes = ((now - sinceMillis) / 60_000L).coerceAtLeast(0L)
        return when {
            minutes < 60L -> ctx.getString(R.string.ago_min, minutes)
            minutes < 1440L -> ctx.getString(R.string.ago_hour, minutes / 60L)
            else -> ctx.getString(R.string.ago_day, minutes / 1440L)
        }
    }

    fun freshnessLabel(f: Freshness, ctx: Context): String = ctx.getString(
        when (f) {
            Freshness.FRESH -> R.string.fresh_ok
            Freshness.STALE -> R.string.fresh_stale
            Freshness.FAILED -> R.string.fresh_failed
            Freshness.NO_LOCATION -> R.string.fresh_no_location
            Freshness.NO_PERMISSION -> R.string.fresh_no_permission
            Freshness.NO_TOKEN -> R.string.fresh_no_token
        }
    )

    fun attribution(standard: AqiStandard, ctx: Context): String = ctx.getString(
        if (standard == AqiStandard.CN) R.string.attribution_cn else R.string.attribution_us
    )

    fun attribution(state: AqiState, ctx: Context): String =
        state.attribution.ifBlank { attribution(state.standard, ctx) }

    fun notifHint(standard: AqiStandard, ctx: Context): String = ctx.getString(
        if (standard == AqiStandard.CN) R.string.notif_big_hint_cn else R.string.notif_big_hint
    )

    fun notifHint(state: AqiState, ctx: Context): String = ctx.getString(
        when {
            state.sourceTag == AqiSource.WAQI_RECALC.tag -> R.string.notif_big_hint_waqi_recalc
            state.standard == AqiStandard.CN -> R.string.notif_big_hint_cn
            else -> R.string.notif_big_hint
        }
    )

    private fun one(x: Double): String =
        if (x.isNaN()) "—" else String.format(Locale.US, "%.1f", x)

    fun diag(s: AqiState, ctx: Context): String {
        val d = s.diag ?: return s.diagnostic
        if (d.kind == DiagInfo.Kind.WAQI_RECALC) {
            return buildString {
                append(
                    ctx.getString(
                        R.string.diag_waqi,
                        d.originalIndex,
                        one(d.equivalent),
                        d.recalcIndex,
                        d.reportedAqi,
                    )
                )
                if (d.reportedAqi > (s.subIndices.values.maxOrNull() ?: 0)) {
                    append(" · ").append(ctx.getString(R.string.diag_waqi_high))
                }
                append(ctx.getString(R.string.diag_waqi_tail))
            }
        }
        val win = if (d.windowHours <= 1) {
            ctx.getString(R.string.diag_window_instant)
        } else {
            ctx.getString(R.string.diag_window_hours, d.windowHours)
        }
        val conc = if (d.concentration.isNaN()) "" else one(d.concentration) + " µg/m³"
        return buildString {
            append(
                ctx.getString(
                    R.string.diag_line,
                    d.pollutant.ifBlank { "—" },
                    win,
                    conc,
                    d.localAqi,
                )
            )
            if (d.officialAqi >= 0) {
                append(" · ").append(ctx.getString(R.string.diag_official, d.officialAqi))
            }
            if (d.fellBackCount > 0) {
                append(" · ").append(ctx.getString(R.string.diag_fallback, d.fellBackCount))
            }
        }
    }
}
