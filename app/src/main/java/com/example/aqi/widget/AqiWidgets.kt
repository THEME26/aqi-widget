package com.example.aqi.widget

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
import com.example.aqi.R
import com.example.aqi.core.AqiFormat
import com.example.aqi.core.AqiState
import com.example.aqi.core.MaskTier
import com.example.aqi.data.AqiCache
import com.example.aqi.data.AqiPrefs
import com.example.aqi.render.BgPainter
import com.example.aqi.render.GaugePainter
import com.example.aqi.ui.MainActivity
import com.example.aqi.worker.RefreshScheduler

/** 四档尺寸，与 res/xml 下四个 widget info 文件一一对应 */
enum class WidgetKind(val layoutRes: Int) {
    MAIN(R.layout.widget_main),
    COMPACT(R.layout.widget_compact),
    MINI_TALL(R.layout.widget_mini_tall),
    MINI_SQUARE(R.layout.widget_mini_square),
}

/**
 * 四种尺寸共用一个 provider 基类：onUpdate 里绝不做网络请求
 * （updatePeriodMillis 最小 30 分钟，且 onUpdate 超 10 秒会被判无响应）。
 * 网络与调度全部交给 WorkManager，本类只用缓存渲染。
 */
abstract class BaseAqiWidgetProvider : AppWidgetProvider() {
    abstract val kind: WidgetKind

