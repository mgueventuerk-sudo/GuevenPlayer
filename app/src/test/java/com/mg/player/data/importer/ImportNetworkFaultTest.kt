package com.mg.player.data.importer

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.mg.player.core.PlayerApp
import com.mg.player.domain.model.PlaylistType
import com.mg.player.domain.repository.PlaylistInput
import com.mg.player.integration.FakeXtreamServer
import com.mg.player.integration.Toxiproxy
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** Bricht die Verbindung mitten in der Senderliste ab, bleibt die bisherige Liste vollständig erhalten. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class ImportNetworkFaultTest {
    private val xtream = FakeXtreamServer(extraLive = 3000)
    private val toxi = Toxiproxy()
    private val container get() = ApplicationProvider.getApplicationContext<PlayerApp>().container

    @After
    fun tearDown() {
        toxi.reset()
        xtream.shutdown()
    }

    @Test
    fun cutDuringRefreshKeepsPreviousChannels() = runBlocking {
        toxi.assumeRunning()
        xtream.start()
        val proxied = "http://" + toxi.proxy("xtream", xtream.server.port)
        val pid = container.playlistRepository.addPlaylist(
            PlaylistInput(name = "Test", type = PlaylistType.XTREAM, serverUrl = proxied, username = "u", password = "p", allowInsecureHttp = true),
        )
        container.playlistRepository.refreshContent(pid) {}
        val before = container.database.channelDao().count(pid)
        assertTrue("nur $before Sender", before > 3000)

        toxi.toxic("xtream", "limit_data", "\"bytes\":50000")
        val failure = runCatching { container.playlistRepository.refreshContent(pid) {} }.exceptionOrNull()

        assertNotNull("Abbruch wurde nicht gemeldet", failure)
        assertEquals(before, container.database.channelDao().count(pid))
    }
}
