package com.example.aqi.ui

import android.app.Application
import androidx.work.Configuration
import androidx.work.WorkManager
import com.example.aqi.R
import com.example.aqi.core.AqiSource
import com.example.aqi.core.AqiStandard
import com.example.aqi.data.AqiPrefs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * 真的把 MainActivity 起起来。
 *
 * 页面渲染测试只验证「布局 + 绑定逻辑」，抓不到 [MainActivity.wireControls] 里的
 * findViewById 断链、prefs 读写异常、SegmentedControl 回调里 NPE 这类问题 ——
 * 那些只会在点开 App 的瞬间崩。
 *
 * 用裸 Application 而不是 AqiApp：AqiApp 会在 onCreate 里初始化 WorkManager，
 * 在 JVM 里既慢又容易被 SQLite 初始化顺序坑到。这里显式 initialize 一次即可。
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], application = Application::class, qualifiers = "w411dp-h891dp-xxhdpi")
class MainActivityTest {

    @Before
    fun initWorkManager() {
        val ctx = RuntimeEnvironment.getApplication()
        if (!WorkManager.isInitialized()) {
            WorkManager.initialize(ctx, Configuration.Builder().build())
        }
    }

    @Test
    fun activityStartsAndSwitchesTabs() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        val activity = controller.get()
        assertNotNull(activity)

        // 三个 tab 逐个点一遍：每一次都会走 selectTab（改 tint、切 visibility）
        listOf(R.id.tab_detail, R.id.tab_settings, R.id.tab_today).forEach { id ->
            activity.findViewById<android.view.View>(id).performClick()
        }

        // 设置页的五个分段控件都应已有条目（否则就是 setItems 没走到）
        val seg = activity.findViewById<SegmentedControl>(R.id.seg_standard)
        org.junit.Assert.assertEquals("seg_standard 应有两项", 2, seg.childCount)

        controller.pause().stop().destroy()
    }

    /**
     * 站点模式的设置页不能留着一个没用的半径。
     *
     * 真机故障链：半径 10 公里 → 8 个探测点落在 10 公里外 → 回来的站也在半径边缘
     * → 被半径滤掉 → 「only 2 usable cells」→ 整块退回格点。用户当时完全看不出
     * 「半径」这个设置在站点模式下毫无意义。现在整组藏掉，换一句说明。
     */
    @Test
    fun waqiAreaHidesRadiusBlock() {
        val ctx = RuntimeEnvironment.getApplication()
        val prefs = AqiPrefs(ctx)
        prefs.standard = AqiStandard.US
        prefs.token = "0123456789abcdef"
        prefs.areaSource = AqiSource.WAQI_RECALC

        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        val act = controller.get()
        assertEquals(
            "站点模式没有半径这个概念，整组必须收起",
            android.view.View.GONE,
            act.findViewById<android.view.View>(R.id.radius_block).visibility,
        )
        assertEquals(
            "收起的同时要给一句说明，否则用户以为半径这一项没了",
            android.view.View.VISIBLE,
            act.findViewById<android.view.View>(R.id.radius_rank_note).visibility,
        )
        controller.pause().stop().destroy()

        prefs.areaSource = null
    }

    @Test
    fun gridAreaKeepsRadiusBlock() {
        val ctx = RuntimeEnvironment.getApplication()
        val prefs = AqiPrefs(ctx)
        prefs.standard = AqiStandard.US
        prefs.areaSource = null           // 跟随主源 = OM

        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        val act = controller.get()
        assertEquals(
            android.view.View.VISIBLE,
            act.findViewById<android.view.View>(R.id.radius_block).visibility,
        )
        assertEquals(
            android.view.View.GONE,
            act.findViewById<android.view.View>(R.id.radius_rank_note).visibility,
        )
        controller.pause().stop().destroy()
    }
}
