package com.example.aqi.render

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.example.aqi.R
import com.example.aqi.core.AqiFormat
import com.example.aqi.core.AqiLevel
import com.example.aqi.core.AqiStandard
import com.example.aqi.core.AqiState
import com.example.aqi.core.AreaStat
import com.example.aqi.core.Freshness
import com.example.aqi.widget.WidgetKind
import com.example.aqi.widget.WidgetRenderer
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

/**
 * v0.4.8 的陈旧态去色：**只去色相，不动数与形**。
 *
 * 这一版的全部主张就两句，所以两条都要有像素级证据：
 *
 *  1. 陈旧时卡片上看不到任何档色（否则等于替数据源保证「此刻空气就是这样」）；
 *  2. 陈旧时环填到第几格、数字是几，**一个像素都没变**（否则用户连
 *     「上次大概什么水平」都读不出来，那还不如直接显示 – –）。
 *
 * 第 2 条比第 1 条更容易写错：把整张位图套一个 saturation=0 的 ColorMatrix
 * 是最省事的做法，但它会把淡底轨和实填充压到同一个灰度，六格边界随之消失。
 * 所以这里用「实色像素个数必须与新鲜态一致」来钉住几何。
 */
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

    // ---------------- 1. 颜色：档色必须全部消失 ----------------

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

        // 动作词（「需口罩」）与周边胶囊也得一起压灰，否则彩色字会浮在灰卡上。
        // 动作词取更淡的一档：它是「据此该怎么办」的推断，比数字更不可信。
        assertEquals(color(R.color.text_dim), stale.findViewById<TextView>(R.id.aqi_level).currentTextColor)
        assertEquals(neutral, stale.findViewById<TextView>(R.id.aqi_area_value).currentTextColor)
    }

    @Test
    fun staleLevelKeepsActionWord() {
        // 去色不等于抹掉内容：动作词还得在，用户才有东西可读
        val stale = WidgetRenderer.render(ctx, WidgetKind.MAIN, state(152, stale = true))
            .apply(ctx, FrameLayout(ctx))
        val text = stale.findViewById<TextView>(R.id.aqi_level).text.toString()
        assertTrue("陈旧时动作词被清空了：$text", text.isNotBlank())
    }

    // ---------------- 2. 几何：填到第几格不能变 ----------------

    /** 实色（alpha=255）像素的个数。淡底轨 alpha 只有 33~56，被排除。 */
    private fun solidPixels(bmp: Bitmap): Int {
        var n = 0
        for (y in 0 until bmp.height) {
            for (x in 0 until bmp.width) {
                if (Color.alpha(bmp.getPixel(x, y)) > 200) n++
            }
        }
        return n
    }

    /** 实色像素的平均饱和度（max-min / max，0 = 纯灰）。 */
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
        // 50 分：第一格整段实色，样本量足够，不会因为 2% 的一段弧产生抖动
        val fresh = GaugePainter.ring(198, 50, pal, false)
        val stale = GaugePainter.ring(198, 50, muted, false, mono = true)

        val sf = meanSaturation(fresh)
        val ss = meanSaturation(stale)
        assertTrue("新鲜态应是有色弧，实测饱和度 $sf", sf > 0.6f)
        assertTrue("陈旧态几乎无饱和度，实测 $ss（新鲜 $sf）", ss < 0.3f)

        // 形状一致：同一个值、同一次绘制，只有颜色参数不同
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

    /** mono 只影响底轨：无数据（-1）那条路径本来就用 NEUTRAL，不能被 mono 破坏。 */
    @Test
    fun monoDoesNotDisturbNoDataRing() {
        val a = GaugePainter.ring(198, -1, GaugePainter.palette(ctx), false)
        val b = GaugePainter.ring(198, -1, GaugePainter.palette(ctx), false, mono = true)
        assertEquals(solidPixels(a), solidPixels(b))
        assertTrue("无数据态本来就没有实色弧", solidPixels(a) == 0)
    }

    // ---------------- 3. 相对时间文案 ----------------

    @Test
    fun agoPicksMinuteHourDay() {
        val now = 1_800_000_000_000L
        assertEquals("20 分钟前", AqiFormat.ago(now - 20 * 60_000L, ctx, now))
        assertEquals("3 小时前", AqiFormat.ago(now - 3 * 3_600_000L, ctx, now))
        assertEquals("2 天前", AqiFormat.ago(now - 2 * 86_400_000L, ctx, now))
        // 时间戳缺失（旧缓存）→ 空串，调用方退回「数据陈旧」
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

    /** 4×2 的元信息行不再重复时间：陈旧时那行只留「站名 · 污染物」。 */
    @Test
    fun metaLineDoesNotRepeatTheAge() {
        val stale = WidgetRenderer.render(ctx, WidgetKind.MAIN, state(152, stale = true))
            .apply(ctx, FrameLayout(ctx))
        val meta = stale.findViewById<TextView>(R.id.aqi_meta).text.toString()
        assertTrue("元信息里不该再出现时间：$meta", !meta.contains("小时") && !meta.contains(":"))
        assertTrue(meta.contains("奥体中心"))
    }

    // ---------------- 4. 四个尺寸的快照 ----------------

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
        // 同参数的彩色版放在一起，肉眼一比就知道「灰了没有、少没少东西」
        shot(WidgetKind.MAIN, state(152, false), 288, 110, "stale_main_fresh_ref")
    }

    private fun save(bmp: Bitmap, name: String) {
        FileOutputStream(File(outDir, name)).use {
            bmp.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }
}
