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
        shot(WidgetKind.COMPACT, state(184, 264), 110, 60, "card_compact_en_min")
        shot(WidgetKind.MINI_TALL, state(184, 264), 74, 150, "card_tall_en_min")
    }

    @Test
    fun renderCompactCard() {
        shot(WidgetKind.COMPACT, state(152, 198), 150, 74, "card_compact_same")
        shot(WidgetKind.COMPACT, state(68, 152), 150, 74, "card_compact_cross")
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

    @Test
    fun renderDark() {
        RuntimeEnvironment.setQualifiers("+night")
        val darkCtx = RuntimeEnvironment.getApplication()
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
        bmp.eraseColor(0xFF20262F.toInt())
        view.draw(Canvas(bmp))
        FileOutputStream(File(outDir, "$name.png")).use {
            bmp.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }

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
