package com.mg.player.integration

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.mg.player.core.PlayerApp
import com.mg.player.domain.model.ContentType
import com.mg.player.domain.model.PlaylistType
import com.mg.player.domain.repository.PlaylistInput
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Großer Anbieter: 30.000 Sender, 60.000 Filme, 8.000 Serien. Import, Suchindex und Suche müssen vollständig
 * und in vertretbarer Zeit durchlaufen. (Zeiten auf dem PC – ein TV-Stick ist etwa 5–10× langsamer.)
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class LargeLibraryTest {

    private val xtream = FakeXtreamServer(extraLive = 30_000, extraMovies = 60_000, extraSeries = 8_000)
    private val container get() = ApplicationProvider.getApplicationContext<PlayerApp>().container

    @After
    fun tearDown() = xtream.shutdown()

    @Test
    fun importIndexAndSearchScale() = runBlocking {
        val base = xtream.start()
        val id = container.playlistRepository.addPlaylist(
            PlaylistInput(name = "Groß", type = PlaylistType.XTREAM, serverUrl = base, username = "u", password = "p", allowInsecureHttp = true),
        )
        val importStart = System.currentTimeMillis()
        container.playlistRepository.refreshContent(id) {}
        // Ab 3.25 baut der Suchindex im Hintergrund – Importzeit ohne ihn, dann auf ihn warten.
        val importMs = System.currentTimeMillis() - importStart
        container.searchIndexer.awaitIdle(id, 300_000)
        val indexed = container.searchIndexer.indexedCount(id)
        assertEquals("jeder Titel muss im Suchindex stehen", 30_000 + 60_000 + 8_000 + xtream.live.size + xtream.movies.size + xtream.series.size, indexed)

        fun timed(q: String): Pair<Long, Int> {
            val t = System.currentTimeMillis()
            val hits = runBlocking { container.searchRepository.search(id, q) }
            return (System.currentTimeMillis() - t) to hits.size
        }
        // Erste Abfrage wärmt die Datenbank auf.
        timed("zdf")
        val results = listOf("zdf", "sender 2999", "film nummer 59999", "folge 7999", "neo", "de", "xyzxyz").associateWith { timed(it) }
        println("Import: $importMs ms, Index: $indexed Einträge")
        results.forEach { (q, r) -> println("Suche „$q“: ${r.first} ms, ${r.second} Treffer") }
        assertTrue(runBlocking { container.searchRepository.search(id, "sender 2999") }.any { it.type == ContentType.LIVE && it.title == "Sender 2999" })
        assertTrue(runBlocking { container.searchRepository.search(id, "film nummer 59999") }.any { it.title == "Film Nummer 59999" })
        results.forEach { (q, r) -> assertTrue("Suche „$q“ zu langsam: ${r.first} ms", r.first < 3_000) }
    }
}
