package com.example.aqi.core

import android.content.Context
import com.example.aqi.R
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object AqiFormat {

    /** 无数据显示 – –，绝不显示 0（0 会被误读成「空气极好」）。 */
    fun valueOrDash(v: Int): String = if (v < 0) "– –" else v.toString()

    /** 1 格宽只有 40dp，3 位数必须缩字号，否则 352 会被裁切。 */
    fun miniTextSizeSp(v: Int): Float = if (v >= 100) 18f else 25f

    fun time(millis: Long): String =
        if (millis <= 0L) "--:--"
        else SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(millis))

    /**
     * 相对时间：「3 小时前」（v0.4.8）。
     *
     * 桌面小部件空间紧张，所以单位用缩写资源（`3 h ago` / `3時間前`），
     * 而不是 "3 小时 12 分钟前"。超过一天只给天数 —— 这种精度已经足够
     * 让用户判断「要不要点进去手动刷一下」。
     *
     * 时间戳 <= 0（旧缓存没有 fetched）返回空串，由调用方退回「数据陈旧」。
     * [now] 可注入，否则这个函数在不同机器上跑出的断言会随时间漂移。
     */
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

    /** 署名按标准切换（保留旧签名兼容其它调用点）。 */
    fun attribution(standard: AqiStandard, ctx: Context): String = ctx.getString(
        if (standard == AqiStandard.CN) R.string.attribution_cn else R.string.attribution_us
    )

    /** 优先用状态里带的具体来源（WAQI 站点实测会额外注明 2012 旧断点）。 */
    fun attribution(state: AqiState, ctx: Context): String =
        state.attribution.ifBlank { attribution(state.standard, ctx) }

    /** 通知展开区的口径提示（保留旧签名兼容其它调用点）。 */
    fun notifHint(standard: AqiStandard, ctx: Context): String = ctx.getString(
        if (standard == AqiStandard.CN) R.string.notif_big_hint_cn else R.string.notif_big_hint
    )

    /** 按 sourceTag 区分 2024 本地计算 / WAQI 反算 2024 / 国标，口径必须对用户可见。 */
    fun notifHint(state: AqiState, ctx: Context): String = ctx.getString(
        when {
            state.sourceTag == AqiSource.WAQI_RECALC.tag -> R.string.notif_big_hint_waqi_recalc
            state.standard == AqiStandard.CN -> R.string.notif_big_hint_cn
            else -> R.string.notif_big_hint
        }
    )

    private fun one(x: Double): String =
        if (x.isNaN()) "—" else String.format(Locale.US, "%.1f", x)

    /**
     * 计算口径行（详情页底部 / 通知展开区 / 无数据时的兜底）。
     *
     * v0.4.1 起由结构化字段 [DiagInfo] 现场拼句子：数据层不再生成中文,
     * 所以英文、日文界面看到的也是本语言的句子。
     * 旧缓存没有结构化字段时退回 [AqiState.diagnostic] 原文（可能是旧语言，但不丢信息）。
     */
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
