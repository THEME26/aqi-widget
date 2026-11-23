package com.example.aqi.core

import org.junit.Assert.assertEquals
import org.junit.Test

class FreshnessRuleTest {

    private val minute = 60_000L

    @Test
    fun justFetchedIsFresh() {
        assertEquals(Freshness.FRESH, FreshnessRule.of(0L, 0L, 60))
    }

    @Test
    fun insideOneIntervalIsFresh() {
        assertEquals(Freshness.FRESH, FreshnessRule.of(0L, 59 * minute, 60))
    }

    @Test
    fun insideTheSecondIntervalIsStillFresh() {
        assertEquals(Freshness.FRESH, FreshnessRule.of(0L, 119 * minute, 60))
    }

    @Test
    fun exactlyTwoIntervalsIsStillFresh() {
        assertEquals(Freshness.FRESH, FreshnessRule.of(0L, 120 * minute, 60))
    }

    @Test
    fun beyondTwoIntervalsIsStale() {
        assertEquals(Freshness.STALE, FreshnessRule.of(0L, 121 * minute, 60))
    }

    @Test
    fun windowFollowsTheConfiguredInterval() {
        assertEquals(Freshness.FRESH, FreshnessRule.of(0L, 29 * minute, 15))
        assertEquals(Freshness.STALE, FreshnessRule.of(0L, 31 * minute, 15))
    }

    @Test
    fun missingTimestampCountsAsStale() {
        val now = 1_700_000_000_000L
        assertEquals(Freshness.STALE, FreshnessRule.of(0L, now, 60))
    }
}
