package com.example.aqi.render

import android.content.Context
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import com.example.aqi.R
import com.example.aqi.core.AqiLevel
import com.example.aqi.core.AqiStandard
import com.example.aqi.core.AqiState
import com.example.aqi.core.AreaStat
import com.example.aqi.core.Freshness
import com.example.aqi.widget.WidgetKind
import com.example.aqi.widget.WidgetRenderer
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * 「数字有没有被切掉」这件事必须量，不能看。
 *
 * v0.4.2 的 2×1 卡把 17sp 的主数字改成了 36sp，同时又给胶囊写了「Worst」，
 * 结果 184 只剩「18」——截图上一眼能看出来，但代码里两个数字都不算错，
 * 错的是「宽度预算」和「字号」这两件事被写在了两个地方，没人负责对账。
 *
 * 这里直接把 RemoteViews apply 成真实 View，量三件事：
 *   1. 主数字的文本宽度 ≤ 控件可用宽度（否则会被裁掉最后一位）；
 *   2. 区域数字同样不能超宽；
 *   3. 两者在 2×1 最窄尺寸（110×60dp）下也不能互相挤。
 */
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

    /** 文本自然宽度 vs 控件能给到的宽度（含 padding 扣减）。 */
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
        // 110×60dp ≈ 2×1 在 4 列桌面上的最窄尺寸；60 / 184 / 320 / 500 都过一遍
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
        // 4×2 的最小尺寸（部分桌面只给 250×100）
        listOf(184, 500).forEach { assertValueFits(WidgetKind.MAIN, it, 250, 100) }
    }

    /** 胶囊 + 主数字必须同时放得下：2×1 最窄宽度是最容易翻车的地方。 */
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
