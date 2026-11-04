package com.mg.player.data.remote

import org.junit.Assert.assertEquals
import org.junit.Test

/** Toter Zugang: wachsende Pause statt Abfrage alle 30 s (Audit A-14). */
class ConnectionBackoffTest {
    @Test
    fun backoffGrowsAndIsCapped() {
        assertEquals(
            listOf(15_000L, 30_000L, 60_000L, 120_000L, 240_000L, 300_000L, 300_000L),
            (0..6).map(ConnectionMonitor::backoffMs),
        )
    }
}
