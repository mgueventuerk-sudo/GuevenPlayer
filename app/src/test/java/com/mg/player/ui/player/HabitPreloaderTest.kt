package com.mg.player.ui.player

import com.mg.player.data.combined.Combined
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Sehgewohnheits-Vorladung darf nie auf demselben Zugang landen wie der laufende Sender - die meisten Zugänge
 * erlauben nur eine Verbindung (siehe LiveSession.prepareNeighbours, die aus demselben Grund nie einen echten
 * zweiten Stream öffnet).
 */
class HabitPreloaderTest {

    private val playlists = mapOf("A1" to 1L, "A2" to 1L, "B1" to 2L, "C1" to 3L)
    private val freeSlots = mutableSetOf<Long>()

    private suspend fun pick(habits: List<String>, currentId: String, currentPlaylistId: Long) = HabitPreloader.pickCandidate(
        habitChannelIds = habits,
        currentChannelId = currentId,
        currentPlaylistId = currentPlaylistId,
        playlistIdOf = { playlists[it] },
        hasFreeSlot = { it in freeSlots },
    )

    @Test
    fun picksTheFirstHabitChannelOnAFreeDifferentAccount() = runBlocking {
        freeSlots += 2L
        assertEquals("B1", pick(listOf("A2", "B1", "C1"), currentId = "A1", currentPlaylistId = 1L))
    }

    @Test
    fun skipsTheCurrentChannelEvenIfItLeadsTheHabitList() = runBlocking {
        freeSlots += 2L
        assertEquals("B1", pick(listOf("A1", "B1"), currentId = "A1", currentPlaylistId = 1L))
    }

    @Test
    fun neverPicksAChannelOnTheSameAccountAsTheCurrentOne() = runBlocking {
        freeSlots += 1L // Zugang 1 (der laufende) hätte sogar einen "freien Platz" - zählt trotzdem nicht.
        assertNull(pick(listOf("A2"), currentId = "A1", currentPlaylistId = 1L))
    }

    @Test
    fun skipsAccountsWithoutAConfirmedFreeSlot() = runBlocking {
        // B1 liegt auf Zugang 2, aber der hat laut Prüfung keinen freien Platz - C1 (Zugang 3) hat einen.
        freeSlots += 3L
        assertEquals("C1", pick(listOf("B1", "C1"), currentId = "A1", currentPlaylistId = 1L))
    }

    @Test
    fun returnsNullWhenNoCandidateQualifies() = runBlocking {
        assertNull(pick(listOf("A2", "B1"), currentId = "A1", currentPlaylistId = 1L))
    }

    @Test
    fun neverPredictsAcrossTheCombinedView() = runBlocking {
        freeSlots += 2L
        // "Alle Zugänge" ist keine echte Einzelverbindung - weder als laufender noch als Ziel-Zugang.
        assertNull(pick(listOf("B1"), currentId = "X", currentPlaylistId = Combined.ID))
    }
}
