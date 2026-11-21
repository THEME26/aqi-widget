package com.example.aqi.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.MotionEvent
import android.view.View
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * 九宫格点击（v0.4.5，替代 v0.4.4 的圆环点击测试）。
 *
 * 图是 Canvas 自绘的，每格的位置只有 onDraw 才知道 —— 所以这里先真的 draw 一次，
 * 再往某一格的中心 dispatch 一次触摸，验证回调索引。
 *
 * 重点验证的是**行列映射**：
 * ```
 *   NW(8)  N(1)  NE(2)
 *    W(7)  (0)   E(3)
 *   SW(6)  S(5)  SE(4)
 * ```
 * 这个映射写错不会崩、也不会报警，只会让用户点「东北」看到「西北」的站名。
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "zh-rCN-xxhdpi", application = android.app.Application::class)
class AreaGridTouchTest {

    private val ctx = RuntimeEnvironment.getApplication()
    private val d = ctx.resources.displayMetrics.density
    private val sideDp = 176f

    private fun dp(v: Float): Float = v * d

    private fun grid(values: List<Int>, onTap: (Int) -> Unit): View {
        val v = AreaGridView(ctx)
        v.setValues(values)
        v.onPointTap = onTap
        val px = dp(sideDp).toInt()
        v.measure(
            View.MeasureSpec.makeMeasureSpec(px, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(px, View.MeasureSpec.EXACTLY),
        )
        v.layout(0, 0, px, px)
        v.draw(Canvas(Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888)))
        return v
    }

    private fun tap(v: View, x: Float, y: Float) {
        val down = MotionEvent.obtain(0, 0, MotionEvent.ACTION_DOWN, x, y, 0)
        val up = MotionEvent.obtain(0, 0, MotionEvent.ACTION_UP, x, y, 0)
        v.dispatchTouchEvent(down)
        v.dispatchTouchEvent(up)
        down.recycle()
        up.recycle()
    }

    /** 第 row 行、第 col 列格子的中心（3×3，gap 4dp，与 AreaGridView 内部一致）。 */
    private fun centerOf(row: Int, col: Int): Pair<Float, Float> {
        val total = dp(sideDp)
        val gap = dp(4f)
        val cell = (total - gap * 2) / 3f
        return (col * (cell + gap) + cell / 2f) to (row * (cell + gap) + cell / 2f)
    }

    private fun tapCell(row: Int, col: Int): Int {
        var hit = -1
        val v = grid(List(9) { 100 + it }) { hit = it }
        val (x, y) = centerOf(row, col)
        tap(v, x, y)
        return hit
    }

    @Test
    fun centerIsIndexZero() = assertEquals(0, tapCell(1, 1))

    @Test
    fun northIsTopMiddle() = assertEquals(1, tapCell(0, 1))

    @Test
    fun northeastIsTopRight() = assertEquals(2, tapCell(0, 2))

    @Test
    fun eastIsMiddleRight() = assertEquals(3, tapCell(1, 2))

    @Test
    fun southeastIsBottomRight() = assertEquals(4, tapCell(2, 2))

    @Test
    fun southIsBottomMiddle() = assertEquals(5, tapCell(2, 1))

    @Test
    fun southwestIsBottomLeft() = assertEquals(6, tapCell(2, 0))

    @Test
    fun westIsMiddleLeft() = assertEquals(7, tapCell(1, 0))

    @Test
    fun northwestIsTopLeft() = assertEquals(8, tapCell(0, 0))

    /** 拖动（手指移动超过 12dp）不应触发点击，否则翻页时会误弹详情。 */
    @Test
    fun swipeDoesNotTriggerTap() {
        var hit = -1
        val v = grid(List(9) { 100 + it }) { hit = it }
        val (x, y) = centerOf(1, 1)
        val down = MotionEvent.obtain(0, 0, MotionEvent.ACTION_DOWN, x, y, 0)
        val up = MotionEvent.obtain(0, 0, MotionEvent.ACTION_UP, x + dp(40f), y, 0)
        v.dispatchTouchEvent(down)
        v.dispatchTouchEvent(up)
        down.recycle()
        up.recycle()
        assertEquals(-1, hit)
    }

    /** 没有数据时（values 不是 9 个），点击不回调任何索引。 */
    @Test
    fun emptyGridDoesNotRespond() {
        var hit = -1
        val v = grid(emptyList()) { hit = it }
        val (x, y) = centerOf(1, 1)
        tap(v, x, y)
        assertEquals(-1, hit)
    }
}
