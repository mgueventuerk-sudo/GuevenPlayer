package com.mg.player.integration

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.mg.player.core.PlayerApp
import com.mg.player.data.sync.AutoRefresher
import com.mg.player.domain.model.AutoRefreshSchedule.DAY_MS
import com.mg.player.domain.model.PlaylistType
import com.mg.player.domain.repository.PlaylistInput
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Ende-zu-Ende mit echter Datenbank: Intervall speichern, „Gerät aus“ (Uhr springt vor), Countdown abgelaufen →
 * Playlist wird neu geladen; vorher nicht.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class AutoRefreshIntegrationTest {

    private val server = MockWebServer()
    private val container get() = ApplicationProvider.getApplicationContext<PlayerApp>().container

    @After
    fun tearDown() = server.shutdown()

    @Test
    fun refreshesOnlyAfterCountdownEvenAcrossOffTime() = runBlocking {
        repeat(3) { server.enqueue(MockResponse().setBody(TestPlaylists.M3U)) }
        server.start()
        val repo = container.playlistRepository
        val id = repo.addPlaylist(PlaylistInput("Test", PlaylistType.M3U, m3uUrl = server.url("/list.m3u").toString(), allowInsecureHttp = true))
        repo.refreshContent(id) {}
        repo.setAutoRefreshDays(id, 2)
        val first = repo.getPlaylist(id)!!
        assertEquals(2, first.autoRefreshDays)

        var now = first.lastUpdatedMs + 2 * DAY_MS - 60_000
        val refresher = AutoRefresher({ repo.observePlaylists().first() }, { repo.refreshContent(it) {} }, clock = { now })
        assertEquals(first.lastUpdatedMs + 2 * DAY_MS, refresher.runDue("Test"))
        assertEquals(first.lastUpdatedMs, repo.getPlaylist(id)!!.lastUpdatedMs)

        // Neuer Prozess (App war aus) – der Countdown kommt allein aus der Datenbank.
        Thread.sleep(5)
        now = first.lastUpdatedMs + 5 * DAY_MS
        val afterRestart = AutoRefresher({ repo.observePlaylists().first() }, { repo.refreshContent(it) {} }, clock = { now })
        afterRestart.runDue("Test")
        assertNotEquals(first.lastUpdatedMs, repo.getPlaylist(id)!!.lastUpdatedMs)
        assertEquals(2, server.requestCount)
    }
}
