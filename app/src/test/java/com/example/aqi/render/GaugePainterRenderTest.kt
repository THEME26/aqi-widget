package com.example.aqi.render

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.io.FileOutputStream

/**
 * 用 Robolectric 的 NATIVE 图形模式**真正跑一遍 Canvas**，把结果写成 PNG。
 *
 * 为什么非要这样：沙箱里没有模拟器，也没法把 APK 装到真机上让 AI 看一眼。
 * 而「环画出来是什么样」恰恰是这个版本的全部内容。只读代码无法证明
 * drawArc 的起始角方向、strokeCap 造成的缝宽、端点圆点的位置是对的 ——
 * 这些都是像素级事实。
 *
 * 产出落在 `build/render-check/`，由 tools/verify_v030_render.py 再拿去做
 * 像素断言（段数、填充比例、颜色）。
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
// 不加载 AqiApp：它在 onCreate 里初始化 WorkManager，JVM 单测环境下会抛
// IllegalStateException。渲染测试只关心 Canvas，用裸 Application 即可。
@Config(sdk = [34], application = android.app.Application::class)
class GaugePainterRenderTest {

    private val outDir = File("build/render-check").apply { mkdirs() }

    /** 真实调色板：来自 colors.xml 的 aqi_*_ink，浅色主题。 */
    private val pal: IntArray by lazy {
        GaugePainter.palette(RuntimeEnvironment.getApplication() as Context)
    }

    private fun save(bmp: Bitmap, name: String) {
        FileOutputStream(File(outDir, name)).use {
            bmp.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }

    /** 六个值各画一张，覆盖「同档内不同位置」和「跨档」。 */
    @Test
    fun renderRings() {
        listOf(0, 25, 45, 68, 152, 320).forEach { v ->
            save(GaugePainter.ring(198, v, pal, false), "ring_light_$v.png")
        }
        listOf(45, 152).forEach { v ->
            save(GaugePainter.ring(198, v, pal, true), "ring_dark_$v.png")
        }
        save(GaugePainter.ring(198, -1, pal, false), "ring_light_none.png")
    }

    @Test
    fun renderBars() {
        listOf(45, 152, 320).forEach { v ->
            save(GaugePainter.bar(252, v, pal, false, 12), "bar_light_$v.png")
            save(GaugePainter.bar(252, v, pal, true, 12), "bar_dark_$v.png")
        }
    }

    @Test
    fun renderBgStrip() {
        val (ta, ba) = BgPainter.alphaOf(false)
        // 跨档：区域 152（橙）→ 本站 68（黄）
        save(BgPainter.strip(0xFFFF7E00.toInt(), 0xFFFFD400.toInt(), ta, ba), "bg_light_cross.png")
        // 同档：198 与 152 都是红
        save(BgPainter.strip(0xFFFF3B30.toInt(), 0xFFFF3B30.toInt(), ta, ba), "bg_light_same.png")
    }

    // ---------------- 几何断言（纯逻辑，不依赖像素） ----------------

    @Test
    fun segmentMappingIsCorrect() {
        assertEquals(0 to 0.0f, GaugePainter.segmentOf(0).let { it.first to (it.second * 1000).toInt() / 1000f })
        // 45 分 = 第 1 格填 90%（45/50）
        assertEquals(0, GaugePainter.segmentOf(45).first)
        assertEquals(0.9f, GaugePainter.segmentOf(45).second, 0.001f)
        // 50 分 = 第 1 格满
        assertEquals(0, GaugePainter.segmentOf(50).first)
        assertEquals(1.0f, GaugePainter.segmentOf(50).second, 0.001f)
        // 51 分 = 第 2 格刚起步（0%）；76 分 = 第 2 格正好一半
        assertEquals(1, GaugePainter.segmentOf(51).first)
        assertEquals(0.0f, GaugePainter.segmentOf(51).second, 0.001f)
        assertEquals(1, GaugePainter.segmentOf(76).first)
        assertEquals(0.51f, GaugePainter.segmentOf(76).second, 0.02f)
        // 152 分 = 第 4 格 2%：这是设计稿里「有意义」的核心证据
        assertEquals(3, GaugePainter.segmentOf(152).first)
        assertEquals(0.02f, GaugePainter.segmentOf(152).second, 0.01f)
        // 320 分 = 第 6 格 9.5%（(320-301)/199）
        assertEquals(5, GaugePainter.segmentOf(320).first)
        assertEquals(0.095f, GaugePainter.segmentOf(320).second, 0.01f)
        // 越界收敛
        assertEquals(5, GaugePainter.segmentOf(9999).first)
        assertEquals(1.0f, GaugePainter.segmentOf(9999).second, 0.001f)
    }

    /**
     * 像素级：0 分时环上不应有任何实色弧；50 分时第一格整段是实色。
     * 采样点取第一格中点：12 点方向顺时针 30° = Canvas 角 -60°。
     */
    @Test
    fun ringFillMatchesValue() {
        val size = 198
        val r = size * 0.405f
        val cx = size / 2f
        fun sampleAt(deg: Double): Int {
            val a = Math.toRadians(deg)
            return Color.alpha(
                GaugePainter.ring(size, 0, pal, false).getPixel(
                    (cx + r * Math.cos(a)).toInt(),
                    (cx + r * Math.sin(a)).toInt(),
                ),
            )
        }
        // 0 分：第一格只有 13% 的底轨
        val a0 = sampleAt(-60.0)
        assertTrue("0 分时第一格应只有极淡底轨，实测 alpha=$a0", a0 in 20..60)

        // 50 分：第一格整段 100%
        val bmp50 = GaugePainter.ring(size, 50, pal, false)
        val a50 = Color.alpha(
            bmp50.getPixel((cx + r * Math.cos(Math.toRadians(-60.0))).toInt(),
                (cx + r * Math.sin(Math.toRadians(-60.0))).toInt()),
        )
        assertEquals("50 分时第一格应为实色", 255, a50)
    }
}
