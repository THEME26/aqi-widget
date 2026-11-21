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

        listOf(R.id.tab_detail, R.id.tab_settings, R.id.tab_today).forEach { id ->
            activity.findViewById<android.view.View>(id).performClick()
        }

        val seg = activity.findViewById<SegmentedControl>(R.id.seg_standard)
        org.junit.Assert.assertEquals("seg_standard 应有两项", 2, seg.childCount)

        controller.pause().stop().destroy()
    }

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
        prefs.areaSource = null

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
