package com.mg.player.data.tmdb

import org.junit.Assert.assertEquals
import org.junit.Test

class TitleCleanerTest {

    @Test
    fun `entfernt Länderpräfix, Jahr und Qualität`() {
        assertEquals(TitleCleaner.Cleaned("Der Pate", "1972"), TitleCleaner.clean("DE: Der Pate (1972) FHD"))
    }

    @Test
    fun `entfernt Präfix in Strichen und 4K`() {
        assertEquals(TitleCleaner.Cleaned("The Matrix", null), TitleCleaner.clean("|EN| The Matrix 4K"))
    }

    @Test
    fun `versteht Release-Namen mit Punkten`() {
        assertEquals(TitleCleaner.Cleaned("Dune Part Two", "2024"), TitleCleaner.clean("Dune.Part.Two.2024.MULTI.1080p"))
    }

    @Test
    fun `Zahl im Titel ist kein Jahr`() {
        assertEquals(TitleCleaner.Cleaned("Blade Runner 2049", null), TitleCleaner.clean("Blade Runner 2049"))
        assertEquals(TitleCleaner.Cleaned("2001: Odyssee im Weltraum", "1968"), TitleCleaner.clean("2001: Odyssee im Weltraum (1968)"))
    }

    @Test
    fun `normalisiert für den Vergleich`() {
        assertEquals("diefabelhaftewelt", TitleCleaner.normalize("Die fabelhafte Welt!"))
        assertEquals("muenchen", TitleCleaner.normalize("München"))
    }
}
