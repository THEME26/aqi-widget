package com.example.aqi.data

import android.content.Context
import com.example.aqi.core.AqiLevel
import com.example.aqi.core.AqiSource
import com.example.aqi.core.AqiStandard
import com.example.aqi.core.AqiState
import com.example.aqi.core.Freshness
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class CacheFreshnessTest {

    private val ctx: Context = RuntimeEnvironment.getApplication()
    private val minute = 60_000L

    private fun state(fetchedAt: Long) = AqiState(
        value = 88,
        level = AqiLevel.of(88),
        dominantPollutant = "pm2_5",
        subIndices = mapOf("pm2_5" to 88),
        stationName = "grid",
        distanceKm = 0.0,
        observedText = "",
        fetchedAtMillis = fetchedAt,
        freshness = Freshness.FRESH,
        attribution = "",
        standard = AqiStandard.US,
        sourceTag = AqiSource.OM.tag,
    )

    @Test
    fun readingInsideTheWindowSurvivesAFailedRefresh() {
        val prefs = AqiPrefs(ctx)
        prefs.primarySource = AqiSource.OM
        AqiCache(ctx).clear()
        AqiCache(ctx).save(state(System.currentTimeMillis() - 90 * minute))

        val shown = AqiCache(ctx).loadForDisplay(60, AqiStandard.US, true)
        assertEquals(Freshness.FRESH, shown.freshness)
    }

    @Test
    fun readingPastTheWindowIsStale() {
        val prefs = AqiPrefs(ctx)
        prefs.primarySource = AqiSource.OM
        AqiCache(ctx).clear()
        AqiCache(ctx).save(state(System.currentTimeMillis() - 130 * minute))

        val shown = AqiCache(ctx).loadForDisplay(60, AqiStandard.US, true)
        assertEquals(Freshness.STALE, shown.freshness)
    }

    @Test
    fun failedFetchKeepsColourWhileTheReadingIsStillValid() {
        val prefs = AqiPrefs(ctx)
        prefs.primarySource = AqiSource.OM
        prefs.intervalMinutes = 60
        AqiCache(ctx).clear()
        AqiCache(ctx).save(state(System.currentTimeMillis() - 90 * minute))

        val afterFailure = fallback(Freshness.STALE)
        assertEquals(88, afterFailure.value)
        assertEquals(Freshness.FRESH, afterFailure.freshness)
    }

    @Test
    fun failedFetchGreysOnceTheReadingIsOld() {
        val prefs = AqiPrefs(ctx)
        prefs.primarySource = AqiSource.OM
        prefs.intervalMinutes = 60
        AqiCache(ctx).clear()
        AqiCache(ctx).save(state(System.currentTimeMillis() - 130 * minute))

        assertEquals(Freshness.STALE, fallback(Freshness.STALE).freshness)
    }

    private fun fallback(reason: Freshness): AqiState {
        val m = AqiRepository::class.java
            .getDeclaredMethod("fallback", Freshness::class.java, AqiStandard::class.java)
        m.isAccessible = true
        return m.invoke(AqiRepository(ctx), reason, AqiStandard.US) as AqiState
    }
}