    override fun onUpdate(context: Context, mgr: AppWidgetManager, appWidgetIds: IntArray) {
        RefreshScheduler.ensurePeriodic(context)
        val p = AqiPrefs(context)
        // 读与当前标准对应的缓存槽，避免切换标准后把另一套口径的数值画到桌面上
        val state = AqiCache(context).loadForDisplay(p.intervalMinutes, p.standard, p.token.isBlank())
        val rv = WidgetRenderer.render(context, kind, state)
        appWidgetIds.forEach { mgr.updateAppWidget(it, rv) }
        // v0.4.8：updatePeriodMillis 现在是 30 分钟，系统会定期叫到这里。
        // 但「叫到」不等于「该取数」——必须按数据状态决定：
        //  - 数据新鲜 → 什么都不做。否则每 30 分钟一次强制刷新，
        //    会直接翻掉用户设的 60/120 分钟，流量和耗电都对不上设置。
        //  - 陈旧 / 从没取到 → 补一次。这是 WorkManager 被 ROM 掐掉之后，
        //    卡片唯一能自救的机会。
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

/**
 * v0.3.0 渲染器。与 v0.2.0 的三处根本变化：
 *
 * 1. **环随数值变化**：不再是「每档一张满环」，而是六格量程 —— 第几格 = 危险档，
 *    格内填多少 = 距下一档还有多远。0/25/50 三个值现在画出三种不同的环。
 * 2. **卡片有两层数据**：顶端区域档色 → 底端本站档色，一条连续渐变。
 * 3. **环和渐变都由 Canvas 现场画**（[GaugePainter] / [BgPainter]），
 *    不预渲染 —— 详见 GaugePainter 的类注释。
 *
 * 所有尺寸共用这一个渲染器，保证「一份 AqiState 驱动多端」数值一致。
 */
object WidgetRenderer {

    fun render(ctx: Context, kind: WidgetKind, s: AqiState): RemoteViews {
        val rv = RemoteViews(ctx.packageName, kind.layoutRes)
        val dark = isNight(ctx)
        // 环/横条的颜色来自 colors.xml 的 aqi_*_ink，values-night 自动给深色版
        //
        // ---- 陈旧态整卡去色（v0.4.8）----
        // 颜色是一种断言：「此刻的空气就是这个样子」。超过两个刷新周期没更新时，
        // 这句断言已经不成立，继续画红/绿等于替数据源作保 —— 而我们连它到底
        // 更没更新都不知道（见 README §20.1）。
        // 去色**只去色相**：数值、填到第几格、周边胶囊、陈旧小字一个都不藏。
        val dim = s.hasValue && s.isStale
        val palette = if (dim) GaugePainter.mutedPalette(ctx) else GaugePainter.palette(ctx)
        // 无数据与陈旧都用中性色，但语义不同：前者是「没有」，后者是「有过但不可信」。
        // 分成两个变量是为了将来能各自调整，而不是现在就共用同一个常量。
        val neutral = ContextCompat.getColor(ctx, R.color.text_secondary)
        val mutedInk = ContextCompat.getColor(ctx, R.color.text_secondary)
        val mutedSoft = ContextCompat.getColor(ctx, R.color.text_dim)
        val area = if (s.hasValue) s.area else null

        // ---- 背景：一条纵向渐变，区域档色 → 本站档色 ----
        val (topA, botA) = BgPainter.alphaOf(dark)
        val topColor = when {
            // 陈旧时仍保留「区域 ≠ 本站」这条信息，只是两条都退成灰阶深浅
            dim -> mutedSoft
            else -> area?.level?.let { GaugePainter.ringColor(it) } ?: GaugePainter.ringColor(s.level)
        }
        val botColor = if (dim) mutedSoft else GaugePainter.ringColor(s.level)
        rv.setImageViewBitmap(R.id.aqi_bg, BgPainter.strip(topColor, botColor, topA, botA))

        // ---- 区域胶囊：取不到就整块 GONE，卡片干净退回单层 ----
        // 1×1 布局里根本没有这排视图（只有环、数字、档名），必须按尺寸跳过 ——
        // 否则 RemoteViews 会对着不存在的 id 做反射，「小组件无法加载」。
        //
        // v0.4.3：胶囊里只剩一个按档上色的数字，不再写「周边 / 最差」。
        // 原因有两个：一是标签在 2×1 里跟主数字抢宽度，三位数会被右边界切掉；
        // 二是「周边」是口径说明，而颜色已经表达档位，字反而是噪声。
        if (kind != WidgetKind.MINI_SQUARE) {
            if (area != null) {
                rv.setViewVisibility(R.id.aqi_area_row, View.VISIBLE)
                rv.setTextViewText(R.id.aqi_area_value, area.value.toString())
                rv.setTextColor(
                    R.id.aqi_area_value,
                    if (dim) mutedInk else area.level.textColorInt(ctx),
                )
                // 视觉上只剩一个数字，口径（周边 / 最差）不能一起消失 ——
                // 交给无障碍描述，读屏仍然能听到「周边 264」，占位为零。
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

        // ---- 数字 ----
        rv.setTextViewText(R.id.aqi_value, AqiFormat.valueOrDash(s.value))
        // 4×2 的数字在环里，用中性 ink：环本身已经是彩色，数字再染色等于拿可读性换配色。
        // 小尺寸没有环，数字就是主体，用档色。
        rv.setTextColor(
            R.id.aqi_value,
            when {
                !s.hasValue -> ContextCompat.getColor(ctx, R.color.text_dim)
                // 陈旧：即使 4×2 也用中性灰。原来这里靠「环是彩色的、数字保持中性」分工，
                // 环一灰，数字再保持 text_primary 就成了灰卡上唯一的高对比元素，
                // 反而比彩色时更抢眼 —— 权重必须跟着降。
                dim -> mutedInk
                kind == WidgetKind.MAIN -> ContextCompat.getColor(ctx, R.color.text_primary)
                else -> s.level.textColorInt(ctx)
            },
        )
        if (kind != WidgetKind.MAIN) {
            // 基准字号 = 布局里写的 textSize，三位数再按比例缩一点。
            //
            // v0.4.2 及之前这里只有「1×1 用 32、其余用 36」两档，于是 2×1 的
            // 17sp 被强行改成 36sp：右上方那串数字比左边胶囊还宽，三星数（如 184）
            // 直接被右边界切掉。字号必须按尺寸分别给，不能一套走天下。
            val (base, threeDigit) = when (kind) {
                WidgetKind.MINI_SQUARE -> 32f to 23f
                WidgetKind.MINI_TALL -> 36f to 26f
                // 2×1 的可用宽度 = 110dp(最窄) - 25dp(padding) - 32dp(胶囊) ≈ 53dp，
                // 17sp 的「184」约 28dp，不必缩；留一档 15sp 兜住 4 位数的极端情况
                WidgetKind.COMPACT -> 17f to 15f
                else -> 36f to 26f
            }
            rv.setTextViewTextSize(
                R.id.aqi_value,
                android.util.TypedValue.COMPLEX_UNIT_SP,
                if (s.value >= 100) threeDigit else base,
            )
        }

        // ---- 动作词：桌面上扫一眼要的是结论 ----
        val tier = MaskTier.of(s.value)
        rv.setTextViewText(
            R.id.aqi_level,
            if (tier != null) ctx.getString(tier.labelRes) else AqiFormat.freshnessLabel(s.freshness, ctx),
        )
        rv.setTextColor(
            R.id.aqi_level,
            when {
                !s.hasValue -> neutral
                // 动作词（「需口罩」）用比数字更淡的一档灰：数字是「上次测到什么」，
                // 仍然可读；动作词是「据此该怎么办」，是推断，可信度更低。
                // 两者同色时，25sp 粗体的动作词会比数字更抢眼，正好把主次弄反。
                dim -> mutedSoft
                else -> s.level.textColorInt(ctx)
            },
        )

        // ---- 环（仅 4×2）----
        if (kind == WidgetKind.MAIN) {
            val px = dp(ctx, 66f)
            rv.setImageViewBitmap(
                R.id.aqi_gauge,
                GaugePainter.ring(px, if (s.hasValue) s.value else -1, palette, dark, dim),
            )

            val alert = s.hasValue && !s.isStale && s.value >= 101
            rv.setViewVisibility(R.id.aqi_pulse, if (alert) View.VISIBLE else View.GONE)
            if (alert) {
                // 三帧共用同一个 Bitmap 实例：RemoteViews 在 writeToParcel 时按对象身份
                // 去重（mBitmapCache），所以只传输一次，不会三倍占 Binder 预算。
                val halo = GaugePainter.halo(dp(ctx, 78f), s.value, palette)
                PULSE_IDS.forEach { id -> rv.setImageViewBitmap(id, halo) }
            }
        }

        // ---- 六格横条（除 4×2 外都用）----
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

        // ---- 元信息行（仅 4×2）----
        if (kind == WidgetKind.MAIN) {
            rv.setTextViewText(R.id.aqi_meta, metaLine(ctx, s))
        }

        // ---- 陈旧提示：1×1 只放得下一个圆点，其余给一行小字 ----
        val showStale = s.hasValue && s.isStale
        when (kind) {
            WidgetKind.MINI_SQUARE ->
                rv.setViewVisibility(R.id.aqi_stale_dot, if (showStale) View.VISIBLE else View.GONE)
            WidgetKind.MAIN, WidgetKind.MINI_TALL -> {
                rv.setViewVisibility(R.id.aqi_stale, if (showStale) View.VISIBLE else View.GONE)
                if (showStale) {
                    // v0.4.8：整卡灰了以后，「数据陈旧」四个字只是把颜色又说了一遍，
                    // 信息量为零。改成回答真正被问的那个问题 —— 多久没更新了。
                    // 拿不到时间戳（旧缓存）才退回「数据陈旧」。
                    val ago = AqiFormat.ago(s.fetchedAtMillis, ctx)
                    rv.setTextViewText(
                        R.id.aqi_stale,
                        ago.ifBlank { AqiFormat.freshnessLabel(s.freshness, ctx) },
                    )
                    // 布局里给的是橙色的 aqi_usg_ink：在灰卡上留一抹橙，会让人以为
                    // 这是「当前等级提示」而不是「这卡已经不作数了」。一并压成中性灰。
                    rv.setTextColor(R.id.aqi_stale, mutedInk)
                }
            }
            // 2×1 塞不下第二行字：整卡变灰本身就是提示，不再额外占位
            WidgetKind.COMPACT -> Unit
        }

        val open = PendingIntent.getActivity(
            ctx, kind.ordinal, Intent(ctx, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        // clipToOutline 在布局 XML 里只有 API 31+ 认（lint: UnusedAttribute），
        // minSdk 26 意味着 26~30 上渐变会从四角溢出来。这里再调一次 setter 兜底：
        // RemoteViews.setBoolean 走反射，不校验 @RemotableViewMethod，public SDK 方法可调。
        // 万一在某些 ROM 上失败，降级也只是圆角处露出 alpha 8~13% 的直角，不影响可读性。
        rv.setBoolean(R.id.widget_root, "setClipToOutline", true)

        rv.setOnClickPendingIntent(R.id.widget_root, open)

        return rv
    }

    /**
     * 三帧光晕。强度写在**布局**里（android:alpha = 1.00 / 0.55 / 0.76），
     * 而不是靠 setFloat(id,"setAlpha") —— 后者要反射命中 @RemotableViewMethod，
     * 在沙箱里无法实机验证；布局属性由 LayoutInflater 正常解析，行为确定。
     */
    private val PULSE_IDS =
        intArrayOf(R.id.aqi_pulse_1, R.id.aqi_pulse_2, R.id.aqi_pulse_3)

    /** dp → px，供 Canvas 画图用 */
    private fun dp(ctx: Context, v: Float): Int =
        (v * ctx.resources.displayMetrics.density).toInt().coerceAtLeast(1)

    private fun isNight(ctx: Context): Boolean =
        (ctx.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES

    /** 4×2 的一行元信息：站点 · 首要污染物 · 时间（陈旧时把时间换成「多久前」） */
    private fun metaLine(ctx: Context, s: AqiState): String {
        if (!s.hasValue) return AqiFormat.freshnessLabel(s.freshness, ctx)
        val parts = mutableListOf<String>()
        parts += when {
            s.stationName.isNotBlank() -> s.stationName
            // 小部件这一行只有一行宽度，站点名要用短名：
            // 英文 "Open-Meteo model grid" 会把后面的时间挤成 "21:…"（渲染测试里实测到）
            s.modelGrid -> ctx.getString(R.string.station_model_grid_short)
            else -> ctx.getString(R.string.widget_no_station)
        }
        s.dominantPollutant.uppercase().takeIf { it.isNotBlank() }?.let { parts += it }
        // 陈旧时不在这里写时间：卡片下面那行 aqi_stale 已经专门说了「多久前」。
        // 两处都写，4×2 上会出现「… · 3 小时前」+「3 小时前」的重复。
        if (!s.isStale) parts += AqiFormat.time(s.fetchedAtMillis)
        return parts.joinToString(" · ")
    }
}

/** WorkManager 刷新成功后主动推送到所有小部件实例 */
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
