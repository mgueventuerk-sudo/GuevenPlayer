package com.mg.player.core.vpn

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Gleicher Schlüssel auf zwei Geräten: Muster aus dem Protokoll vom 04.11.2026 (Abriss alle ~2 min). */
class ShortSessionsTest {
    private var now = 0L
    private val sessions = ShortSessions { now }

    @Test
    fun fourShortSessionsInHalfAnHourMeanConflict() {
        repeat(3) {
            now += 110_000
            assertFalse(sessions.onSessionEnded(110_000))
        }
        now += 110_000
        assertTrue(sessions.onSessionEnded(110_000))
    }

    @Test
    fun longSessionsOrSpreadOutDropsAreNormal() {
        repeat(10) {
            now += 3_600_000
            assertFalse(sessions.onSessionEnded(3_600_000))
        }
        repeat(6) {
            now += 11 * 60_000L
            assertFalse(sessions.onSessionEnded(110_000))
        }
    }
}
