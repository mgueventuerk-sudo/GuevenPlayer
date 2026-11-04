package com.mg.player.domain.binge

import com.mg.player.domain.model.ChapterInfo
import com.mg.player.domain.model.IntroProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class IntroResolverTest {

    @Test
    fun `Kapitel hat Vorrang vor gespeichertem Profil`() {
        val chapters = listOf(ChapterInfo("Recap", 0, 45_000), ChapterInfo("Kapitel 1", 45_000, 600_000))
        val window = IntroResolver.resolve(chapters, IntroProfile("s", 10_000, 90_000))
        assertEquals(IntroWindow(0, 45_000, IntroSource.CHAPTER), window)
    }

    @Test
    fun `erkennt Opening-Kapitel`() {
        val chapters = listOf(ChapterInfo("Prolog", 0, 60_000), ChapterInfo("Opening Credits", 60_000, 150_000))
        assertEquals(IntroWindow(60_000, 150_000, IntroSource.CHAPTER), IntroResolver.resolve(chapters, null))
    }

    @Test
    fun `nutzt Profil ohne passende Kapitel`() {
        val window = IntroResolver.resolve(emptyList(), IntroProfile("s", 10_000, 90_000))
        assertEquals(IntroWindow(10_000, 90_000, IntroSource.PROFILE), window)
    }

    @Test
    fun `ohne Kapitel und Profil gibt es kein bekanntes Intro`() {
        assertNull(IntroResolver.resolve(emptyList(), null))
    }

    @Test
    fun `findet Abspann in der zweiten Hälfte`() {
        val chapters = listOf(ChapterInfo("Opening Credits", 0, 60_000), ChapterInfo("End Credits", 2_300_000, 2_400_000))
        assertEquals(2_300_000L, ChapterMatcher.findOutroStart(chapters, 2_400_000))
    }
}
