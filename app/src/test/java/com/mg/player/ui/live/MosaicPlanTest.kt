package com.mg.player.ui.live

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MosaicPlanTest {

    private val ids = (1..12).map { "ch$it" }

    @Test
    fun startsAtFocusedChannel() {
        assertEquals(listOf("ch5", "ch6", "ch7", "ch8"), MosaicPlan.pick(ids, "ch5", 4))
    }

    @Test
    fun wrapsAroundAtEndOfGroup() {
        assertEquals(listOf("ch11", "ch12", "ch1", "ch2"), MosaicPlan.pick(ids, "ch11", 4))
    }

    @Test
    fun smallGroupHasNoDuplicates() {
        val picked = MosaicPlan.pick(listOf("a", "b", "c"), "b", 9)
        assertEquals(listOf("b", "c", "a"), picked)
        assertEquals(picked.size, picked.toSet().size)
    }

    @Test
    fun unknownStartBeginsAtTop() {
        assertEquals(listOf("ch1", "ch2", "ch3", "ch4"), MosaicPlan.pick(ids, "gibt-es-nicht", 4))
        assertEquals(listOf("ch1", "ch2", "ch3", "ch4"), MosaicPlan.pick(ids, null, 4))
    }

    @Test
    fun emptyInputGivesEmptyMosaic() {
        assertTrue(MosaicPlan.pick(emptyList(), "x", 9).isEmpty())
        assertTrue(MosaicPlan.pick(ids, "ch1", 0).isEmpty())
    }

    @Test
    fun roundNeverShorterThanThirtySeconds() {
        assertEquals(30_000L, MosaicPlan.restAfterRound(roundStartedMs = 1_000, nowMs = 1_000))
        assertEquals(5_000L, MosaicPlan.restAfterRound(roundStartedMs = 0, nowMs = 25_000))
        // Langsame Runde (viele Sender ohne Bild): sofort weiter, nie negativ.
        assertEquals(0L, MosaicPlan.restAfterRound(roundStartedMs = 0, nowMs = 60_000))
    }

    @Test
    fun weakDevicesStartWithFourTiles() {
        assertEquals(4, MosaicPlan.defaultSize(lowTier = true))
        assertEquals(9, MosaicPlan.defaultSize(lowTier = false))
        assertEquals(9, MosaicPlan.toggled(4))
        assertEquals(4, MosaicPlan.toggled(9))
    }
}
