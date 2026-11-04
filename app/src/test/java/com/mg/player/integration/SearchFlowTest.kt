package com.mg.player.integration

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.mg.player.core.PlayerApp
import com.mg.player.domain.model.ContentType
import com.mg.player.domain.model.PlaylistType
import com.mg.player.domain.repository.PlaylistInput
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Ende-zu-Ende: Playlist über HTTP importieren (echter Importer, echte Datenbank), dann mit der echten
 * Suche suchen – so, wie es die App am Fernseher tut.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class SearchFlowTest {

    private val server = MockWebServer()
    private val container get() = ApplicationProvider.getApplicationContext<PlayerApp>().container
    private var playlistId = 0L

    @Before
    fun setUp() = runBlocking {
        server.enqueue(MockResponse().setBody(TestPlaylists.M3U))
        server.start()
        playlistId = container.playlistRepository.addPlaylist(
            PlaylistInput(name = "Test", type = PlaylistType.M3U, m3uUrl = server.url("/playlist.m3u").toString(), allowInsecureHttp = true),
        )
        container.playlistRepository.refreshContent(playlistId) {}
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun search(query: String) = runBlocking { container.searchRepository.search(playlistId, query) }

    @Test
    fun findsChannelByWordStart() {
        val hits = search("zdf")
        assertTrue(hits.toString(), hits.any { it.type == ContentType.LIVE && it.title.contains("ZDF") })
    }

    @Test
    fun groupsQualityVariantsIntoOneHit() {
        val zdf = search("zdf hd").filter { it.type == ContentType.LIVE && it.title == "ZDF" }
        assertEquals(zdf.toString(), 1, zdf.size)
    }

    @Test
    fun findsMovieAndSeries() {
        assertTrue(search("incep").any { it.type == ContentType.MOVIE })
        assertTrue(search("dark").any { it.type == ContentType.SERIES })
    }

    @Test
    fun findsInsideWords() {
        assertTrue(search("neo").toString(), search("neo").any { it.title.contains("neo", ignoreCase = true) })
        assertTrue(search("schau").toString(), search("schau").any { it.title.contains("tagesschau", ignoreCase = true) })
    }

    @Test
    fun umlautsAndCaseDoNotMatter() {
        assertTrue(search("KAISER").any { it.type == ContentType.MOVIE })
    }

    @Test
    fun specialCharactersDoNotBreakSearch() {
        listOf("\"", "-", "*", "%", "a\"b", "'", "(", "zdf)").forEach { query -> search(query) }
    }
}
