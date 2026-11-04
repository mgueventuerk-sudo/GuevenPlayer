package com.mg.player.integration

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.mg.player.core.PlayerApp
import com.mg.player.domain.model.PlaylistType
import com.mg.player.domain.repository.PlaylistInput
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** M3U im Xtream-Format: In der Datenbank stehen keine Zugangsdaten, abgespielt wird trotzdem die echte Adresse. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class M3uCredentialMaskTest {

    private val server = MockWebServer()
    private val container get() = ApplicationProvider.getApplicationContext<PlayerApp>().container

    @After
    fun tearDown() = server.shutdown()

    @Test
    fun credentialsAreNotStoredButPlayed() = runBlocking {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val base = server.url("/").toString().trimEnd('/')
                return MockResponse().setBody(
                    "#EXTM3U\n" +
                        "#EXTINF:-1 tvg-id=\"zdf.de\" group-title=\"DE | TV\",ZDF HD\n$base/live/max/geheim/101.ts\n" +
                        "#EXTINF:-1 group-title=\"DE | Filme\",Heat (1995)\n$base/movie/max/geheim/501.mkv\n",
                )
            }
        }
        server.start()
        val base = server.url("/").toString().trimEnd('/')
        val pid = container.playlistRepository.addPlaylist(
            PlaylistInput(name = "M3U", type = PlaylistType.M3U, m3uUrl = "$base/get.php?username=max&password=geheim&type=m3u_plus", allowInsecureHttp = true),
        )
        container.playlistRepository.refreshContent(pid) {}

        val db = container.database
        val channels = db.channelDao().idsAll(pid).mapNotNull { db.channelDao().entity(it) }
        val channel = channels.first { "/{u}/{p}/" in it.streamUrl }
        assertFalse("Passwort darf nicht in der Datenbank stehen", "geheim" in channel.streamUrl)
        assertTrue(channel.streamUrl.contains("/{u}/{p}/"))

        assertEquals("$base/live/max/geheim/101.ts", container.streamUrlResolver.resolve(channel.streamUrl, channel.id))
    }
}
