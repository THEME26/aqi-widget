package com.example.aqi.render

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import com.example.aqi.core.AqiLevel
import com.example.aqi.core.AqiStandard
import com.example.aqi.core.AqiState
import com.example.aqi.core.AreaStat
import com.example.aqi.core.Freshness
import com.example.aqi.widget.WidgetKind
import com.example.aqi.widget.WidgetRenderer
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.io.FileOutputStream

/**
 * 整卡渲染：走**真实的** RemoteViews.apply 路径 —— inflate 布局、
 * 应用所有 setTextViewText / setImageViewBitmap / setViewVisibility，
 * 再 measure + layout + draw 成位图。
 *
 * 这比「用 PIL 复刻一遍布局」可信得多：复刻只能证明我写的 Python 对不对，
 * 这张图证明的是**发给桌面的那个 RemoteViews 长什么样**。
 *
 * 局限：Robolectric 的字体渲染与真机可能有基线/字重差异，
 * 颜色、几何、层级、裁切是可信的。
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "zh-rCN-xxhdpi", application = android.app.Application::class)
class WidgetCardRenderTest {

    private val ctx: Context = RuntimeEnvironment.getApplication()
    private val outDir = File("build/render-check").apply { mkdirs() }

    private fun state(v: Int, areaV: Int?, stale: Boolean = false) = AqiState(
        value = v,
        level = AqiLevel.of(v),
        dominantPollutant = "pm2.5",
        subIndices = mapOf("pm2_5" to v),
        stationName = "奥体中心",
        distanceKm = 1.2,
        observedText = "",
        fetchedAtMillis = System.currentTimeMillis() - 3_600_000,
        freshness = if (stale) Freshness.STALE else Freshness.FRESH,
        attribution = "",
        standard = AqiStandard.US,
        sourceTag = "us_2024_avg",
        area = areaV?.let {
            AreaStat(
                value = it,
                level = AqiLevel.of(it),
                radiusKm = 10,
                samples = 9,
                grid = listOf(it, v, v, it, v, it, v, it, v),
            )
        },
    )

    /** dp → px，和 widget 布局用的是同一个密度。 */
    private fun dp(v: Int): Int =
        (v * ctx.resources.displayMetrics.density).toInt()

    private fun shot(kind: WidgetKind, s: AqiState, wDp: Int, hDp: Int, name: String) {
        val rv = WidgetRenderer.render(ctx, kind, s)
        val parent = FrameLayout(ctx)
        val view: View = rv.apply(ctx, parent)
        val w = dp(wDp)
        val h = dp(hDp)
        view.measure(
            View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY),
        )
        view.layout(0, 0, w, h)
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        // 铺一层浅灰底：既能看到玻璃的通透度，也直接可用作 widget 选择器的预览图
        bmp.eraseColor(0xFFEDF0F5.toInt())
        view.draw(Canvas(bmp))
        FileOutputStream(File(outDir, "$name.png")).use {
            bmp.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }

    @Test
    fun renderMainCard() {
        shot(WidgetKind.MAIN, state(152, 198), 288, 110, "card_main_same")
        shot(WidgetKind.MAIN, state(68, 152), 288, 110, "card_main_cross")
        shot(WidgetKind.MAIN, state(320, 198), 288, 110, "card_main_haz")
        shot(WidgetKind.MAIN, state(45, 45), 288, 110, "card_main_good")
    }

    /** 英文环境：小部件的「周边 / 站名 / 无站点」都走资源，必须确认没冒出中文。 */
    @Test
    @Config(qualifiers = "en-rUS-xxhdpi", sdk = [34], application = android.app.Application::class)
    fun renderEnglish() {
        shot(
            WidgetKind.MAIN,
            state(68, 152).copy(stationName = "Olympic Sports Center"),
            288, 110, "card_main_en",
        )
        shot(
            WidgetKind.MAIN,
            state(268, 152).copy(stationName = "", modelGrid = true),
            288, 110, "card_main_en_grid",
        )
        // 用户截图那一版：英文 + 最差口径 + 三位数，2×1 上曾经只显示「18」
        shot(WidgetKind.COMPACT, state(184, 264), 110, 60, "card_compact_en_min")
        shot(WidgetKind.MINI_TALL, state(184, 264), 74, 150, "card_tall_en_min")
    }

    @Test
    fun renderCompactCard() {
        shot(WidgetKind.COMPACT, state(152, 198), 150, 74, "card_compact_same")
        shot(WidgetKind.COMPACT, state(68, 152), 150, 74, "card_compact_cross")
        // v0.4.3 回归点：三位数主数字 + 三位数区域数字，在最窄的 2×1（110×60dp）上
        // 必须都完整 —— 旧版把「周边 / Worst」写进胶囊，主数字只剩两位的宽度
        shot(WidgetKind.COMPACT, state(184, 264), 110, 60, "card_compact_3digit_min")
        shot(WidgetKind.COMPACT, state(184, 264), 150, 74, "card_compact_3digit")
    }

    @Test
    fun renderMiniTall() {
        shot(WidgetKind.MINI_TALL, state(152, 198), 74, 150, "card_tall")
        shot(WidgetKind.MINI_TALL, state(68, 152), 74, 150, "card_tall_cross")
    }

    @Test
    fun renderMiniSquare() {
        shot(WidgetKind.MINI_SQUARE, state(152, 198), 74, 74, "card_square")
        shot(WidgetKind.MINI_SQUARE, state(320, 198), 74, 74, "card_square_haz")
    }

    /** 深色模式：切 qualifiers 后重建 Context，验证 values-night 的 ink 与玻璃色是否生效。 */
    @Test
    fun renderDark() {
        RuntimeEnvironment.setQualifiers("+night")
        val darkCtx = RuntimeEnvironment.getApplication()
        // 直接用 darkCtx 渲染，避免 @Config 的浅色资源缓存
        shotWith(darkCtx, WidgetKind.MAIN, state(152, 198), 288, 110, "card_main_dark")
        shotWith(darkCtx, WidgetKind.MAIN, state(68, 152), 288, 110, "card_main_dark_cross")
        shotWith(darkCtx, WidgetKind.COMPACT, state(184, 264), 110, 60, "card_compact_dark_min")
        shotWith(darkCtx, WidgetKind.MINI_SQUARE, state(320, 198), 74, 74, "card_square_dark")
        RuntimeEnvironment.setQualifiers("+notnight")
    }

    private fun shotWith(
        context: Context,
        kind: WidgetKind,
        s: AqiState,
        wDp: Int,
        hDp: Int,
        name: String,
    ) {
        val rv = WidgetRenderer.render(context, kind, s)
        val view: View = rv.apply(context, FrameLayout(context))
        val w = (wDp * context.resources.displayMetrics.density).toInt()
        val h = (hDp * context.resources.displayMetrics.density).toInt()
        view.measure(
            View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY),
        )
        view.layout(0, 0, w, h)
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        // 深色卡配深色底，否则贴到浅色展示图上像一张「贴纸」
        bmp.eraseColor(0xFF20262F.toInt())
        view.draw(Canvas(bmp))
        FileOutputStream(File(outDir, "$name.png")).use {
            bmp.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }

    /** RemoteViews 的反射调用是否真的把 clipToOutline 打开了 —— API 26~30 上的唯一保障。 */
    @Test
    fun clipToOutlineIsEnabledByRemoteViews() {
        val rv = WidgetRenderer.render(ctx, WidgetKind.MAIN, state(152, 198))
        val view: View = rv.apply(ctx, FrameLayout(ctx))
        org.junit.Assert.assertTrue(
            "setBoolean(\"setClipToOutline\") 未生效，API 26~30 上渐变会溢出圆角",
            view.clipToOutline,
        )
    }

    @Test
    fun renderNoAreaAndStale() {
        shot(WidgetKind.MAIN, state(152, null), 288, 110, "card_main_noarea")
        shot(WidgetKind.MINI_SQUARE, state(152, null, stale = true), 74, 74, "card_square_stale")
    }
}
