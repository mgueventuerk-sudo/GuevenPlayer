package com.mg.player.domain.usecase

import com.mg.player.domain.model.Episode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ResolveNextEpisodeTest {

    private fun ep(season: Int, episode: Int) = Episode("s${season}e$episode", "serie", season, episode, "Folge $episode", "url", null, null, null)

    private val all = listOf(ep(2, 1), ep(1, 2), ep(1, 1), ep(2, 2))

    @Test
    fun `nächste Folge in derselben Staffel`() {
        assertEquals("s1e2", ResolveNextEpisodeUseCase.next(all, ep(1, 1))?.id)
    }

    @Test
    fun `wechselt am Staffelende zu Staffel plus eins`() {
        assertEquals("s2e1", ResolveNextEpisodeUseCase.next(all, ep(1, 2))?.id)
    }

    @Test
    fun `nach der letzten Folge gibt es keine weitere`() {
        assertNull(ResolveNextEpisodeUseCase.next(all, ep(2, 2)))
    }
}
