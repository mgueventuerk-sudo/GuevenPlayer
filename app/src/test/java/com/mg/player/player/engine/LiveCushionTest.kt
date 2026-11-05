package com.mg.player.player.engine

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Live-Puffer (ab 3.51): langsamer, bis der Vorrat steht – ohne ständiges Hin und Her. */
class LiveCushionTest {

    @Test
    fun slowsDownUntilTheTargetIsReached() {
        assertTrue(LiveCushionController.decide(aheadMs = 1_500, targetMs = 4_000, slowNow = false))
        assertTrue(LiveCushionController.decide(aheadMs = 3_900, targetMs = 4_000, slowNow = true))
        assertFalse(LiveCushionController.decide(aheadMs = 4_000, targetMs = 4_000, slowNow = true))
    }

    @Test
    fun smallDipAfterReachingTheTargetDoesNotFlipBack() {
        assertFalse(LiveCushionController.decide(aheadMs = 3_500, targetMs = 4_000, slowNow = false))
        assertTrue(LiveCushionController.decide(aheadMs = 2_900, targetMs = 4_000, slowNow = false))
    }

    @Test
    fun offMeansNormalSpeed() {
        assertFalse(LiveCushionController.decide(aheadMs = 0, targetMs = 0, slowNow = true))
    }
}
