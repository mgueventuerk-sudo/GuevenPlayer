package com.mg.player.ui.player

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PreloadTimingTest {

    @Test
    fun neverCheckedCountsAsStaleWithoutOverflow() {
        assertTrue(PreloadTiming.isOlderThan(null, nowMs = 5L, limitMs = 20_000L))
    }

    @Test
    fun exactlyAtTheLimitIsNotYetStale() {
        assertFalse(PreloadTiming.isOlderThan(1_000L, nowMs = 21_000L, limitMs = 20_000L))
        assertTrue(PreloadTiming.isOlderThan(1_000L, nowMs = 21_001L, limitMs = 20_000L))
    }
}
