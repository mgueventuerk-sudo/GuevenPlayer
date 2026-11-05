package com.mg.player.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StationReportTest {

    @Test
    fun countsAndMissingNamesAsDelivered() {
        val text = StationReport(9, 2, listOf("beIN SPORTS 1 Türkiye", "beIN SPORTS")).text()
        assertEquals("Bzzoiro-Senderliste: 9 Spiele mit Sender, 2 zugeordnet\nNicht gefunden: ‚beIN SPORTS 1 Türkiye‘, ‚beIN SPORTS‘", text)
        // Nie der interne Vergleichsschlüssel.
        assertFalse(text.contains("beinsports"))
    }

    @Test
    fun noStationsYet() {
        assertTrue(StationReport(0, 0, emptyList()).text().contains("noch kein Spiel mit Sender"))
        assertFalse(StationReport(1, 1, emptyList()).text().contains("Nicht gefunden"))
        assertTrue(StationReport(1, 1, emptyList()).text().contains("1 Spiel mit Sender"))
    }

    @Test
    fun longListsAreCut() {
        val names = (1..12).map { "Sender $it" }
        val text = StationReport(12, 0, names).text()
        assertTrue(text.endsWith("und 4 weitere"))
        assertFalse(text.contains("Sender 9‘"))
    }

    @Test
    fun duplicatesIgnoreCase() {
        assertEquals(listOf("beIN SPORTS 1", "ZDF"), StationReport.distinctNames(listOf(" beIN SPORTS 1", "BEIN SPORTS 1", "ZDF", "")))
    }
}
