package com.mg.player.data.sport

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.mg.player.core.PlayerApp
import com.mg.player.domain.model.AppSettings
import com.mg.player.domain.model.EventStatus
import com.mg.player.domain.model.PlaylistType
import com.mg.player.domain.model.SportEvent
import com.mg.player.domain.repository.PlaylistInput
import com.mg.player.domain.repository.SettingsRepository
import com.mg.player.integration.FakeXtreamServer
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** Die Neuzuordnung vor Anstoß wartet nicht auf einen langsamen Sport-Abruf (bis 3.12.1 tat sie das). */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class SportRefreshLockTest {
    private val xtream = FakeXtreamServer()
    private val slow = MockWebServer()
    private val container get() = ApplicationProvider.getApplicationContext<PlayerApp>().container
    private var sportScope: CoroutineScope? = null

    @After
    fun tearDown() {
        sportScope?.cancel()
        xtream.shutdown()
        slow.shutdown()
    }

    @Test
    fun rematchDoesNotWaitForSlowFetch() = runBlocking {
        val pid = container.playlistRepository.addPlaylist(
            PlaylistInput(name = "Test", type = PlaylistType.XTREAM, serverUrl = xtream.start(), username = "u", password = "p", allowInsecureHttp = true),
        )
        container.playlistRepository.refreshContent(pid) {}
        // Jede ESPN-Antwort braucht 4 s.
        slow.enqueue(MockResponse().setBody("{\"events\":[]}").setBodyDelay(4, TimeUnit.SECONDS))
        repeat(20) { slow.enqueue(MockResponse().setBody("{\"events\":[]}").setBodyDelay(4, TimeUnit.SECONDS)) }
        slow.start()
        SportEndpoints.espn = slow.url("/espn").toString().trimEnd('/')

        val dao = container.database.sportDao()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        sportScope = scope
        val repo = SportRepository(
            dao = dao,
            settings = TestSettingsRepository(AppSettings(activePlaylistId = pid, sportLeagues = setOf("espn:soccer/ger.1"))),
            sources = listOf(EspnSource(container.httpFetcher)),
            matcher = ChannelEventMatcher(dao),
            scope = scope,
            hasPlaylists = { true },
            contentUpdated = flowOf(0L),
        )
        val fetch = async(Dispatchers.IO) { repo.refresh(force = true) }
        delay(500)
        val now = System.currentTimeMillis()
        val game = SportEvent("espn:soccer/ger.1#1", "soccer", "Bundesliga", "A – B", "Alpha", "Beta", now + 10 * 60_000L, null, EventStatus.PRE, emptyList())
        withTimeout(2_000) { repo.rematch(listOf(game)) }
        assertFalse("Abruf sollte noch laufen – sonst prüft der Test nichts", fetch.isCompleted)
        fetch.cancel()
    }

    private class TestSettingsRepository(initial: AppSettings) : SettingsRepository {
        private val value = MutableStateFlow(initial)
        override val settings: StateFlow<AppSettings> = value
        override suspend fun current() = value.value
        override suspend fun update(transform: (AppSettings) -> AppSettings) {
            value.update(transform)
        }
    }
}
