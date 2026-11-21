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

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], application = android.app.Application::class)
class GaugePainterRenderTest {

    private val outDir = File("build/render-check").apply { mkdirs() }

    private val pal: IntArray by lazy {
        GaugePainter.palette(RuntimeEnvironment.getApplication() as Context)
    }

    private fun save(bmp: Bitmap, name: String) {
        FileOutputStream(File(outDir, name)).use {
            bmp.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }

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
        save(BgPainter.strip(0xFFFF7E00.toInt(), 0xFFFFD400.toInt(), ta, ba), "bg_light_cross.png")
        save(BgPainter.strip(0xFFFF3B30.toInt(), 0xFFFF3B30.toInt(), ta, ba), "bg_light_same.png")
    }

    @Test
    fun segmentMappingIsCorrect() {
        assertEquals(0 to 0.0f, GaugePainter.segmentOf(0).let { it.first to (it.second * 1000).toInt() / 1000f })
        assertEquals(0, GaugePainter.segmentOf(45).first)
        assertEquals(0.9f, GaugePainter.segmentOf(45).second, 0.001f)
        assertEquals(0, GaugePainter.segmentOf(50).first)
        assertEquals(1.0f, GaugePainter.segmentOf(50).second, 0.001f)
        assertEquals(1, GaugePainter.segmentOf(51).first)
        assertEquals(0.0f, GaugePainter.segmentOf(51).second, 0.001f)
        assertEquals(1, GaugePainter.segmentOf(76).first)
        assertEquals(0.51f, GaugePainter.segmentOf(76).second, 0.02f)
        assertEquals(3, GaugePainter.segmentOf(152).first)
        assertEquals(0.02f, GaugePainter.segmentOf(152).second, 0.01f)
        assertEquals(5, GaugePainter.segmentOf(320).first)
        assertEquals(0.095f, GaugePainter.segmentOf(320).second, 0.01f)
        assertEquals(5, GaugePainter.segmentOf(9999).first)
        assertEquals(1.0f, GaugePainter.segmentOf(9999).second, 0.001f)
    }

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
        val a0 = sampleAt(-60.0)
        assertTrue("0 分时第一格应只有极淡底轨，实测 alpha=$a0", a0 in 20..60)

        val bmp50 = GaugePainter.ring(size, 50, pal, false)
        val a50 = Color.alpha(
            bmp50.getPixel((cx + r * Math.cos(Math.toRadians(-60.0))).toInt(),
                (cx + r * Math.sin(Math.toRadians(-60.0))).toInt()),
        )
        assertEquals("50 分时第一格应为实色", 255, a50)
    }
}
