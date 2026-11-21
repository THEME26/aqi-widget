package com.example.aqi.render

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import androidx.core.content.ContextCompat

import com.example.aqi.R
import com.example.aqi.core.AqiLevel

/**
 * 小部件用到的两张「动态图」全部运行时用 Canvas 画，不再预渲染 PNG。
 *
 * ## 为什么放弃预渲染
 * 六格环需要 6 档 × 21 个填充步进 = 126 张，乘 5 个密度是 630 张；APK 会涨 1 MB 以上。
 * 而这些图只被 66dp / 34dp 两个尺寸引用，且每次刷新只用到其中一张。
 * `RemoteViews` 禁止自定义 View，但**允许 setImageViewBitmap** —— 于是「画不了弧」
 * 这个限制其实不存在，只要把结果画进 Bitmap 就行。
 *
 * 传输成本：66dp @3x = 198px 的 ARGB_8888 约 157 KB，单次 updateAppWidget
 * （Binder 上限 1 MB）完全装得下；背景图只有 1×256 像素，可忽略。
 *
 * ## 六格量程的几何
 * - 6 段等分，每段 60°，从 3 点方向顺时针；段间留 2.4° 缝（两端各 1.2°）。
 * - 段内按该档区间百分比连续填充：45 分 = 第 1 格填 90%，152 分 = 第 4 格填 2%。
 * - 0–500 不做线性映射：45 分只画 32° 弧会像「加载失败」。
 */
object GaugePainter {

    private const val SEG_DEG = 60.0f
    /** 每段两端各留 1.5°，共 6 段 → 视觉缝 3°，一眼能数出六格（与设计稿一致）。 */
    private const val GAP_DEG = 3.0f

    /**
     * Canvas 的 drawArc 以 3 点方向为 0°、正角度顺时针；
     * 而设计稿的极坐标是「0° = 12 点方向，顺时针」（内部做了 -90 偏移）。
     * 这里直接写 -90f，让第一格从正上方开始 —— 否则整圈会整体转 90°，
     * 第二格跑到右下、第六格跑到右上，读数位置全错。
     */
    private const val START_DEG = -90f
    private const val RADIUS_RATIO = 0.405f   // 相对画布边长
    private const val STROKE_RATIO = 0.052f

    /**
     * 背景渐变用的**纯色**。不是 [AqiLevel.hex]：
     * 纯 #FFFF00、#FF0000、#7E0023 压过一档，在玻璃上更耐看。
     *
     * 注意：环和横条**不用**这套色，它们用 [palette] 的 ink 色 ——
     * 亮绿 #00E400 填满一整格时在浅色玻璃上刺眼，且与数字颜色不一致。
     */
    private val RING = intArrayOf(
        0xFF00E400.toInt(), // 优
        0xFFFFD400.toInt(), // 良
        0xFFFF7E00.toInt(), // 轻度
        0xFFFF3B30.toInt(), // 中度
        0xFF8F3F97.toInt(), // 重度
        0xFFB3123E.toInt(), // 严重
    )

    /**
     * 环与横条用的调色板：直接取 [AqiLevel] 的 **ink** 色（值来自 colors.xml，
     * `values-night` 自动给深色版），因此不需要再传 dark 决定颜色。
     *
     * 用 ink 而不是官方纯色，理由和数字文字一致：
     * #00E400 填满一格时在浅色玻璃上几乎发光，而 #15803D 才压得住。
     */
    fun palette(ctx: android.content.Context): IntArray =
        AqiLevel.values().map { it.textColorInt(ctx) }.toIntArray()

