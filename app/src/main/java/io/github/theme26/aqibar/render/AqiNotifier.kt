package io.github.theme26.aqibar.render

import android.Manifest
import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import io.github.theme26.aqibar.R
import io.github.theme26.aqibar.core.AqiFormat
import io.github.theme26.aqibar.core.AqiSource
import io.github.theme26.aqibar.core.AqiStandard
import io.github.theme26.aqibar.core.AqiState
import io.github.theme26.aqibar.core.MaskTier
import io.github.theme26.aqibar.data.AqiCache
import io.github.theme26.aqibar.data.AqiPrefs
import io.github.theme26.aqibar.live.LiveUpdate
import io.github.theme26.aqibar.ui.MainActivity

class AqiNotifier(private val ctx: Context) {

    fun show(state: AqiState) {
        val prefs = AqiPrefs(ctx)
        if (!prefs.notificationEnabled || !prefs.notifPolicy.shouldNotify(state.value)) {
            cancel()
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        try {
            NotificationManagerCompat.from(ctx).notify(NOTIF_ID, buildFull(state))
        } catch (e: SecurityException) {
        }
    }

    fun cancel() = NotificationManagerCompat.from(ctx).cancel(NOTIF_ID)

    private fun buildFull(state: AqiState): Notification {
        val full = AqiPrefs(ctx).lockScreenFull
        val b = baseBuilder(state)
            .setContentTitle(title(state))
            .setContentText(summary(state))
            .setStyle(NotificationCompat.BigTextStyle().bigText(bigText(state)))
            .setVisibility(
                if (full) NotificationCompat.VISIBILITY_PUBLIC
                else NotificationCompat.VISIBILITY_PRIVATE
            )
        if (!full) b.setPublicVersion(buildPublic(state))
        return b.build()
    }

    private fun buildPublic(state: AqiState): Notification = baseBuilder(state)
        .setContentTitle(title(state))
        .setContentText(ctx.getString(R.string.notif_public_text))
        .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
        .build()

    private fun baseBuilder(state: AqiState): NotificationCompat.Builder {
        val open = PendingIntent.getActivity(
            ctx, 0, Intent(ctx, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val smallIcon = MaskTier.of(state.value)?.iconRes ?: MaskTier.UNKNOWN_ICON
        val b = NotificationCompat.Builder(ctx, CHANNEL_ID)
            .setSmallIcon(smallIcon)
            .setColor(state.level.colorInt())
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setShowWhen(true)
            .setWhen(if (state.fetchedAtMillis > 0) state.fetchedAtMillis else System.currentTimeMillis())

        if (AqiPrefs(ctx).liveUpdate) {
            b.addExtras(Bundle().apply { putBoolean(LiveUpdate.EXTRA_REQUEST_PROMOTED, true) })
        }
        return b
    }

    private fun title(s: AqiState): String =
        if (!s.hasValue) ctx.getString(R.string.notif_placeholder)
        else ctx.getString(R.string.notif_title, s.value, s.level.label(ctx, s.standard))

    private fun summary(s: AqiState): String {
        if (!s.hasValue) return AqiFormat.freshnessLabel(s.freshness, ctx)
        val action = MaskTier.of(s.value)?.let { ctx.getString(it.labelRes) }
            ?: ctx.getString(R.string.mask_unknown_short)
        val tail = if (s.isStale) AqiFormat.freshnessLabel(s.freshness, ctx)
        else AqiFormat.time(s.fetchedAtMillis)
        return ctx.getString(R.string.notif_text, action, tail)
    }

    private fun bigText(s: AqiState): String = buildString {
        if (!s.hasValue) {
            append(AqiFormat.freshnessLabel(s.freshness, ctx))
            val d = AqiFormat.diag(s, ctx)
            if (d.isNotBlank()) append('\n').append(d)
            return@buildString
        }
        val tier = MaskTier.of(s.value)
        append(
            ctx.getString(
                R.string.notif_line_action,
                tier?.let { ctx.getString(it.labelRes) } ?: ctx.getString(R.string.mask_unknown_short),
                tier?.let { ctx.getString(it.adviceRes) } ?: "",
            )
        )
        val pol = s.dominantPollutant.ifBlank { "—" }.uppercase()
        val station = stationOf(s)
        val time = if (s.isStale) AqiFormat.freshnessLabel(s.freshness, ctx) else AqiFormat.time(s.fetchedAtMillis)
        append('\n').append(ctx.getString(R.string.notif_line_where, station, pol, time))
        append('\n').append(ctx.getString(R.string.notif_line_src, s.standard.shortLabel(ctx), sourceShort(s)))
        val diag = AqiFormat.diag(s, ctx)
        if (diag.isNotBlank()) append('\n').append(diag)
        append('\n').append(dualLine(s))
    }

    private fun stationOf(s: AqiState): String = when {
        s.stationName.isNotBlank() -> s.stationName
        s.modelGrid -> ctx.getString(R.string.station_model_grid)
        else -> ctx.getString(R.string.widget_no_station)
    }

    private fun sourceShort(s: AqiState): String =
        AqiSource.fromTag(s.sourceTag)?.shortLabel(ctx) ?: s.standard.shortLabel(ctx)

    private fun dualLine(s: AqiState): String {
        if (s.standard != AqiStandard.US) return ctx.getString(R.string.dual_cn_single)
        val p = AqiPrefs(ctx)
        val cache = AqiCache(ctx)
        val other = AqiSource.other(p.primarySource)
        val sec = cache.loadSecondaryForDisplay(p.intervalMinutes, s.standard, p.token.isBlank())
        if (sec == null || !sec.hasValue) {
            val reason = cache.secondaryStatus().ifBlank {
                if (other.needsToken && p.token.isBlank()) ctx.getString(R.string.secondary_no_token)
                else ctx.getString(R.string.secondary_unavailable)
            }
            return ctx.getString(R.string.notif_line_dual_none, other.shortLabel(ctx), reason)
        }
        return ctx.getString(R.string.notif_line_dual, other.shortLabel(ctx), sec.value)
    }

    companion object {
        const val CHANNEL_ID = "aqi_ongoing"
        const val NOTIF_ID = 1001
    }
}
