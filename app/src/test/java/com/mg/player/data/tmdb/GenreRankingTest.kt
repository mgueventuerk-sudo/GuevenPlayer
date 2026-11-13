package com.mg.player.data.tmdb

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GenreRankingTest {

    @Test
    fun mostFrequentGenresWinTies() {
        val genres = listOf(listOf("Action", "Drama"), listOf("Action"), listOf("Komödie"))
        assertEquals(setOf("Action", "Drama"), GenreRanking.preferredGenres(genres, max = 2))
        assertEquals(setOf("Action"), GenreRanking.preferredGenres(genres, max = 1))
    }

    @Test
    fun emptyInputHasNoPreferredGenres() {
        assertTrue(GenreRanking.preferredGenres(emptyList()).isEmpty())
    }

    @Test
    fun matchingItemsMoveToTheFrontButKeepTheirRelativeOrder() {
        data class Item(val title: String, val genres: List<String>)
        val items = listOf(Item("A", listOf("Drama")), Item("B", listOf("Action")), Item("C", listOf("Action")), Item("D", listOf("Komödie")))
        val ranked = GenreRanking.rank(items, setOf("Action")) { it.genres }
        assertEquals(listOf("B", "C", "A", "D"), ranked.map { it.title })
    }

    @Test
    fun noPreferredGenresLeavesTheOrderUnchanged() {
        val items = listOf("A", "B", "C")
        assertEquals(items, GenreRanking.rank(items, emptySet()) { emptyList() })
    }

    @Test
    fun genreNamesComeFromTmdbsFixedIdList() {
        assertEquals(listOf("Action", "Komödie"), TmdbGenreNames.of(listOf(28, 35)))
        assertEquals(emptyList<String>(), TmdbGenreNames.of(listOf(999_999)))
    }
}
