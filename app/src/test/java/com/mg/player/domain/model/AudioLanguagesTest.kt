package com.mg.player.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Einstellung „Sprache“: Standardsprache, weitere in Reihenfolge, „Nur diese Sprachen“. */
class AudioLanguagesTest {

    @Test
    fun trackCodesCoverAllSpellingsInOrderOfPreference() {
        assertEquals(listOf("de", "deu", "ger", "tr", "tur"), AudioLanguages.trackCodes(listOf("de", "tr")))
    }

    @Test
    fun emptyOrUnknownFallsBackToGerman() {
        assertEquals(listOf("de"), AudioLanguages.sanitize(emptyList()))
        assertEquals(listOf("de"), AudioLanguages.sanitize(listOf("xx", " ")))
        assertEquals(listOf("tr", "de"), AudioLanguages.sanitize(listOf("TR", "de", "tr")))
    }

    @Test
    fun sourceLanguageFromProviderCodes() {
        assertEquals("de", AudioLanguages.languageOfSource("AT"))
        assertEquals("en", AudioLanguages.languageOfSource("UK"))
        assertEquals(null, AudioLanguages.languageOfSource(null))
        assertEquals(null, AudioLanguages.languageOfSource("BALKAN"))
    }

    @Test
    fun rankFollowsTheListUnknownBeforeForeign() {
        val langs = listOf("de", "tr")
        assertEquals(0, AudioLanguages.rank("DE", langs))
        assertEquals(1, AudioLanguages.rank("TR", langs))
        assertEquals(2, AudioLanguages.rank(null, langs))
        assertEquals(3, AudioLanguages.rank("US", langs))
    }

    @Test
    fun onlyKnownOtherLanguagesAreForeign() {
        assertTrue(AudioLanguages.isForeign("US", listOf("de")))
        assertFalse(AudioLanguages.isForeign("CH", listOf("de")))
        assertFalse(AudioLanguages.isForeign(null, listOf("de")))
    }

    @Test
    fun newDefaultMovesToTheFrontAndKeepsTheRest() {
        assertEquals(listOf("tr", "de", "en"), AudioLanguages.withDefault(listOf("de", "tr", "en"), "tr"))
        assertEquals(listOf("en", "de"), AudioLanguages.withDefault(listOf("de"), "en"))
    }

    @Test
    fun toggleAddsAtTheEndAndNeverRemovesTheDefault() {
        assertEquals(listOf("de", "tr"), AudioLanguages.toggled(listOf("de"), "tr"))
        assertEquals(listOf("de"), AudioLanguages.toggled(listOf("de", "tr"), "tr"))
        assertEquals(listOf("de", "tr"), AudioLanguages.toggled(listOf("de", "tr"), "de"))
    }
}
