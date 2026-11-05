package com.mg.player.data.sport

import com.mg.player.core.util.NameNormalizer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/** Echte Sendernamen aus der Online-Sendeliste treffen den Kanal des Anbieters (ab 3.47). */
class StationNamesTest {

    @Test
    fun countryWordsDoNotMatter() {
        assertEquals(NameNormalizer.normalize("|TR| BEIN SPORTS 2 FHD"), StationNames.withoutCountry("beIN SPORTS 2 Türkiye"))
        assertEquals(NameNormalizer.normalize("DE: Sky Sport Bundesliga HD"), StationNames.withoutCountry("Sky Sport Bundesliga Deutschland"))
    }

    @Test
    fun channelNumberStillMatters() {
        assertNotEquals(NameNormalizer.normalize("|TR| BEIN SPORTS 1 FHD"), StationNames.withoutCountry("beIN SPORTS 2 Turkey"))
    }
}
