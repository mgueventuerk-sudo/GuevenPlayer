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

/** Cloud-only data stress: large accounts and repeated refreshes without a physical device. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class CloudStressTest {

    private val first = FakeXtreamServer(extraLive = 15_000, extraMovies = 30_000, extraSeries = 5_000)
    private val second = FakeXtreamServer(extraLive = 15_000, extraMovies = 30_000, extraSeries = 5_000)
    private val container get() = ApplicationProvider.getApplicationContext<PlayerApp>().container

    @After
    fun tearDown() {
        first.shutdown()
        second.shutdown()
    }

    @Test(timeout = 600_000)
    fun twoLargeAccountsRemainIsolatedAndSearchable() = runBlocking {
        val firstId = addAndImport("Großes Konto A", first)
        val secondId = addAndImport("Großes Konto B", second)
        val firstExpected = expectedIndexSize(first)
        val secondExpected = expectedIndexSize(second)
        // Ab 3.25 baut der Suchindex im Hintergrund – auf ihn warten, bevor er gezählt wird.
        container.searchIndexer.awaitIdle(firstId, INDEX_WAIT_MS)
        container.searchIndexer.awaitIdle(secondId, INDEX_WAIT_MS)
        assertEquals(firstExpected, container.searchIndexer.indexedCount(firstId))
        assertEquals(secondExpected, container.searchIndexer.indexedCount(secondId))

        val queries = listOf(
            firstId to ("sender 14999" to ContentType.LIVE),
            firstId to ("film nummer 29999" to ContentType.MOVIE),
            firstId to ("serie folge 4999" to ContentType.SERIES),
            secondId to ("sender 14999" to ContentType.LIVE),
            secondId to ("film nummer 29999" to ContentType.MOVIE),
            secondId to ("serie folge 4999" to ContentType.SERIES),
        )
        val measurements = queries.map { (playlistId, query) ->
            val (text, type) = query
            val started = System.nanoTime()
            val hits = container.searchRepository.search(playlistId, text)
            val elapsedMs = (System.nanoTime() - started) / 1_000_000
            assertTrue("$text missed in account $playlistId", hits.any { it.type == type })
            assertTrue("$text took $elapsedMs ms", elapsedMs < 5_000)
            "$playlistId/$text=${elapsedMs}ms/${hits.size} hits"
        }
        println("two-account cloud stress: ${firstExpected + secondExpected} indexed entries; ${measurements.joinToString()}")
    }

    @Test(timeout = 180_000)
    fun repeatedRefreshDoesNotAccumulateStaleChannelsOrIndexRows() = runBlocking {
        val server = FakeXtreamServer(extraLive = 1_200, extraMovies = 1_000, extraSeries = 300)
        try {
            val base = server.start()
            val id = container.playlistRepository.addPlaylist(
                PlaylistInput(name = "Refresh soak", type = PlaylistType.XTREAM, serverUrl = base, username = "u", password = "p", allowInsecureHttp = true),
            )
            val baseLive = server.live
            repeat(20) { cycle ->
                server.live = baseLive + Triple((900_000 + cycle).toString(), "Zyklus Kanal $cycle", "1")
                container.playlistRepository.refreshContent(id) {}
                assertEquals(baseLive.size + 1_200 + 1, container.database.channelDao().count(id))
                container.searchIndexer.awaitIdle(id, INDEX_WAIT_MS)
                assertEquals(expectedIndexSize(server, 1_200, 1_000, 300), container.searchIndexer.indexedCount(id))
                assertTrue(
                    "stale channel from cycle ${cycle - 1} survived",
                    container.searchRepository.search(id, "zyklus kanal ${cycle - 1}").none { it.title == "Zyklus Kanal ${cycle - 1}" },
                )
            }
            assertTrue(container.searchRepository.search(id, "zyklus kanal 19").any { it.title == "Zyklus Kanal 19" })
            println("refresh soak: 20 refreshes; ${expectedIndexSize(server, 1_200, 1_000, 300)} indexed entries after final refresh")
        } finally {
            server.shutdown()
        }
    }

    private suspend fun addAndImport(name: String, server: FakeXtreamServer): Long {
        val base = server.start()
        val id = container.playlistRepository.addPlaylist(
            PlaylistInput(name = name, type = PlaylistType.XTREAM, serverUrl = base, username = "u", password = "p", allowInsecureHttp = true),
        )
        container.playlistRepository.refreshContent(id) {}
        return id
    }

    private fun expectedIndexSize(
        server: FakeXtreamServer,
        extraLive: Int = 15_000,
        extraMovies: Int = 30_000,
        extraSeries: Int = 5_000,
    ) = server.live.size + server.movies.size + server.series.size + extraLive + extraMovies + extraSeries

    private companion object {
        const val INDEX_WAIT_MS = 120_000L
    }
}
