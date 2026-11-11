package com.mg.player.screenshots

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.mg.player.core.PlayerApp
import com.mg.player.domain.model.PlaylistType
import com.mg.player.domain.repository.PlaylistInput
import com.mg.player.ui.common.CrashReportHost
import com.mg.player.ui.navigation.Navigator
import com.mg.player.ui.navigation.Screen
import com.mg.player.ui.search.FILTER_TAG
import com.mg.player.ui.search.SEARCH_PLACEHOLDER
import com.mg.player.ui.setup.CountryFilterScreen
import com.mg.player.ui.setup.SetupScreen
import java.io.File
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Die Handy-Oberfläche im Hochformat (OnePlus 13) – jeder Bereich dunkel und hell. Bedient wird per Fingertipp.
 * Ablage: build/outputs/roborazzi/build/screenshots/handy_*.png
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = PHONE_PORTRAIT)
class PhoneShotTest {

    @get:Rule
    val compose = createComposeRule()

    private val server = ShowcaseServer()
    private val container get() = ApplicationProvider.getApplicationContext<PlayerApp>().container
    private lateinit var data: ShowcaseData

    @Before
    fun setUp() {
        data = Showcase.prepare(server, container)
    }

    @After
    fun tearDown() = server.shutdown()

    private fun open(screen: Screen = Screen.Home) {
        val navigator = Navigator(screen)
        compose.setContent { PhoneRoot(container, navigator) }
        compose.settle()
    }

    private fun tab(label: String) {
        compose.clickable(label).performClick()
        compose.settle()
    }

    private fun waitForDescription(description: String) {
        compose.waitUntil(15_000) { compose.onAllNodes(hasContentDescription(description)).fetchSemanticsNodes().isNotEmpty() }
    }

    @Test
    fun liveTv() {
        open()
        compose.waitForText("ZDF")
        compose.settle()
        compose.shootBothThemes("handy_01_live_tv")
    }

    @Test
    fun guide() {
        open()
        tab("Guide")
        compose.waitForText("Das Erste")
        compose.settle()
        compose.shootBothThemes("handy_02_guide")
    }

    @Test
    fun movies() {
        open()
        tab("Filme")
        waitForDescription("Nordlicht")
        compose.settle()
        compose.shootBothThemes("handy_03_filme")
    }

    @Test
    fun series() {
        open()
        tab("Serien")
        waitForDescription("Orbit")
        compose.settle()
        compose.shootBothThemes("handy_04_serien")
    }

    @Test
    fun search() {
        open()
        tab("Suche")
        compose.waitForText("Wonach suchst du?")
        compose.onNodeWithText(SEARCH_PLACEHOLDER).performClick()
        compose.onNode(hasSetTextAction()).performTextInput("nord")
        compose.waitForText("Treffer")
        compose.settle()
        compose.shootBothThemes("handy_05_suche")
    }

    @Test
    fun searchAllPlaylists() {
        // Zweiter Zugang mit denselben Inhalten: Treffer beider Zugänge stehen nebeneinander, mit Zugangsname.
        runBlocking {
            val second = container.playlistRepository.addPlaylist(
                PlaylistInput(
                    name = "Zweitzugang",
                    type = PlaylistType.XTREAM,
                    serverUrl = server.url,
                    username = "demo",
                    password = "demo",
                    allowInsecureHttp = true,
                ),
            )
            container.playlistRepository.refreshContent(second) {}
        }
        // Die gemerkte Seite stammt evtl. aus einem anderen Test (Einstellungen bleiben im Testprozess erhalten).
        runBlocking { container.settingsRepository.update { it.copy(searchPagePlaylistId = null) } }
        open()
        tab("Suche")
        compose.waitForText("Alle Playlists")
        compose.onNodeWithText(SEARCH_PLACEHOLDER).performClick()
        compose.onNode(hasSetTextAction()).performTextInput("nord")
        // „Alle Playlists“: Treffer beider Playlists nebeneinander, darunter der Name der Playlist.
        compose.waitForText("2023 · Zweitzugang")
        compose.waitForText("2023 · Mein IPTV")
        compose.settle()
        compose.shootBothThemes("handy_05b_suche_alle_playlists")
        // Wischen nach links → Seite „Mein IPTV“: nur deren Treffer, ohne Playlist-Namen.
        // Über die ganze Breite wischen (ein kurzer Wisch über nur einen Text reicht dem Pager nicht).
        compose.onRoot().performTouchInput { swipeLeft(startX = width * 0.9f, endX = width * 0.1f) }
        compose.waitUntil(15_000) { compose.onAllNodesWithText("2023 · Zweitzugang").fetchSemanticsNodes().isEmpty() }
        compose.waitForText("Nordlicht")
        val first = runBlocking { container.playlistRepository.observePlaylists().first().minOf { it.id } }
        compose.waitUntil(15_000) { runBlocking { container.settingsRepository.current().searchPagePlaylistId } == first }
        compose.settle()
        compose.shootBothThemes("handy_05c_suche_seite_playlist")
        // Bereichs-Filter: nur Serien.
        compose.onNode(hasText("Serien") and hasAnyAncestor(hasTestTag(FILTER_TAG))).performClick()
        compose.waitUntil(15_000) { compose.onAllNodesWithText("Nordlicht").fetchSemanticsNodes().isEmpty() }
    }

