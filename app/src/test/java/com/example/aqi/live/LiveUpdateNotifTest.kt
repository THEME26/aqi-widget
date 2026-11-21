package com.example.aqi.live

import android.app.Application
import android.app.NotificationManager
import com.example.aqi.core.AqiLevel
import com.example.aqi.core.AqiState
import com.example.aqi.core.Freshness
import com.example.aqi.core.NotifPolicy
import com.example.aqi.data.AqiPrefs
import com.example.aqi.render.AqiNotifier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Live Update 这条路只有一次机会证明自己：请求发出去、系统采纳了没有。
 *
 * 这些用例钉的是**请求确实随通知发出**，不是「系统一定会采纳」——
 * 后者只能在真机上读回 flag，测试环境无法代替。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], application = Application::class)
class LiveUpdateNotifTest {

    private fun ctx() = RuntimeEnvironment.getApplication()

    private fun state(value: Int = 152) = AqiState(
        value = value,
        level = AqiLevel.entries[3],
        dominantPollutant = "pm25",
        subIndices = emptyMap(),
        stationName = "测试站",
        distanceKm = 1.2,
        observedText = "",
        fetchedAtMillis = 1_700_000_000_000L,
        freshness = Freshness.FRESH,
        attribution = "Open-Meteo",
    )

    private fun showWith(live: Boolean, value: Int = 152) {
        val c = ctx()
        AqiPrefs(c).apply {
            this.liveUpdate = live
            this.notifPolicy = NotifPolicy.ALWAYS
        }
        AqiNotifier(c).show(state(value))
    }

    private fun postedNotification(): android.app.Notification? {
        val mgr = ctx().getSystemService(NotificationManager::class.java)
        return mgr.activeNotifications.firstOrNull { it.id == AqiNotifier.NOTIF_ID }?.notification
    }

    @Test
    fun requestsPromotionWhenEnabled() {
        showWith(live = true)
        val n = postedNotification()
        assertNotNull("通知应已发出", n)
        assertTrue(
            "开启后必须带上 promote 请求，否则系统永远不会考虑它",
            n!!.extras.getBoolean(LiveUpdate.EXTRA_REQUEST_PROMOTED, false),
        )
    }

    @Test
    fun omitsRequestWhenDisabled() {
        showWith(live = false)
        val n = postedNotification()
        assertNotNull(n)
        assertFalse(
            "关掉开关后不应再请求提升",
            n!!.extras.getBoolean(LiveUpdate.EXTRA_REQUEST_PROMOTED, false),
        )
    }

    /**
     * 低版本也要发：extra 是纯数据，系统不认识就忽略。
     * 若这里改成按版本判断，SDK 30 的机器上就永远测不到「请求发了吗」。
     */
    @Test
    fun requestsEvenOnOlderSdk() {
        assertEquals("测试跑在 SDK 30，不应支持 Live Updates", false, LiveUpdate.supported)
        showWith(live = true)
        val n = postedNotification()
        assertTrue(n!!.extras.getBoolean(LiveUpdate.EXTRA_REQUEST_PROMOTED, false))
    }

    /** 优/良 + 仅污染时：通知压根不存在，此时不能显示「系统拒绝」，那是两回事。 */
    @Test
    fun promotedStateIsNullWhenNoNotification() {
        val c = ctx()
        AqiPrefs(c).apply {
            this.liveUpdate = true
            this.notifPolicy = NotifPolicy.ALERT_ONLY
        }
        AqiNotifier(c).show(state(42))
        assertNull(LiveUpdate.promotedState(c, AqiNotifier.NOTIF_ID))
    }

    /** 低版本没有 canPostPromotedNotifications，必须安全地返回 false 而不是崩溃。 */
    @Test
    fun canPostIsFalseOnUnsupportedSdk() {
        assertFalse(LiveUpdate.canPost(ctx()))
    }

    /** 通知存在但未被提升 → false（区别于「没有通知」的 null）。 */
    @Test
    fun promotedStateIsFalseWhenNotPromoted() {
        showWith(live = true)
        assertEquals(false, LiveUpdate.promotedState(ctx(), AqiNotifier.NOTIF_ID))
    }
}
