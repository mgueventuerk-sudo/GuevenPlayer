package com.mg.player.data.sport.guide

import com.mg.player.data.db.dao.ChannelIndexRow
import com.mg.player.data.sport.StationNames
import com.mg.player.domain.model.stationLabel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** „beIN SPORTS 1“ gibt es in vielen Ländern: Bzzoiro-Sender mit Land treffen nur den Sender dieses Landes (ab 3.49). */
class StationCountryTest {

    private val index = SportChannelIndex(
        listOf(
            ChannelIndexRow("1:L:1", "|AR| BEIN SPORTS 1 FHD", 1, ""),
            ChannelIndexRow("1:L:2", "|FR| BEIN SPORTS 1 HD", 1, ""),
            ChannelIndexRow("1:L:3", "|TR| BEIN SPORTS 1 HD", 1, ""),
            ChannelIndexRow("2:L:4", "|AR| BEIN SPORTS 1", 2, ""),
            ChannelIndexRow("3:L:5", "BEIN SPORTS 1", 3, ""),
        ),
    )

    @Test
    fun turkishStationOnlyHitsTheTurkishChannel() {
        // Zugang 1: der türkische, nicht der arabische oder französische. Zugang 2 hat nur den arabischen → keiner.
        // Zugang 3: ohne Länderangabe zählt er.
        assertEquals(listOf("1:L:3", "3:L:5"), index.forStation("TR| beIN SPORTS 1").map { it.id }.sorted())
    }

    @Test
    fun countryOfTheFallbackPath() {
        assertTrue(StationNames.sameCountry("TR| beIN SPORTS 1", "|TR| BEIN SPORTS 1 FHD"))
        assertTrue(StationNames.sameCountry("TR| beIN SPORTS 1", "BEIN SPORTS 1"))
        assertFalse(StationNames.sameCountry("TR| beIN SPORTS 1", "|AR| BEIN SPORTS 1"))
        assertFalse(StationNames.sameCountry("TR| beIN SPORTS 1", "FR: beIN Sports 1 HD"))
        assertTrue(StationNames.sameCountry("beIN SPORTS 1", "|AR| BEIN SPORTS 1"))
    }

    @Test
    fun countryIsShownReadable() {
        assertEquals("beIN SPORTS 1 (TR)", stationLabel("TR| beIN SPORTS 1"))
        assertEquals("ZDF", stationLabel("ZDF"))
    }
}
