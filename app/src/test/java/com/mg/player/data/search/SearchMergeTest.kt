package com.mg.player.data.search

import com.mg.player.domain.model.ContentType
import com.mg.player.domain.model.SearchHit
import org.junit.Assert.assertEquals
import org.junit.Test

/** Zusammenführen der Treffer mehrerer Zugänge: gleiche Titel nebeneinander, der beste zuerst. */
class SearchMergeTest {

    private fun live(id: String, title: String, quality: String? = null) = SearchHit(id, ContentType.LIVE, title, null, listOfNotNull(quality))

    private fun movie(id: String, title: String, year: String?, quality: String? = null) =
        SearchHit(id, ContentType.MOVIE, title, null, listOfNotNull(quality), year)

    @Test
    fun sameChannelGroupedBestQualityFirst() {
        val active = SourceHits(1, "Aktiv", listOf(live("1:a", "ZDF", "HD"), live("1:b", "ZDFneo")))
        val other = SourceHits(2, "Zweit", listOf(live("2:a", "ZDF", "4K"), live("2:c", "ZDFinfo")))
        val merged = mergeAcrossPlaylists(listOf(active, other), "zdf", 100)
        // "ZDF" exakt: 4K aus dem zweiten Zugang vor HD aus dem aktiven; danach Wortanfang-Treffer, aktiver zuerst.
        assertEquals(listOf("2:a", "1:a", "1:b", "2:c"), merged.map { it.id })
        assertEquals(listOf("Zweit", "Aktiv", "Aktiv", "Zweit"), merged.map { it.source })
        assertEquals(listOf(2L, 1L, 1L, 2L), merged.map { it.playlistId })
    }

    @Test
    fun equalQualityPrefersActivePlaylist() {
        val merged = mergeAcrossPlaylists(
            listOf(SourceHits(1, "A", listOf(live("1:a", "RTL"))), SourceHits(2, "B", listOf(live("2:a", "RTL")))),
            "rtl",
            100,
        )
        assertEquals(listOf("1:a", "2:a"), merged.map { it.id })
    }

    @Test
    fun moviesOfDifferentYearsStaySeparateAndShowTheirSource() {
        val a = SourceHits(1, "A", listOf(movie("1:m", "Dune", "1984"), movie("1:n", "Dune", "2021", "FHD")))
        val b = SourceHits(2, "B", listOf(movie("2:n", "Dune", "2021", "4K")))
        val merged = mergeAcrossPlaylists(listOf(a, b), "dune", 100)
        assertEquals(listOf("1:m", "2:n", "1:n"), merged.map { it.id })
        assertEquals(listOf("1984 · A", "2021 · B", "2021 · A"), merged.map { it.subtitle })
    }

    @Test
    fun singlePlaylistStaysUnchanged() {
        val hits = listOf(live("1:a", "ZDF"))
        assertEquals(hits, mergeAcrossPlaylists(listOf(SourceHits(1, "A", hits)), "zdf", 100))
    }

    @Test
    fun limitAppliesPerArea() {
        val many = (1..10).map { live("1:$it", "Sender $it") } + movie("1:m", "Sender Film", null)
        val merged = mergeAcrossPlaylists(listOf(SourceHits(1, "A", many), SourceHits(2, "B", emptyList())), "sender", 3)
        assertEquals(3, merged.count { it.type == ContentType.LIVE })
        assertEquals(1, merged.count { it.type == ContentType.MOVIE })
    }
}
