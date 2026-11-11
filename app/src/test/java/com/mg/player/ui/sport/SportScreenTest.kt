package com.mg.player.ui.sport

import org.junit.Assert.assertTrue
import org.junit.Test

class SportScreenTest {

    @Test
    fun kickoffFormatsWeekdayDateAndTime() {
        // Unabhaengig von der Zeitzone des Testrechners ueberprueft via Pattern statt fester Uhrzeit.
        val label = kickoff(1_704_111_240_000L)
        assertTrue(label, Regex("""^[A-Za-zÄÖÜäöü]{2}\.? \d{2}\.\d{2}\. \d{2}:\d{2}$""").matches(label))
    }
}
