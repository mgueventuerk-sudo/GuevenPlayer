package com.mg.player.data.importer

import com.mg.player.data.importer.EpgSchedule.HOUR
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Fall aus der Prüfung: Vorhandenes EPG wurde nie von selbst erneuert und lief nach ein paar Tagen leer. */
class EpgScheduleTest {

    private val now = 1_000 * HOUR

    @Test
    fun missingEpgRetriesEveryTwelveHours() {
        assertFalse(EpgSchedule.shouldLoad(hasPrograms = false, lastAttemptMs = now - 2 * HOUR, lastSuccessMs = 0, nowMs = now))
        assertTrue(EpgSchedule.shouldLoad(hasPrograms = false, lastAttemptMs = now - 13 * HOUR, lastSuccessMs = 0, nowMs = now))
        assertTrue(EpgSchedule.shouldLoad(hasPrograms = false, lastAttemptMs = 0, lastSuccessMs = 0, nowMs = now))
    }

    @Test
    fun freshEpgIsKept() {
        assertFalse(EpgSchedule.shouldLoad(hasPrograms = true, lastAttemptMs = now - 3 * HOUR, lastSuccessMs = now - 3 * HOUR, nowMs = now))
    }

    @Test
    fun freshEpgFromOlderVersionIsReloadedOnce() {
        // Update von 3.1: EPG vor 2 h geladen (4 Tage, ohne Sport-Untertitel) → sofort neu laden.
        assertTrue(EpgSchedule.shouldLoad(true, now - 2 * HOUR, now - 2 * HOUR, now, attemptedFormat = 1))
        assertFalse(EpgSchedule.shouldLoad(true, now - 2 * HOUR, now - 2 * HOUR, now, attemptedFormat = EpgSchedule.FORMAT))
    }

    @Test
    fun epgOlderThanOneDayIsReloaded() {
        assertTrue(EpgSchedule.shouldLoad(hasPrograms = true, lastAttemptMs = now - 25 * HOUR, lastSuccessMs = now - 25 * HOUR, nowMs = now))
    }

    @Test
    fun failedSourceIsNotHammered() {
        // Erfolg vor zwei Tagen, letzter (gescheiterter) Versuch vor 2 Stunden: noch warten.
        assertFalse(EpgSchedule.shouldLoad(hasPrograms = true, lastAttemptMs = now - 2 * HOUR, lastSuccessMs = now - 48 * HOUR, nowMs = now))
        assertTrue(EpgSchedule.shouldLoad(hasPrograms = true, lastAttemptMs = now - 7 * HOUR, lastSuccessMs = now - 48 * HOUR, nowMs = now))
    }

    @Test
    fun olderVersionsWithoutSuccessStampUseLastAttempt() {
        assertFalse(EpgSchedule.shouldLoad(hasPrograms = true, lastAttemptMs = now - 5 * HOUR, lastSuccessMs = 0, nowMs = now))
        assertTrue(EpgSchedule.shouldLoad(hasPrograms = true, lastAttemptMs = now - 30 * HOUR, lastSuccessMs = 0, nowMs = now))
    }
}
