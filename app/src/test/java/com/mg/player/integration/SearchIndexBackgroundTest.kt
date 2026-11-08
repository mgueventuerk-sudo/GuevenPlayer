package com.mg.player.integration

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.mg.player.core.PlayerApp
import com.mg.player.domain.model.Category
import com.mg.player.domain.model.PlaylistType
import kotlinx.coroutines.flow.toList
import com.mg.player.domain.repository.PlaylistInput
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Plan B4 (3.25): Der Suchindex wird nach einer Aktualisierung im Hintergrund aufgebaut. Eine Suche direkt danach
 * muss trotzdem die neuen Titel finden (sie wartet kurz auf den Aufbau), und ein gelöschter Zugang bekommt keinen Index.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class SearchIndexBackgroundTest {

    private val server = MockWebServer()
    private val container get() = ApplicationProvider.getApplicationContext<PlayerApp>().container

    @After
    fun tearDown() = server.shutdown()

    private suspend fun addM3u(path: String = "/list.m3u"): Long = container.playlistRepository.addPlaylist(
        PlaylistInput("Test", PlaylistType.M3U, m3uUrl = server.url(path).toString(), allowInsecureHttp = true),
    )

    @Test
    fun searchRightAfterRefreshFindsNewTitles() = runBlocking {
        server.enqueue(MockResponse().setBody(TestPlaylists.M3U))
        val extra = "\n#EXTINF:-1 group-title=\"DE | Neu\",DE: Ganz Neuer Sender\nhttp://stream.invalid/live/neu.ts\n"
        server.enqueue(MockResponse().setBody(TestPlaylists.M3U + extra))
        server.start()
        val id = addM3u()
        container.playlistRepository.refreshContent(id) {}
        assertTrue(container.searchRepository.search(id, "zdf").isNotEmpty())
        // Zweite Aktualisierung: Der alte Index kennt den neuen Sender nicht – die Suche muss auf den neuen warten.
        container.playlistRepository.refreshContent(id) {}
        val hits = container.searchRepository.search(id, "Neuer")
        assertTrue(hits.any { "Neuer Sender" in it.title })
        container.searchIndexer.awaitIdle(id, 5_000)
        assertFalse(container.searchIndexer.isBusy(id))
        assertTrue(container.searchIndexer.hasIndex(id))
    }

    @Test
    fun searchEmitsActivePlaylistBeforeRemainingPlaylists() = runBlocking {
        repeat(2) { server.enqueue(MockResponse().setBody(TestPlaylists.M3U)) }
        server.start()
        val other = addM3u()
        val active = addM3u("/active.m3u")
        container.playlistRepository.refreshContent(other) {}
        container.playlistRepository.refreshContent(active) {}

        val updates = container.searchRepository.observeSearchPages("zdf", active).toList()

        assertEquals(listOf(active), updates.first().playlists.map { it.playlistId })
        assertEquals(setOf(other), updates.first().pendingPlaylistIds)
        assertTrue(updates.first().all.isNotEmpty())
        assertEquals(listOf(other, active).sorted(), updates.last().playlists.map { it.playlistId })
        assertTrue(updates.last().pendingPlaylistIds.isEmpty())
    }

    @Test
    fun channelPositionMatchesOrderedChannelIds() = runBlocking {
        server.enqueue(MockResponse().setBody(TestPlaylists.M3U))
        server.start()
        val id = addM3u()
        container.playlistRepository.refreshContent(id) {}

        val live = container.liveRepository
        val ids = live.channelIds(id, Category.ALL)
        assertTrue(ids.isNotEmpty())
        ids.forEachIndexed { index, channelId ->
            assertEquals(index, live.channelPosition(id, Category.ALL, channelId))
        }
        assertEquals(-1, live.channelPosition(id, Category.ALL, "missing-channel"))
    }

    @Test
    fun deletedPlaylistGetsNoIndex() = runBlocking {
        server.enqueue(MockResponse().setBody(TestPlaylists.M3U))
        server.start()
        val id = addM3u()
        container.playlistRepository.refreshContent(id) {}
        container.playlistRepository.deletePlaylist(id)
        container.searchIndexer.awaitIdle(id, 5_000)
        assertFalse(container.searchIndexer.hasIndex(id))
    }
}
