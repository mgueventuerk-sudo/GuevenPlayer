package com.mg.player.data.sport.guide

import com.mg.player.core.util.NameNormalizer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/** Sendername von Bzzoiro = Sender des Anbieters, Qualitätsangaben und Länder-Vorsatz zählen nicht. */
class StationKeyTest {

    @Test
    fun qualityAndCountryPrefixAreIgnored() {
        val bzzoiro = GuideCatalog.nameKey("beIN SPORTS 1")
        listOf("|TR| BEIN SPORTS 1 FHD", "TR: beIN Sports 1 HD", "TR | BEIN SPORTS 1 UHD", "|TR| BEIN SPORTS 1 4K").forEach {
            assertEquals(it, bzzoiro, GuideCatalog.nameKey(it))
        }
        assertEquals(NameNormalizer.normalize("beIN SPORTS 1"), NameNormalizer.normalize("|TR| BEIN SPORTS 1 FHD"))
    }

    @Test
    fun theNumberStillCounts() {
        assertNotEquals(GuideCatalog.nameKey("beIN SPORTS 1"), GuideCatalog.nameKey("|TR| BEIN SPORTS 2 FHD"))
    }
}
