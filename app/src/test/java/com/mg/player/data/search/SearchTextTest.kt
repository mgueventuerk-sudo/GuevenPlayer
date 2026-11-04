package com.mg.player.data.search

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchTextTest {

    @Test
    fun everyWordBecomesPrefixQuery() {
        assertEquals("zdf* hd*", SearchText.ftsQuery("ZDF HD"))
    }

    @Test
    fun specialCharactersCannotBreakQuery() {
        assertEquals("tatort* munster*", SearchText.ftsQuery("\"Tatort\" - Münster*"))
    }

    @Test
    fun ftsOperatorsBecomePlainWords() {
        assertEquals("sky* or* sport*", SearchText.ftsQuery("Sky OR Sport"))
        assertEquals("near* not* and*", SearchText.ftsQuery("NEAR NOT AND"))
    }

    @Test
    fun onlySymbolsGiveNoQuery() {
        assertNull(SearchText.ftsQuery(" -*\" "))
    }

    @Test
    fun fancyProviderFontsBecomePlainLetters() {
        assertEquals(listOf("zdf"), SearchText.words("𝗭𝗗𝗙"))
        assertEquals(listOf("zdf"), SearchText.words("ＺＤＦ"))
        assertEquals(listOf("zdf", "uhd"), SearchText.words("ZDF ᵁᴴᴰ"))
    }

    @Test
    fun accentsAndSpecialLettersAreFolded() {
        assertEquals(listOf("cafe", "strasse", "istanbul"), SearchText.words("Café Straße İstanbul"))
        assertEquals(listOf("ilk", "sevgi"), SearchText.words("ılk Sevgi"))
    }

    @Test
    fun indexContainsGermanUmlautSpellingAndCompactForm() {
        val terms = SearchText.indexTerms("DE: Die Känguru-Chroniken").split(" ")
        assertTrue(terms.toString(), "kanguru" in terms)
        assertTrue(terms.toString(), "kaenguru" in terms)
        assertTrue(terms.toString(), "dediekanguruchroniken" in terms)
    }

    @Test
    fun compactFormJoinsVoiceInputAndPunctuation() {
        assertEquals("zdf", SearchText.compact("Z D F"))
        assertEquals("ntv", SearchText.compact("n-tv"))
        assertEquals("sat1", SearchText.compact("SAT.1"))
        assertTrue(SearchText.indexTerms("DE: n-tv HD").contains("ntv"))
        assertTrue(SearchText.indexTerms("DE ▎SAT.1 HD").contains("sat1"))
    }
}
