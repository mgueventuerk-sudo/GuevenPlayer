package com.mg.player.data.tmdb

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TmdbTitleMatcherTest {

    @Test
    fun removesProviderCountryAndQualityTagsBeforeExactComparison() {
        assertEquals(
            TmdbTitleMatcher.Match.EXACT,
            TmdbTitleMatcher.compare(listOf("Nordlicht"), "DE | Nordlicht (2023) [4K]"),
        )
    }

    @Test
    fun matchesOneCharacterProviderTypoAsApproximate() {
        assertEquals(
            TmdbTitleMatcher.Match.APPROXIMATE,
            TmdbTitleMatcher.compare(listOf("Nordlichtt"), "DE - Nordlicht (2023) FHD"),
        )
    }

    @Test
    fun doesNotConfuseAFranchiseTitleWithItsSubtitle() {
        assertNull(TmdbTitleMatcher.compare(listOf("Stargate"), "Stargate Atlantis"))
    }

    @Test
    fun toleratesAccentAndUmlautSpellingDifferences() {
        assertEquals(TmdbTitleMatcher.Match.EXACT, TmdbTitleMatcher.compare(listOf("München"), "Munchen"))
        assertEquals(TmdbTitleMatcher.Match.EXACT, TmdbTitleMatcher.compare(listOf("München"), "Muenchen"))
    }
}
