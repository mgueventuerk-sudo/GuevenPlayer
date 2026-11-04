package com.mg.player.ui.detail

import com.mg.player.domain.model.Episode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Staffel-Auswahl der Seriendetails – unabhängig davon, ob Folgen oder Fortschritt zuerst ankommen. */
class SeasonPickTest {

    private fun ep(season: Int, number: Int) = Episode("e$season-$number", "s", season, number, "Folge", "http://x.invalid", null, null, null)

    private val episodes = listOf(ep(1, 1), ep(1, 2), ep(2, 1), ep(3, 1))

    @Test
    fun followsContinueTargetWhenProgressArrivesLater() {
        // Folgen da, Fortschritt noch nicht: vorerst Staffel 1 …
        assertEquals(1, pickSeason(null, ContinueTarget(episodes.first(), 0, ""), episodes))
        // … dann kommt der Fortschritt: Staffel 3 statt hängen gebliebener Staffel 1.
        assertEquals(3, pickSeason(null, ContinueTarget(ep(3, 1), 12_000, ""), episodes))
    }

    @Test
    fun ownChoiceWinsWhileTheSeasonExists() {
        assertEquals(2, pickSeason(2, ContinueTarget(ep(3, 1), 0, ""), episodes))
        // Staffel gibt es (nach Aktualisierung) nicht mehr: zurück zur automatischen Wahl.
        assertEquals(3, pickSeason(7, ContinueTarget(ep(3, 1), 0, ""), episodes))
    }

    @Test
    fun noEpisodesNoSeason() {
        assertNull(pickSeason(null, null, emptyList()))
        assertEquals(1, pickSeason(null, null, episodes))
    }
}
