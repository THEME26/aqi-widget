package io.github.theme26.aqibar.render

import android.content.Context
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import io.github.theme26.aqibar.R
import io.github.theme26.aqibar.core.AqiLevel
import io.github.theme26.aqibar.core.AqiStandard
import io.github.theme26.aqibar.core.AqiState
import io.github.theme26.aqibar.core.AreaStat
import io.github.theme26.aqibar.core.Freshness
import io.github.theme26.aqibar.widget.WidgetKind
import io.github.theme26.aqibar.widget.WidgetRenderer
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "zh-rCN-xxhdpi", application = android.app.Application::class)
class WidgetTextFitTest {

    private val ctx: Context = RuntimeEnvironment.getApplication()

    private fun state(v: Int, areaV: Int?) = AqiState(
        value = v,
        level = AqiLevel.of(v),
        dominantPollutant = "pm2.5",
        subIndices = mapOf("pm2_5" to v),
        stationName = "奥体中心",
        distanceKm = 1.2,
        observedText = "",
        fetchedAtMillis = System.currentTimeMillis() - 3_600_000,
        freshness = Freshness.FRESH,
        attribution = "",
        standard = AqiStandard.US,
        area = areaV?.let {
            AreaStat(
                value = it,
                level = AqiLevel.of(it),
                radiusKm = 25,
                samples = 9,
                grid = List(9) { it },
            )
        },
    )

    private fun inflate(kind: WidgetKind, s: AqiState, wDp: Int, hDp: Int): View {
        val rv = WidgetRenderer.render(ctx, kind, s)
        val view = rv.apply(ctx, FrameLayout(ctx))
        val d = ctx.resources.displayMetrics.density
        val w = (wDp * d).toInt()
        val h = (hDp * d).toInt()
        view.measure(
            View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY),
        )
        view.layout(0, 0, w, h)
        return view
    }

    private fun fits(tv: TextView): Boolean {
        val avail = tv.width - tv.totalPaddingLeft - tv.totalPaddingRight
        val need = tv.paint.measureText(tv.text.toString())
        return avail > 0 && need <= avail + 0.5f
    }

    private fun assertValueFits(kind: WidgetKind, v: Int, wDp: Int, hDp: Int) {
        val view = inflate(kind, state(v, 264), wDp, hDp)
        val tv = view.findViewById<TextView>(R.id.aqi_value)
        assertTrue(
            "$kind $wDp×${hDp}dp 主数字 $v 被裁：可用 ${tv.width - tv.totalPaddingLeft - tv.totalPaddingRight}px，" +
                "需要 ${tv.paint.measureText(tv.text.toString())}px（${tv.textSize / ctx.resources.displayMetrics.scaledDensity}sp）",
            fits(tv),
        )
    }

    private fun assertAreaFits(kind: WidgetKind, areaV: Int, wDp: Int, hDp: Int) {
        val view = inflate(kind, state(184, areaV), wDp, hDp)
        val tv = view.findViewById<TextView>(R.id.aqi_area_value)
        assertTrue(
            "$kind $wDp×${hDp}dp 区域数字 $areaV 被裁：可用 ${tv.width - tv.totalPaddingLeft - tv.totalPaddingRight}px，" +
                "需要 ${tv.paint.measureText(tv.text.toString())}px",
            fits(tv),
        )
    }

    @Test
    fun compactValueFitsAtNarrowestSize() {
        listOf(60, 68, 184, 320, 500).forEach { assertValueFits(WidgetKind.COMPACT, it, 110, 60) }
        listOf(184, 500).forEach { assertValueFits(WidgetKind.COMPACT, it, 150, 74) }
    }

    @Test
    fun compactAreaNumberFits() {
        listOf(60, 152, 264, 500).forEach { assertAreaFits(WidgetKind.COMPACT, it, 110, 60) }
    }

    @Test
    fun tallValueFits() {
        listOf(68, 184, 500).forEach { assertValueFits(WidgetKind.MINI_TALL, it, 74, 150) }
    }

    @Test
    fun squareValueFits() {
        listOf(68, 184, 500).forEach { assertValueFits(WidgetKind.MINI_SQUARE, it, 74, 74) }
    }

    @Test
    fun mainValueFitsInsideGauge() {
        listOf(60, 184, 500).forEach { assertValueFits(WidgetKind.MAIN, it, 288, 110) }
        listOf(184, 500).forEach { assertValueFits(WidgetKind.MAIN, it, 250, 100) }
    }

    @Test
    fun compactChipAndValueCoexist() {
        val view = inflate(WidgetKind.COMPACT, state(184, 264), 110, 60)
        val chip = view.findViewById<TextView>(R.id.aqi_area_value)
        val value = view.findViewById<TextView>(R.id.aqi_value)
        assertTrue("区域数字被裁", fits(chip))
        assertTrue("主数字被裁", fits(value))
        assertTrue(
            "胶囊右边界 ${chip.right} 压到了主数字左边界 ${value.left}",
            chip.right <= value.left,
        )
    }
}
