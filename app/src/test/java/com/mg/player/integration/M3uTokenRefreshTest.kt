package com.mg.player.integration

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.mg.player.core.PlayerApp
import com.mg.player.domain.model.ContentType
import com.mg.player.domain.model.PlaylistType
import com.mg.player.domain.repository.PlaylistInput
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** Anbieter mit wechselnden Tokens in den Stream-Adressen: Favoriten überleben eine Aktualisierung. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class M3uTokenRefreshTest {

    private val server = MockWebServer()
    private val container get() = ApplicationProvider.getApplicationContext<PlayerApp>().container

    private fun playlist(token: String) = """
        #EXTM3U
        #EXTINF:-1 tvg-id="zdf.de" group-title="DE | Vollprogramm",DE: ZDF HD
        http://stream.invalid/play.php?id=1&token=$token
        #EXTINF:-1 group-title="Filme | Drama",Nordlicht (2023)
        http://stream.invalid/movie/nordlicht.mkv?token=$token&expires=99
    """.trimIndent()

    @After
    fun tearDown() = server.shutdown()

    @Test
    fun favoritesSurviveRotatingTokens() = runBlocking {
        server.enqueue(MockResponse().setBody(playlist("AAA")))
        server.enqueue(MockResponse().setBody(playlist("BBB")))
        server.start()
        val id = container.playlistRepository.addPlaylist(
            PlaylistInput(name = "Token", type = PlaylistType.M3U, m3uUrl = server.url("/list.m3u").toString(), allowInsecureHttp = true),
        )
        container.playlistRepository.refreshContent(id) {}
        val channel = container.searchRepository.search(id, "zdf").first { it.type == ContentType.LIVE }
        val movie = container.searchRepository.search(id, "nordlicht").first { it.type == ContentType.MOVIE }
        container.playbackRepository.toggleFavorite(ContentType.LIVE, channel.id)
        container.playbackRepository.toggleFavorite(ContentType.MOVIE, movie.id)

        container.playlistRepository.refreshContent(id) {}

        val channelAfter = container.searchRepository.search(id, "zdf").first { it.type == ContentType.LIVE }
        val movieAfter = container.searchRepository.search(id, "nordlicht").first { it.type == ContentType.MOVIE }
        assertEquals(channel.id, channelAfter.id)
        assertEquals(movie.id, movieAfter.id)
        assertTrue(container.playbackRepository.isFavorite(ContentType.LIVE, channelAfter.id).first())
        assertTrue(container.playbackRepository.isFavorite(ContentType.MOVIE, movieAfter.id).first())
    }
}