    /**
     * 陈旧态用的灰阶调色板（v0.4.8）。
     *
     * **颜色是一句断言**：「此刻的空气就是这个样子」。数据超过两个刷新周期没更新时，
     * 这句断言已经不成立 —— 继续画绿/橙/红，等于替服务端保证一个我们并不知道的现在。
     * 所以整卡去色，只留明度。
     *
     * 但「填到第几格」必须原样保留：灰阶只改变色相，不改变几何。
     * 已过格仍然是 38% 的淡灰、当前格仍然是实灰 + 端点圆点，
     * 一眼仍能读出「大概多少分」——只是不再声称它是此刻的分数。
     *
     * 为什么不直接把整张位图套一个 saturation=0 的 ColorMatrix：
     * 那样连 [NEUTRAL] 底轨也会被再压一次，暗色模式下整环会糊成一团灰，
     * 六格量程就数不出来了。逐笔上色能保住格与格的边界。
     */
    fun mutedPalette(ctx: android.content.Context): IntArray {
        val g = ContextCompat.getColor(ctx, R.color.text_dim)
        return IntArray(6) { g }
    }

    /**
     * 背景渐变用的档位纯色（见 [RING] 的注释）。
     * 用 when 显式映射而不是 ordinal —— 枚举顺序变动时编译器会在这里报错，
     * 而不是悄悄画错颜色。
     */
    fun ringColor(level: AqiLevel): Int = when (level) {
        AqiLevel.GOOD -> RING[0]
        AqiLevel.MODERATE -> RING[1]
        AqiLevel.USG -> RING[2]
        AqiLevel.UNHEALTHY -> RING[3]
        AqiLevel.VERY_UNHEALTHY -> RING[4]
        AqiLevel.HAZARDOUS -> RING[5]
    }

    /** 各档区间，与 [AqiLevel] 的 from/to 一致；单列出来是因为要算段内百分比。 */
    private val RANGE = arrayOf(
        0 to 50, 51 to 100, 101 to 150, 151 to 200, 201 to 300, 301 to 500,
    )

    /** 无数据时的中性环色。 */
    private const val NEUTRAL = 0xFF8A97A8.toInt()

    /** 段序号与段内比例；值越界时收敛到最后一格。 */
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

