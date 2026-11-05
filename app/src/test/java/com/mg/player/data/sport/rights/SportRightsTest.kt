package com.mg.player.data.sport.rights

import com.mg.player.domain.model.EventStatus
import com.mg.player.domain.model.SportEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Rechte-Tabelle (Plan „Spiel-Finder“ B): Familien, Wettbewerbe, Länder – und keine Aussage, wo die Tabelle nichts weiß. */
class SportRightsTest {

    private fun event(id: String, league: String) = SportEvent(id, "soccer", league, "A – B", "A", "B", 0, null, EventStatus.PRE, emptyList())

    private val europaLeague = event("espn:soccer/uefa.europa#1", "Europa League")
    private val superLigBzzoiro = event("bsd:52#9", "Trendyol Süper Lig")

    @Test
    fun familiesAreRecognisedInProviderNames() {
        assertEquals("prime", SportRights.familyOf("DE| PRIME DE 016 [Alkmaar vs. Juventus]"))
        assertEquals("sky", SportRights.familyOf("DE - SKY SPORT BUNDESLIGA 3 4K"))
        assertEquals("bein", SportRights.familyOf("|TR| BEIN SPORTS 1 RAW"))
        assertEquals("ssport", SportRights.familyOf("TR: S Sport Plus 1"))
        assertEquals("rtl", SportRights.familyOf("DE: Nitro HD"))
        assertNull(SportRights.familyOf("DE: Kabel Eins"))
    }

    @Test
    fun competitionByEspnKeyOrName() {
        assertEquals("europa-league", SportRights.competitionOf(europaLeague)?.id)
        assertEquals("super-lig", SportRights.competitionOf(superLigBzzoiro)?.id)
    }

    /** 05.11.: „PRIME“ für die Europa League widerspricht den Rechten (RTL, Sky). */
    @Test
    fun primeForEuropaLeagueInGermanyIsAConflict() {
        assertEquals(SportRights.Verdict.CONFLICT, SportRights.verdict(europaLeague, "DE| PRIME DE 016", "de"))
        assertEquals(SportRights.Verdict.ALLOWED, SportRights.verdict(europaLeague, "DE: RTL HD", "de"))
        assertEquals(SportRights.Verdict.ALLOWED, SportRights.verdict(europaLeague, "DE: Sky Sport 1", "de"))
    }

    @Test
    fun noStatementWithoutTableEntry() {
        // Land ohne Eintrag, Sender ohne Familie, Kanal ohne Länderkürzel.
        assertEquals(SportRights.Verdict.UNKNOWN, SportRights.verdict(europaLeague, "NL: Ziggo Sport", "nl"))
        assertEquals(SportRights.Verdict.UNKNOWN, SportRights.verdict(europaLeague, "DE: Kabel Eins", "de"))
        assertEquals(SportRights.Verdict.UNKNOWN, SportRights.verdict(europaLeague, "PRIME 016", null))
    }

    @Test
    fun familiesForSearchOrder() {
        assertTrue(SportRights.familiesFor(superLigBzzoiro, setOf("TR", "DE")).contains("bein"))
        assertTrue(SportRights.familiesFor(europaLeague, setOf("DE")).containsAll(setOf("rtl", "sky")))
    }
}
