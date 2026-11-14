package com.mg.player.core.image

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Was vorgeladen im Speicher liegt, wird nur freigegeben, wenn es nie gezeigt wurde UND nicht mehr gebraucht wird. */
class PrefetchLedgerTest {
    private var now = 0L
    private val ledger = PrefetchLedger<String> { now }

    private fun keys(released: List<PrefetchLedger.Released<String>>) = released.map { it.key }

    private fun fill() {
        ledger.add("k1", "u1")
        ledger.add("k2", "u2")
        ledger.add("k3", "u3")
    }

    @Test
    fun entriesInsideTheWindowStay() {
        fill()
        assertTrue(ledger.outsideWindow(setOf("u1", "u2", "u3", "u9")).isEmpty())
        assertEquals(3, ledger.size)
    }

    @Test
    fun entriesOutsideTheWindowAreReleasedOnceAndLeaveTheLedger() {
        fill()
        assertEquals(listOf("k1", "k3"), keys(ledger.outsideWindow(setOf("u2"))))
        assertEquals(1, ledger.size)
        assertTrue("nicht zweimal freigeben", ledger.outsideWindow(setOf("u2")).isEmpty())
    }

    @Test
    fun anImageThatWasShownIsNeverReleasedByUs() {
        fill()
        assertTrue(ledger.markUsed("k2"))
        assertFalse("kein Eintrag von uns", ledger.markUsed("other"))
        // k2 wurde gezeigt: Es gehört dem normalen Cache, auch wenn es längst außerhalb des Fensters liegt.
        assertEquals(listOf("k1", "k3"), keys(ledger.outsideWindow(emptySet())))
        assertTrue(ledger.all().isEmpty())
    }

    @Test
    fun unusedEntriesExpireButFreshOnesDoNot() {
        ledger.add("old", "uo")
        now = 100_000
        ledger.add("fresh", "uf")
        now = 130_000
        assertEquals(listOf("old"), keys(ledger.expired(ttlMs = 120_000)))
        assertEquals(1, ledger.size)
        now = 400_000
        assertEquals(listOf("fresh"), keys(ledger.expired(ttlMs = 120_000)))
    }

    @Test
    fun tightMemoryReleasesEverythingThatWasNeverShown() {
        fill()
        ledger.markUsed("k1")
        assertEquals(listOf("k2", "k3"), keys(ledger.all()))
        assertEquals(0, ledger.size)
    }

    @Test
    fun releasedEntriesCarryTheirAddressSoTheyMayBePreloadedAgainLater() {
        fill()
        assertEquals(listOf("u1", "u3"), ledger.outsideWindow(setOf("u2")).map { it.url })
    }

    @Test
    fun addingTheSameKeyAgainDoesNotDuplicate() {
        ledger.add("k", "u")
        now = 50
        ledger.add("k", "u")
        assertEquals(1, ledger.size)
    }
}