    @Test
    fun recordings() {
        open()
        compose.clickable("Aufnahmen").performClick()
        compose.waitForText("Noch keine Aufnahmen")
        compose.shootBothThemes("handy_06_aufnahmen")
    }

    @Test
    fun settings() {
        open()
        compose.clickable("Einstellungen").performClick()
        compose.waitForText("Darstellung")
        compose.settle()
        compose.shootBothThemes("handy_07_einstellungen")
        compose.onAllNodes(hasText("Zugänge") and hasClickAction()).onFirst().performClick()
        compose.waitForText("Zugang hinzufügen")
        compose.settle()
        compose.shootBothThemes("handy_07b_einstellungen_zugaenge")
        compose.onAllNodes(hasText("Mein IPTV") and hasClickAction()).onFirst().performClick()
        compose.waitForText("Zugangsdaten bearbeiten")
        compose.settle()
        compose.shootBothThemes("handy_07c_einstellungen_zugang")
    }

    @Test
    fun movieDetail() {
        open(Screen.MovieDetail(data.movieId))
        compose.waitForText("Abspielen")
        compose.waitForText("Forscherin")
        compose.settle()
        compose.shootBothThemes("handy_08_film_details")
    }

    @Test
    fun seriesDetail() {
        open(Screen.SeriesDetail(data.seriesId))
        compose.waitForText("Staffel 2")
        compose.settle()
        compose.shootBothThemes("handy_09_serien_details")
    }

    @Test
    fun favoriteToast() {
        open()
        compose.waitForText("ZDF")
        compose.onAllNodesWithText("ZDF").onFirst().performTouchInput { longClick() }
        compose.waitForText("ist jetzt ein Favorit")
        compose.shootBothThemes("handy_10_favorit_hinweis")
    }

    @Test
    fun setup() {
        compose.setContent { PhoneHost(container) { SetupScreen(onFinished = {}, firstSetup = true) } }
        compose.waitForText("Manuell eingeben")
        compose.settle()
        compose.shootBothThemes("handy_11_einrichtung")
    }

    @Test
    fun setupTyping() {
        compose.setContent { PhoneHost(container) { SetupScreen(onFinished = {}, firstSetup = true) } }
        compose.waitForText("Manuell eingeben")
        compose.onNodeWithContentDescription("Server-URL").performClick()
        compose.onNode(hasSetTextAction() and isFocused()).performTextInput("http://mein-anbieter.tv:8080")
        compose.settle()
        compose.shootBothThemes("handy_11b_einrichtung_tippen")
    }

    @Test
    fun setupErrorFitsPortrait() {
        compose.setContent { PhoneHost(container) { SetupScreen(onFinished = {}, firstSetup = true) } }
        compose.waitForText("Manuell eingeben")
        listOf("Server-URL" to "http://127.0.0.1:9", "Benutzername" to "u", "Passwort" to "p").forEach { (field, text) ->
            compose.onNodeWithContentDescription(field).performScrollTo().performClick()
            compose.onNode(hasSetTextAction() and isFocused()).performTextInput(text)
        }
        // HTTP ist absichtlich gesperrt, bis der Nutzer die unverschlüsselte Verbindung ausdrücklich freigibt.
        compose.onNodeWithText("HTTP zulassen").performScrollTo().performClick()
        compose.onNodeWithText("Speichern & laden").performScrollTo().performClick()
        compose.waitForText("Laden fehlgeschlagen", timeoutMs = 60_000)
        compose.settle()
        // Beide Knöpfe passen ins Hochformat (untereinander) und sind antippbar.
        compose.onNodeWithText("Erneut versuchen").assertIsDisplayed()
        compose.onNodeWithText("Daten ändern").assertIsDisplayed()
        compose.shootBothThemes("handy_11c_einrichtung_fehler")
        compose.onNodeWithText("Daten ändern").performClick()
        compose.waitForText("Speichern & laden")
    }

    @Test
    fun crashReportAfterCrash() {
        File(container.appContext.filesDir, "logs").mkdirs()
        File(container.appContext.filesDir, "logs/last_crash.txt").writeText("12.10. 10:00 · IllegalStateException: Beispiel\n\tat x.y")
        compose.setContent { PhoneHost(container) { CrashReportHost(onOpenLog = {}) } }
        compose.waitForText("unerwartet beendet")
        compose.settle()
        compose.shootBothThemes("handy_13_absturzbericht", wholeScreen = true)
    }

    @Test
    fun countryFilter() {
        compose.setContent { PhoneHost(container) { CountryFilterScreen(firstRun = true, onDone = {}) } }
        compose.waitForText("Welche Länder")
        compose.settle()
        compose.shootBothThemes("handy_12_laenderauswahl")
    }
}
