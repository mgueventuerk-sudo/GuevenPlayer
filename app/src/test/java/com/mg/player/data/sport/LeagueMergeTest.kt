package com.mg.player.data.sport

import com.mg.player.domain.model.SportLeague
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** „Süper Lig“ (ESPN) und „Trendyol Super Lig“ (Bzzoiro) sind eine Liga (ab 3.47). */
class LeagueMergeTest {

    private val trendyol = SportLeague("bzz:52", "Trendyol Super Lig (Turkey)", "Bzzoiro")
    private val catalog = listOf(trendyol, SportLeague("bzz:1", "Bundesliga (Germany)", "Bzzoiro"))

    @Test
    fun duplicateLeagueIsMergedAndManualChannelMoves() {
        val (leagues, manual) = BzzoiroAdoption.mergeDuplicates(
            catalog,
            followed = setOf("espn:soccer/tur.1", "bzz:52", "bzz:1"),
            manual = mapOf("espn:soccer/tur.1" to "ch-bein1"),
        )!!
        assertEquals(setOf("bzz:52", "bzz:1"), leagues)
        assertEquals(mapOf("bzz:52" to "ch-bein1"), manual)
    }

    @Test
    fun existingManualChannelOfTheMergedLeagueWins() {
        val (_, manual) = BzzoiroAdoption.mergeDuplicates(
            catalog,
            followed = setOf("espn:soccer/tur.1", "bzz:52"),
            manual = mapOf("espn:soccer/tur.1" to "old", "bzz:52" to "chosen"),
        )!!
        assertEquals(mapOf("bzz:52" to "chosen"), manual)
    }

    @Test
    fun nothingToMerge() {
        assertNull(BzzoiroAdoption.mergeDuplicates(catalog, setOf("espn:soccer/tur.1"), emptyMap()))
        assertNull(BzzoiroAdoption.mergeDuplicates(catalog, setOf("bzz:52"), emptyMap()))
    }

    @Test
    fun adoptingBzzoiroReplacesTheEspnSuperLig() {
        val result = BzzoiroAdoption.adopt(catalog, setOf("espn:soccer/tur.1"))
        assertEquals(setOf("bzz:52"), result.chosen)
        assertEquals(mapOf("espn:soccer/tur.1" to "bzz:52"), result.moved)
    }
}
