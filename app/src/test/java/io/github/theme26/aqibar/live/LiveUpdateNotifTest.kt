package io.github.theme26.aqibar.live

import android.app.Application
import android.app.NotificationManager
import io.github.theme26.aqibar.core.AqiLevel
import io.github.theme26.aqibar.core.AqiState
import io.github.theme26.aqibar.core.Freshness
import io.github.theme26.aqibar.core.NotifPolicy
import io.github.theme26.aqibar.data.AqiPrefs
import io.github.theme26.aqibar.render.AqiNotifier
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

    @Test
    fun requestsEvenOnOlderSdk() {
        assertEquals("测试跑在 SDK 30，不应支持 Live Updates", false, LiveUpdate.supported)
        showWith(live = true)
        val n = postedNotification()
        assertTrue(n!!.extras.getBoolean(LiveUpdate.EXTRA_REQUEST_PROMOTED, false))
    }

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

    @Test
    fun canPostIsFalseOnUnsupportedSdk() {
        assertFalse(LiveUpdate.canPost(ctx()))
    }

    @Test
    fun promotedStateIsFalseWhenNotPromoted() {
        showWith(live = true)
        assertEquals(false, LiveUpdate.promotedState(ctx(), AqiNotifier.NOTIF_ID))
    }
}
