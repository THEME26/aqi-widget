package io.github.theme26.aqibar.render

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import io.github.theme26.aqibar.R
import io.github.theme26.aqibar.core.AqiFormat
import io.github.theme26.aqibar.core.AqiLevel
import io.github.theme26.aqibar.core.AqiStandard
import io.github.theme26.aqibar.core.AqiState
import io.github.theme26.aqibar.core.AreaStat
import io.github.theme26.aqibar.core.Freshness
import io.github.theme26.aqibar.widget.WidgetKind
import io.github.theme26.aqibar.widget.WidgetRenderer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.io.FileOutputStream
import kotlin.math.abs

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "zh-rCN-xxhdpi", application = android.app.Application::class)
class StaleWidgetTest {

    private val ctx: Context = RuntimeEnvironment.getApplication()
    private val outDir = File("build/render-check").apply { mkdirs() }

    private fun state(v: Int, stale: Boolean, areaV: Int? = 198) = AqiState(
        value = v,
        level = AqiLevel.of(v),
        dominantPollutant = "pm2.5",
        subIndices = mapOf("pm2_5" to v),
        stationName = "奥体中心",
        distanceKm = 1.2,
        observedText = "",
        fetchedAtMillis = System.currentTimeMillis() - 3 * 3_600_000L,
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

    private fun color(id: Int): Int = ContextCompat.getColor(ctx, id)

    @Test
    fun staleTextFallsBackToNeutral() {
        val stale = WidgetRenderer.render(ctx, WidgetKind.MAIN, state(152, stale = true))
            .apply(ctx, FrameLayout(ctx))
        val fresh = WidgetRenderer.render(ctx, WidgetKind.MAIN, state(152, stale = false))
            .apply(ctx, FrameLayout(ctx))

        val neutral = color(R.color.text_secondary)
        val staleValue = stale.findViewById<TextView>(R.id.aqi_value).currentTextColor
        val freshValue = fresh.findViewById<TextView>(R.id.aqi_value).currentTextColor

        assertEquals("陈旧时大数字必须是中性灰", neutral, staleValue)
        assertNotEquals("新鲜态不该是中性灰", neutral, freshValue)
        assertEquals("新鲜态大数字用 text_primary", color(R.color.text_primary), freshValue)

        assertEquals(color(R.color.text_dim), stale.findViewById<TextView>(R.id.aqi_level).currentTextColor)
        assertEquals(neutral, stale.findViewById<TextView>(R.id.aqi_area_value).currentTextColor)
    }

    @Test
    fun staleLevelKeepsActionWord() {
        val stale = WidgetRenderer.render(ctx, WidgetKind.MAIN, state(152, stale = true))
            .apply(ctx, FrameLayout(ctx))
        val text = stale.findViewById<TextView>(R.id.aqi_level).text.toString()
        assertTrue("陈旧时动作词被清空了：$text", text.isNotBlank())
    }

    private fun solidPixels(bmp: Bitmap): Int {
        var n = 0
        for (y in 0 until bmp.height) {
            for (x in 0 until bmp.width) {
                if (Color.alpha(bmp.getPixel(x, y)) > 200) n++
            }
        }
        return n
    }

    private fun meanSaturation(bmp: Bitmap): Float {
        var sum = 0f
        var n = 0
        for (y in 0 until bmp.height) {
            for (x in 0 until bmp.width) {
                val c = bmp.getPixel(x, y)
                if (Color.alpha(c) <= 200) continue
                val r = Color.red(c)
                val g = Color.green(c)
                val b = Color.blue(c)
                val max = maxOf(r, g, b)
                if (max == 0) continue
                sum += (max - minOf(r, g, b)).toFloat() / max
                n++
            }
        }
        return if (n == 0) 0f else sum / n
    }

    @Test
    fun ringLosesHueButKeepsGeometry() {
        val pal = GaugePainter.palette(ctx)
        val muted = GaugePainter.mutedPalette(ctx)
        val fresh = GaugePainter.ring(198, 50, pal, false)
        val stale = GaugePainter.ring(198, 50, muted, false, mono = true)

        val sf = meanSaturation(fresh)
        val ss = meanSaturation(stale)
        assertTrue("新鲜态应是有色弧，实测饱和度 $sf", sf > 0.6f)
        assertTrue("陈旧态几乎无饱和度，实测 $ss（新鲜 $sf）", ss < 0.3f)

        val nf = solidPixels(fresh)
        val ns = solidPixels(stale)
        assertTrue("实色像素数应一致（形没变），新鲜 $nf / 陈旧 $ns", abs(nf - ns) <= 2)
        save(fresh, "stale_ring_fresh.png")
        save(stale, "stale_ring_muted.png")
    }

    @Test
    fun barLosesHueButKeepsGeometry() {
        val fresh = GaugePainter.bar(252, 152, GaugePainter.palette(ctx), false, 12)
        val stale = GaugePainter.bar(252, 152, GaugePainter.mutedPalette(ctx), false, 12, true)
        assertTrue(meanSaturation(fresh) > 0.6f)
        assertTrue(meanSaturation(stale) < 0.3f)
        assertTrue("横条长度不能变", abs(solidPixels(fresh) - solidPixels(stale)) <= 2)
    }

    @Test
    fun monoDoesNotDisturbNoDataRing() {
        val a = GaugePainter.ring(198, -1, GaugePainter.palette(ctx), false)
        val b = GaugePainter.ring(198, -1, GaugePainter.palette(ctx), false, mono = true)
        assertEquals(solidPixels(a), solidPixels(b))
        assertTrue("无数据态本来就没有实色弧", solidPixels(a) == 0)
    }

    @Test
    fun agoPicksMinuteHourDay() {
        val now = 1_800_000_000_000L
        assertEquals("20 分钟前", AqiFormat.ago(now - 20 * 60_000L, ctx, now))
        assertEquals("3 小时前", AqiFormat.ago(now - 3 * 3_600_000L, ctx, now))
        assertEquals("2 天前", AqiFormat.ago(now - 2 * 86_400_000L, ctx, now))
        assertEquals("", AqiFormat.ago(0L, ctx, now))
    }

    @Test
    fun staleLineShowsHowLongAgo() {
        val stale = WidgetRenderer.render(ctx, WidgetKind.MAIN, state(152, stale = true))
            .apply(ctx, FrameLayout(ctx))
        val line = stale.findViewById<TextView>(R.id.aqi_stale)
        assertEquals(View.VISIBLE, line.visibility)
        assertEquals("3 小时前", line.text.toString())
        assertEquals(color(R.color.text_secondary), line.currentTextColor)

        val fresh = WidgetRenderer.render(ctx, WidgetKind.MAIN, state(152, stale = false))
            .apply(ctx, FrameLayout(ctx))
        assertEquals(View.GONE, fresh.findViewById<TextView>(R.id.aqi_stale).visibility)
    }

    @Test
    fun metaLineDoesNotRepeatTheAge() {
        val stale = WidgetRenderer.render(ctx, WidgetKind.MAIN, state(152, stale = true))
            .apply(ctx, FrameLayout(ctx))
        val meta = stale.findViewById<TextView>(R.id.aqi_meta).text.toString()
        assertTrue("元信息里不该再出现时间：$meta", !meta.contains("小时") && !meta.contains(":"))
        assertTrue(meta.contains("奥体中心"))
    }

    private fun shot(kind: WidgetKind, s: AqiState, wDp: Int, hDp: Int, name: String) {
        val view: View = WidgetRenderer.render(ctx, kind, s).apply(ctx, FrameLayout(ctx))
        val w = (wDp * ctx.resources.displayMetrics.density).toInt()
        val h = (hDp * ctx.resources.displayMetrics.density).toInt()
        view.measure(
            View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY),
        )
        view.layout(0, 0, w, h)
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        bmp.eraseColor(0xFFEDF0F5.toInt())
        view.draw(Canvas(bmp))
        save(bmp, "$name.png")
    }

    @Test
    fun renderAllSizesStale() {
        shot(WidgetKind.MAIN, state(152, true), 288, 110, "stale_main")
        shot(WidgetKind.COMPACT, state(152, true), 150, 74, "stale_compact")
        shot(WidgetKind.MINI_TALL, state(152, true), 74, 150, "stale_tall")
        shot(WidgetKind.MINI_SQUARE, state(152, true), 74, 74, "stale_square")
        shot(WidgetKind.MAIN, state(152, false), 288, 110, "stale_main_fresh_ref")
    }

    private fun save(bmp: Bitmap, name: String) {
        FileOutputStream(File(outDir, name)).use {
            bmp.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }
}
