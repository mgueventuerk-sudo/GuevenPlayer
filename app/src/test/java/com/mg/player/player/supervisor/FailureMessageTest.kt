package com.mg.player.player.supervisor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Die Endmeldung des Wächters darf nicht „Keine Verbindung“ sagen, wenn die Verbindung gar nicht das Problem ist. */
class FailureMessageTest {

    private val networkSymptoms = setOf(Symptom.NETWORK, Symptom.START_NO_DATA, Symptom.STALL)

    @Test
    fun onlyRealConnectionProblemsPromiseAnAutomaticRestart() {
        Symptom.entries.forEach { symptom ->
            val text = Ladders.failureMessage(symptom, httpCode = null)
            assertEquals("$symptom", symptom in networkSymptoms, text.contains("Keine Verbindung"))
            assertEquals("$symptom", symptom in networkSymptoms, text.contains("startet automatisch"))
        }
    }

    @Test
    fun aPictureProblemIsNotReportedAsMissingConnection() {
        val text = Ladders.failureMessage(Symptom.START_NO_PICTURE, httpCode = 200)
        assertTrue(text.contains("kein Bild"))
        assertFalse(text.contains("Keine Verbindung"))
    }

    @Test
    fun theHttpCodeIsShownWhereItBelongsAndNeverInvented() {
        assertTrue(Ladders.failureMessage(Symptom.AUTH, 403).contains("(HTTP 403)"))
        assertFalse(Ladders.failureMessage(Symptom.AUTH, null).contains("HTTP"))
        assertTrue(Ladders.failureMessage(Symptom.AV_DESYNC, 500).contains("(HTTP 500)"))
    }

    @Test
    fun everySymptomHasANonEmptyMessage() {
        Symptom.entries.forEach { assertTrue("$it", Ladders.failureMessage(it, null).isNotBlank()) }
    }
}
