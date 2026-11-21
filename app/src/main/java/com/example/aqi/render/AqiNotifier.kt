package com.example.aqi.render

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
import com.example.aqi.R
import com.example.aqi.core.AqiFormat
import com.example.aqi.core.AqiSource
import com.example.aqi.core.AqiStandard
import com.example.aqi.core.AqiState
import com.example.aqi.core.MaskTier
import com.example.aqi.data.AqiCache
import com.example.aqi.data.AqiPrefs
import com.example.aqi.live.LiveUpdate
import com.example.aqi.ui.MainActivity

/**
 * 状态栏常驻通知 + 锁屏通知。普通通知 + setOngoing(true) 即可常驻，
 * 不需要前台服务（详见 README「为什么不用前台服务」）。
 *
 * ## v0.1.6：状态栏 smallIcon 改为三档形状，数值仍是 6 档
 * smallIcon 只取 alpha 通道、24dp 下靠 6 档填充度根本分辨不清，所以
 * 改用 ○ 可出门 / ● 需口罩 / ⊘ 不应出门（[MaskTier]），**跟随主源**取值。
 * 通知标题、展开区数值、等级色、桌面小部件、锁屏卡片、屏保全部保持 6 档不变。
 *
 * 两源对照只出现在通知展开区与主卡的第二行，且各自标注口径，不并排当可互校验的数值。
 */
class AqiNotifier(private val ctx: Context) {

    fun show(state: AqiState) {
        // v0.1.8：通知时机策略先于一切。该 ROM 会把 smallIcon 换成 launcher 图标，
        // 所以「有没有这条通知」才是可靠信息位——优/良时主动撤掉，状态栏干净。
        val prefs = AqiPrefs(ctx)
        if (!prefs.notificationEnabled || !prefs.notifPolicy.shouldNotify(state.value)) {
            cancel()
            return
        }
        // API 33+ 权限被拒时服务/任务照样在跑，但通知不可见 —— 由设置页给出引导横幅
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        try {
            NotificationManagerCompat.from(ctx).notify(NOTIF_ID, buildFull(state))
        } catch (e: SecurityException) {
            // 某些 ROM 会额外拦截，静默降级，不让 Worker 崩
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
        // 锁屏「仅数值」档：提供脱敏公开版，不显示站点名与距离（会暴露大致位置）
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
        // 三档形状图标：无数值时退到「圆内一横」占位，绝不伪装成任何一档
        val smallIcon = MaskTier.of(state.value)?.iconRes ?: MaskTier.UNKNOWN_ICON
        val b = NotificationCompat.Builder(ctx, CHANNEL_ID)
            .setSmallIcon(smallIcon)
            .setColor(state.level.colorInt())
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            // 角标由通知渠道的 setShowBadge(false) 控制（见 AqiApp），Builder 无此方法
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setShowWhen(true)
            .setWhen(if (state.fetchedAtMillis > 0) state.fetchedAtMillis else System.currentTimeMillis())

        // v0.5.0：请求提升为 Live Update —— 只有被提升的常驻通知才会进入
        // 系统息屏界面与锁屏顶部（就是「Live Alerts」那一条通道）。
        // 结构合格与否由系统判定，这里只负责提出请求；结果在设置页回读。
        // 不做版本判断：低版本系统读到不认识的 extra 会直接忽略，无害。
        if (AqiPrefs(ctx).liveUpdate) {
            b.addExtras(Bundle().apply { putBoolean(LiveUpdate.EXTRA_REQUEST_PROMOTED, true) })
        }
        return b
    }

    /**
     * 折叠态标题：**数值前置**。v0.1.6 把标准名放最前，结果折叠后只剩「US AQI 2024…」，
     * AQI 数字被省略号吃掉（用户实测反馈）。现在固定「数值 + 等级」，标准名下沉到展开区。
     */
    private fun title(s: AqiState): String =
        if (!s.hasValue) ctx.getString(R.string.notif_placeholder)
        else ctx.getString(R.string.notif_title, s.value, s.level.label(ctx, s.standard))

    /**
     * 折叠态正文：只留「动作 + 时间」，例如「需口罩 · 09:58」。
     * 不用 ○ ● ⊘ 符号——它们属于 Geometric Shapes / Mathematical Operators，
     * 部分 ROM 的通知字体缺字形，会渲染成方框或星号（v0.1.6 截图已出现）。
     */
    private fun summary(s: AqiState): String {
        if (!s.hasValue) return AqiFormat.freshnessLabel(s.freshness, ctx)
        val action = MaskTier.of(s.value)?.let { ctx.getString(it.labelRes) }
            ?: ctx.getString(R.string.mask_unknown_short)
        val tail = if (s.isStale) AqiFormat.freshnessLabel(s.freshness, ctx)
        else AqiFormat.time(s.fetchedAtMillis)
        return ctx.getString(R.string.notif_text, action, tail)
    }

    /**
     * 展开区固定 4 行：动作 / 地点 / 口径 / 对照。
     * v0.1.6 这里是 10 行长文（含 6 项分指数、免责声明、署名），在小屏上要滚两屏，用户直接判为废话。
     */
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

    /** 模型网格没有站点名：数据层留空并置 modelGrid，这里按当前语言显示。 */
    private fun stationOf(s: AqiState): String = when {
        s.stationName.isNotBlank() -> s.stationName
        s.modelGrid -> ctx.getString(R.string.station_model_grid)
        else -> ctx.getString(R.string.widget_no_station)
    }

    /** 具体链路名（OM 本地 2024 / WAQI 反算 2024），比标准名更能说明数值怎么来的。 */
    private fun sourceShort(s: AqiState): String =
        AqiSource.fromTag(s.sourceTag)?.shortLabel(ctx) ?: s.standard.shortLabel(ctx)

    /** 展开区末行：另一源只给数值与原因占位，不再堆符号档位。 */
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
