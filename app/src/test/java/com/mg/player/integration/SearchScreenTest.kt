package com.mg.player.integration

import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.mg.player.core.PlayerApp
import com.mg.player.domain.model.PlaylistType
import com.mg.player.domain.repository.PlaylistInput
import com.mg.player.ui.search.SEARCH_PLACEHOLDER
import com.mg.player.ui.search.SearchScreen
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** Die Suche so bedienen wie am Fernseher: Feld mit OK öffnen, Text eingeben, Treffer erscheinen. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "w960dp-h540dp-xhdpi")
class SearchScreenTest {

    @get:Rule
    val compose = createComposeRule()

    private val server = MockWebServer()
    private val container get() = ApplicationProvider.getApplicationContext<PlayerApp>().container

    @Before
    fun setUp() = runBlocking {
        server.enqueue(MockResponse().setBody(TestPlaylists.M3U))
        server.start()
        val id = container.playlistRepository.addPlaylist(
            PlaylistInput(name = "Test", type = PlaylistType.M3U, m3uUrl = server.url("/playlist.m3u").toString(), allowInsecureHttp = true),
        )
        container.playlistRepository.refreshContent(id) {}
        container.settingsRepository.update { it.copy(activePlaylistId = id) }
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun typingShowsResultsInSections() {
        compose.setContent { TestHost(container) { SearchScreen() } }
        compose.onNodeWithText(SEARCH_PLACEHOLDER).performClick()
        compose.onNode(hasSetTextAction()).performTextInput("zdf")
        compose.waitUntil(timeoutMillis = 10_000) { compose.onAllNodesWithText("Live-TV").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("ZDF", substring = false, useUnmergedTree = true).assertExists()
    }
}
