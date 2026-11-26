package io.github.theme26.aqibar.render

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import androidx.core.content.ContextCompat

import io.github.theme26.aqibar.R
import io.github.theme26.aqibar.core.AqiLevel

object GaugePainter {

    private const val SEG_DEG = 60.0f
    private const val GAP_DEG = 3.0f

    private const val START_DEG = -90f
    private const val RADIUS_RATIO = 0.405f
    private const val STROKE_RATIO = 0.052f

    private val RING = intArrayOf(
        0xFF00E400.toInt(),
        0xFFFFD400.toInt(),
        0xFFFF7E00.toInt(),
        0xFFFF3B30.toInt(),
        0xFF8F3F97.toInt(),
        0xFFB3123E.toInt(),
    )

    fun palette(ctx: android.content.Context): IntArray =
        AqiLevel.values().map { it.textColorInt(ctx) }.toIntArray()

    fun mutedPalette(ctx: android.content.Context): IntArray {
        val g = ContextCompat.getColor(ctx, R.color.text_dim)
        return IntArray(6) { g }
    }

    fun ringColor(level: AqiLevel): Int = when (level) {
        AqiLevel.GOOD -> RING[0]
        AqiLevel.MODERATE -> RING[1]
        AqiLevel.USG -> RING[2]
        AqiLevel.UNHEALTHY -> RING[3]
        AqiLevel.VERY_UNHEALTHY -> RING[4]
        AqiLevel.HAZARDOUS -> RING[5]
    }

    private val RANGE = arrayOf(
        0 to 50, 51 to 100, 101 to 150, 151 to 200, 201 to 300, 301 to 500,
    )

    private const val NEUTRAL = 0xFF8A97A8.toInt()

    fun segmentOf(value: Int): Pair<Int, Float> {
        val v = value.coerceIn(0, 500)
        for (i in RANGE.indices) {
            val (from, to) = RANGE[i]
            if (v <= to) {
                val span = (to - from).toFloat().coerceAtLeast(1f)
                return i to ((v - from) / span).coerceIn(0f, 1f)
            }
        }
        return 5 to 1f
    }

    fun ring(px: Int, value: Int, palette: IntArray, dark: Boolean, mono: Boolean = false): Bitmap {
        val size = px.coerceIn(48, 320)
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val cx = size / 2f
        val cy = size / 2f
        val r = size * RADIUS_RATIO
        val stroke = size * STROKE_RATIO
        val rect = RectF(cx - r, cy - r, cx + r, cy + r)

        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = stroke
            strokeCap = Paint.Cap.BUTT
        }
        val cap = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }

        val trackAlpha = if (dark) 0.22f else 0.13f
        for (i in 0 until 6) {
            fill.color = withAlpha(if (value < 0 || mono) NEUTRAL else RING[i], trackAlpha)
            c.drawArc(rect, segStart(i) + GAP_DEG / 2, SEG_DEG - GAP_DEG, false, fill)
        }
        if (value < 0) return bmp

        val (idx, frac) = segmentOf(value)
        for (i in 0 until idx) {
            fill.color = withAlpha(palette[i], 0.38f)
            c.drawArc(rect, segStart(i) + GAP_DEG / 2, SEG_DEG - GAP_DEG, false, fill)
        }
        if (frac > 0.004f) {
            val sweep = (SEG_DEG - GAP_DEG) * frac
            fill.color = palette[idx]
            c.drawArc(rect, segStart(idx) + GAP_DEG / 2, sweep, false, fill)
            val ang = Math.toRadians((segStart(idx) + GAP_DEG / 2 + sweep).toDouble())
            cap.color = palette[idx]
            c.drawCircle(
                (cx + r * Math.cos(ang)).toFloat(),
                (cy + r * Math.sin(ang)).toFloat(),
                stroke * 0.62f,
                cap,
            )
        }
        return bmp
    }

    fun halo(px: Int, value: Int, palette: IntArray): Bitmap {
        val size = px.coerceIn(60, 400)
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val k = 66f / 78f
        val r = size * RADIUS_RATIO * k
        val stroke = size * STROKE_RATIO * k
        val rect = RectF(size / 2f - r, size / 2f - r, size / 2f + r, size / 2f + r)
        val (idx, frac) = segmentOf(value)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = stroke * 1.45f
            strokeCap = Paint.Cap.ROUND
            maskFilter = android.graphics.BlurMaskFilter(stroke * 1.0f, android.graphics.BlurMaskFilter.Blur.NORMAL)
        }
        for (i in 0 until idx) {
            paint.color = withAlpha(palette[i], 0.38f)
            c.drawArc(rect, segStart(i) + GAP_DEG / 2, SEG_DEG - GAP_DEG, false, paint)
        }
        if (frac > 0.01f) {
            paint.color = withAlpha(palette[idx], 0.52f)
            c.drawArc(rect, segStart(idx) + GAP_DEG / 2, (SEG_DEG - GAP_DEG) * frac, false, paint)
        }
        return bmp
    }

    fun bar(
        wPx: Int,
        value: Int,
        palette: IntArray,
        dark: Boolean,
        heightPx: Int,
        mono: Boolean = false,
    ): Bitmap {
        val w = wPx.coerceIn(24, 900)
        val h = heightPx.coerceIn(4, 40)
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        val gap = h * 0.5f
        val cellW = (w - gap * 5) / 6f
        val radius = h / 2f
        val (idx, frac) = if (value < 0) 0 to 0f else segmentOf(value)

        for (i in 0 until 6) {
            val left = i * (cellW + gap)
            val right = left + cellW
            val rr = RectF(left, 0f, right, h.toFloat())
            paint.color = withAlpha(
                if (value < 0 || mono) NEUTRAL else RING[i],
                if (dark) 0.18f else 0.14f,
            )
            c.drawRoundRect(rr, radius, radius, paint)

            val f = when {
                i < idx -> 1f
                i == idx -> frac
                else -> 0f
            }
            if (f > 0.004f) {
                paint.color = palette[i]
                val right = left + cellW * f
                if (right - left < h) {
                    c.drawCircle(left + h / 2f, h / 2f, h / 2f, paint)
                } else {
                    c.drawRoundRect(RectF(left, 0f, right, h.toFloat()), radius, radius, paint)
                }
            }
        }
        return bmp
    }

    private fun segStart(i: Int): Float = START_DEG + i * SEG_DEG

    private fun withAlpha(color: Int, alpha: Float): Int =
        Color.argb((alpha * 255).toInt().coerceIn(0, 255), Color.red(color), Color.green(color), Color.blue(color))
}

object BgPainter {

    private const val STRIP_H = 256
    private const val STRIP_W = 2

    fun strip(top: Int, bottom: Int, topAlpha: Float, bottomAlpha: Float): Bitmap {
        val bmp = Bitmap.createBitmap(STRIP_W, STRIP_H, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.shader = LinearGradient(
            0f, 0f, 0f, STRIP_H.toFloat(),
            withAlpha(top, topAlpha), withAlpha(bottom, bottomAlpha),
            Shader.TileMode.CLAMP,
        )
        c.drawRect(0f, 0f, STRIP_W.toFloat(), STRIP_H.toFloat(), paint)
        return bmp
    }

    fun alphaOf(dark: Boolean): Pair<Float, Float> =
        if (dark) 0.130f to 0.170f else 0.085f to 0.112f

    private fun withAlpha(color: Int, alpha: Float): Int =
        Color.argb(
            (alpha * 255).toInt().coerceIn(0, 255),
            Color.red(color), Color.green(color), Color.blue(color),
        )
}
