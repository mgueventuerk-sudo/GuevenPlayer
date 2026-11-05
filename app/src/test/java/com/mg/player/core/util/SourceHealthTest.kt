package com.mg.player.core.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SourceHealthTest {

    private class MapStore : StreamMemory.Store {
        val data = HashMap<String, String>()
        override fun all(): Map<String, String> = HashMap(data)

        override fun put(key: String, value: String) {
            data[key] = value
        }

        override fun remove(keys: Collection<String>) {
            keys.forEach(data::remove)
        }
    }

    private var now = 1_000_000L
    private val store = MapStore()
    private fun health() = SourceHealth(store) { now }

    @Test
    fun oneShortStallAlreadyRanksBelowCleanSources() {
        val h = health()
        h.record("1:L:a", SourceHealth.SHORT_STALL)
        assertEquals(1, h.rank("1:L:a"))
        assertEquals(0, h.rank("1:L:b"))
        h.record("1:L:c", SourceHealth.FAILED)
        assertTrue(h.rank("1:L:c") > h.rank("1:L:a"))
    }

    @Test
    fun remembersAcrossRestartAndDecays() {
        health().record("1:L:a", SourceHealth.LONG_STALL)
        // Neustart: aus dem Speicher geladen.
        assertEquals(2, health().rank("1:L:a"))
        // Nach drei Halbwertszeiten (36 h) nur noch 0,25 Punkte → wieder unauffällig.
        now += 3 * SourceHealth.HALF_LIFE_MS
        assertEquals(0, health().rank("1:L:a"))
    }

    @Test
    fun stablePlaybackHalvesThePenalty() {
        val h = health()
        h.record("1:L:a", SourceHealth.FAILED)
        h.recordGood("1:L:a")
        assertEquals(1.5, h.penalty("1:L:a"), 0.001)
    }

    @Test
    fun deletedAccountIsForgotten() {
        val h = health()
        h.record("1:L:a", SourceHealth.FAILED)
        h.record("2:L:a", SourceHealth.FAILED)
        h.forget(1)
        assertEquals(0, h.rank("1:L:a"))
        assertEquals(listOf("2:L:a"), store.data.keys.toList())
    }
}
