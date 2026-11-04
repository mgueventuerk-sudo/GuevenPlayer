package com.mg.player.data.importer

import com.mg.player.data.importer.EpgSchedule.HOUR
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Fall aus der Prüfung: Jede EPG-Aktualisierung lud die zig MB großen öffentlichen Quellen erneut. */
class FallbackEpgGateTest {

    private class MapStore : FallbackEpgGate.Store {
        val map = HashMap<String, Long>()
        override fun get(key: String): Long? = map[key]
        override fun put(key: String, value: Long) {
            map[key] = value
        }
    }

    private val store = MapStore()
    private val gate = FallbackEpgGate(store)
    private val sig = FallbackEpgGate.signature(listOf("a", "b"))
    private val now = 10_000 * HOUR

    @Test
    fun firstTryIsAllowed() {
        assertTrue(gate.allowed(1, sig, now))
    }

    @Test
    fun sameChannelsWaitOneDayAfterMatches() {
        gate.record(1, sig, now, matched = 5)
        assertFalse(gate.allowed(1, sig, now + 23 * HOUR))
        assertTrue(gate.allowed(1, sig, now + 25 * HOUR))
    }

    @Test
    fun noMatchesPausesOneWeek() {
        gate.record(1, sig, now, matched = 0)
        assertFalse(gate.allowed(1, sig, now + 6 * 24 * HOUR))
        assertTrue(gate.allowed(1, sig, now + 8 * 24 * HOUR))
    }

    @Test
    fun unreachableSourceOnlyPausesOneDay() {
        gate.record(1, sig, now, matched = null)
        assertFalse(gate.allowed(1, sig, now + 12 * HOUR))
        assertTrue(gate.allowed(1, sig, now + 25 * HOUR))
    }

    @Test
    fun otherMissingChannelsAreCheckedRightAway() {
        gate.record(1, sig, now, matched = 0)
        assertTrue(gate.allowed(1, FallbackEpgGate.signature(listOf("a", "b", "c")), now + HOUR))
    }

    @Test
    fun accountsAreIndependent() {
        gate.record(1, sig, now, matched = 0)
        assertTrue(gate.allowed(2, sig, now + HOUR))
    }

    @Test
    fun signatureIgnoresOrder() {
        assertEquals(FallbackEpgGate.signature(listOf("x", "y", "z")), FallbackEpgGate.signature(listOf("z", "x", "y")))
    }
}
