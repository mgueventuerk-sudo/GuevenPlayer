package com.mg.player.screenshots

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.mg.player.core.PlayerApp
import com.mg.player.data.sync.SyncState
import com.mg.player.domain.model.PlaylistType
import com.mg.player.domain.repository.PlaylistInput
import com.mg.player.ui.home.HomeScreen
import com.mg.player.ui.home.SyncBanner
import com.mg.player.ui.search.SEARCH_PLACEHOLDER
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Startseite mit allen Bereichen der oberen Leiste – jeweils dunkel und hell aufgenommen.
 * Die Bereiche werden wie am Fernseher über die Tabs geöffnet (prüft damit auch die Navigation).
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w960dp-h540dp-land-television-xhdpi")
class MainScreensShotTest {

    @get:Rule
    val compose = createComposeRule()

    private val server = ShowcaseServer()
    private val container get() = ApplicationProvider.getApplicationContext<PlayerApp>().container

    @Before
    fun setUp() {
        Showcase.prepare(server, container)
    }

    @After
    fun tearDown() = server.shutdown()

    private fun openHome() {
        compose.setContent { TvHost(container) { HomeScreen() } }
        compose.waitForText("ZDF")
        compose.settle()
    }

    private fun openTab(label: String) {
        compose.clickable(label).pressOk()
        compose.settle()
    }

    /** Dezente Aktualisierungs-Anzeige (3.11): einzeilig unten rechts, über dem Live-TV. */
    @Test
    fun syncIndicator() {
        val state = SyncState(running = true, message = "Anbieter B: Lade EPG…", count = 48_213, step = "2/3")
        compose.setContent {
            TvHost(container) {
                Box(Modifier.fillMaxSize()) {
                    HomeScreen()
                    SyncBanner(state, Modifier.align(Alignment.BottomEnd).padding(end = 18.dp, bottom = 14.dp))
                }
            }
        }
        compose.waitForText("ZDF")
        compose.waitForText("2/3 · Anbieter B: Lade EPG… · 48.213")
        compose.shootBothThemes("01c_aktualisierung_dezent")
    }

    @Test
    fun liveTv() {
        openHome()
        compose.waitForText("Quellen")
        compose.shootBothThemes("01_live_tv")
    }

    @Test
    fun guide() {
        openHome()
        openTab("TV-Guide")
        compose.waitForText("Sendung auswählen")
        compose.waitForText("Das Erste")
        compose.shootBothThemes("02_tv_guide")
    }

    @Test
    fun movies() {
        openHome()
        openTab("Filme")
        compose.waitForText("Nordlicht")
        compose.shootBothThemes("03a_filme")
        // Mit dem Steuerkreuz ins Raster: Poster hebt sich, der Hintergrund übernimmt sein Bild.
        compose.remote(Key.DirectionRight, times = 2)
        compose.shootBothThemes("03b_filme_poster_fokus")
    }

    @Test
    fun series() {
        openHome()
        openTab("Serien")
        compose.waitForText("Orbit")
        compose.shootBothThemes("04_serien")
    }

    @Test
    fun searchPagesWithChannelKeys() {
        val second = runBlocking {
            container.playlistRepository.addPlaylist(
                PlaylistInput(
                    name = "Zweitzugang",
                    type = PlaylistType.XTREAM,
                    serverUrl = server.url,
                    username = "demo",
                    password = "demo",
                    allowInsecureHttp = true,
                ),
            ).also { container.playlistRepository.refreshContent(it) {} }
        }
        // Die gemerkte Seite stammt evtl. aus einem anderen Test (Einstellungen bleiben im Testprozess erhalten).
        runBlocking { container.settingsRepository.update { it.copy(searchPagePlaylistId = null) } }
        openHome()
        openTab("Suche")
        compose.waitForText("Alle Playlists")
        compose.onNodeWithText(SEARCH_PLACEHOLDER).pressOk()
        compose.onNode(hasSetTextAction()).performTextInput("nord")
        compose.waitForText("2023 · Zweitzugang")
        compose.settle()
        compose.shootBothThemes("05c_suche_alle_playlists")
        // Kanal − (runter): „Alle“ → erste Playlist → „Zweitzugang“. Im vollen Testlauf kann ein Tastendruck verloren
        // gehen, solange die Seite noch lädt – daher drücken, bis die Seite gewählt ist (höchstens ein paar Mal).
        repeat(6) {
            if (runBlocking { container.settingsRepository.current().searchPagePlaylistId } == second) return@repeat
            compose.remote(Key.ChannelDown)
            compose.settle(800)
        }
        compose.waitUntil(15_000) { runBlocking { container.settingsRepository.current().searchPagePlaylistId } == second }
        compose.waitUntil(15_000) { compose.onAllNodesWithText("2023 · Zweitzugang").fetchSemanticsNodes().isEmpty() }
        compose.waitForText("Nordlicht")
        compose.settle()
        compose.shootBothThemes("05d_suche_seite_zweitzugang")
    }

    @Test
    fun search() {
        openHome()
        openTab("Suche")
        compose.waitForText("Wonach suchst du?")
        compose.shootBothThemes("05a_suche_leer")
        compose.onNodeWithText(SEARCH_PLACEHOLDER).pressOk()
        compose.onNode(hasSetTextAction()).performTextInput("nord")
        compose.waitForText("Treffer")
        compose.settle()
        compose.shootBothThemes("05b_suche_treffer")
    }

    @Test
    fun recordings() {
        openHome()
        openTab("Aufnahmen")
        compose.waitForText("Noch keine Aufnahmen")
        compose.shootBothThemes("06_aufnahmen")
    }

    @Test
    fun settings() {
        openHome()
        openTab("Einstellungen")
        compose.waitForText("Zugang hinzufügen")
        compose.shootBothThemes("07_einstellungen")
    }
}