    /**
     * 画六格环。
     *
     * @param px   位图边长（像素）
     * @param value AQI；<0 表示无数据，画纯中性六格轨
     * @param dark 深色模式：底轨用提亮版档色，否则深底上紫/红两格会消失
     * @param mono 陈旧态（v0.4.8）：底轨不再铺淡彩虹，改用中性灰，
     *             否则一圈六色彩底加上灰色填充，看起来像「彩卡上贴了个灰圈」
     */
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
            strokeCap = Paint.Cap.BUTT      // 段内填充用平头，圆头会溢出到缝里
        }
        val cap = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }

        // 1) 底轨：每格用**亮色**的极淡版（不是 ink）—— 整环始终是一圈淡彩虹，
        //    读起来是「量程」。用 ink 会淡成一圈灰，六格数不出来。
        val trackAlpha = if (dark) 0.22f else 0.13f
        for (i in 0 until 6) {
            fill.color = withAlpha(if (value < 0 || mono) NEUTRAL else RING[i], trackAlpha)
            c.drawArc(rect, segStart(i) + GAP_DEG / 2, SEG_DEG - GAP_DEG, false, fill)
        }
        if (value < 0) return bmp

        val (idx, frac) = segmentOf(value)
        // 2) 已过格：淡填，读得出「走过哪几格」
        for (i in 0 until idx) {
            fill.color = withAlpha(palette[i], 0.38f)
            c.drawArc(rect, segStart(i) + GAP_DEG / 2, SEG_DEG - GAP_DEG, false, fill)
        }
        // 3) 当前格：实色，从段起点填到 frac
        if (frac > 0.004f) {
            val sweep = (SEG_DEG - GAP_DEG) * frac
            fill.color = palette[idx]
            c.drawArc(rect, segStart(idx) + GAP_DEG / 2, sweep, false, fill)
            // 端点圆点：让「停在哪」在 34dp 上也看得出来
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

    /**
     * 报警辉光：只让**已经填过的格**发光，而不是一整圈。
     *
     * v0.2.0 的光晕是「整圈模糊环」，在六格量程上会直接盖住六格 ——
     * 环里只填了 2% 的红色，外面却整圈红光，语义也是反的。
     * 改成沿填充段画一条加粗的弧再模糊，光就只出现在该亮的地方。
     *
     * @param px 光晕画布边长（78dp：环 66dp + 四周各 6dp）
     */
    fun halo(px: Int, value: Int, palette: IntArray): Bitmap {
        val size = px.coerceIn(60, 400)
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val k = 66f / 78f                     // 环只占光晕画布的 66/78
        val r = size * RADIUS_RATIO * k
        val stroke = size * STROKE_RATIO * k
        val rect = RectF(size / 2f - r, size / 2f - r, size / 2f + r, size / 2f + r)
        val (idx, frac) = segmentOf(value)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = stroke * 1.45f
            strokeCap = Paint.Cap.ROUND
            // 模糊半径压在 stroke 附近：再大就会向环内扩散，
            // 把六格的未填充部分也照亮，格与格的分界就消失了
            maskFilter = android.graphics.BlurMaskFilter(stroke * 1.0f, android.graphics.BlurMaskFilter.Blur.NORMAL)
        }
        // 已过的格 + 当前格一起发光：光晕的长度本身就是「超标了多少」
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

    /**
     * 六格微型横条（2×1 / 1×2 / 1×1 用）。
     * 每一格等宽，没走到的格子只留极淡底，走过的填实，当前格按比例填。
     *
     * @param wPx 位图宽；高度固定为 4dp 的量级，由调用方 fitXY 拉伸
     * @param mono 陈旧态（v0.4.8）：底轨也用中性灰，理由同 [ring]
     */
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
                    // 填充窄到不足一个圆角时，drawRoundRect 会退化成一根针。
                    // 改画一个直径 = 条高的圆点：语义是「刚进入这一格」。
                    c.drawCircle(left + h / 2f, h / 2f, h / 2f, paint)
                } else {
                    c.drawRoundRect(RectF(left, 0f, right, h.toFloat()), radius, radius, paint)
                }
            }
        }
        return bmp
    }

    /** 段起点角度（3 点方向为 0，顺时针）。 */
    private fun segStart(i: Int): Float = START_DEG + i * SEG_DEG

    private fun withAlpha(color: Int, alpha: Float): Int =
        Color.argb((alpha * 255).toInt().coerceIn(0, 255), Color.red(color), Color.green(color), Color.blue(color))
}

/**
 * 卡片背景：一条从上到下的连续渐变。
 *
 * 关键技巧：只画 **1 × 256** 的竖条，交给 ImageView 的 fitXY 横向拉满。
 * 因为渐变只沿纵向变化，横向恒定，拉伸不产生任何失真 ——
 * 而 1×256 的位图只有 1 KB，比 288×110 的完整背景（126 KB）小两个数量级。
 */
object BgPainter {

    private const val STRIP_H = 256
    /** 宽 2px 而不是 1px：GPU 双线性采样在 1px 宽的纹理上可能采到边界外，
       2px 保证任意放大倍数下横向都是纯色。 */
    private const val STRIP_W = 2

    /**
     * @param top    顶端颜色（周边区域档色）
     * @param bottom 底端颜色（本站档色）
     * @param topAlpha/bottomAlpha 两端不透明度；中间线性插值，不做任何回淡
     */
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

    /**
     * 玻璃卡底的渐变强度。深色模式要更高一点：同样的 alpha 叠在炭黑上
     * 比叠在白玻璃上暗得多，不提就和背景糊成一片。
     */
    fun alphaOf(dark: Boolean): Pair<Float, Float> =
        if (dark) 0.130f to 0.170f else 0.085f to 0.112f

    private fun withAlpha(color: Int, alpha: Float): Int =
        Color.argb(
            (alpha * 255).toInt().coerceIn(0, 255),
            Color.red(color), Color.green(color), Color.blue(color),
        )
}
