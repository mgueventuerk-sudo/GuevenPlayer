package com.mg.player.integration

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.mg.player.core.PlayerApp
import com.mg.player.domain.model.PlayRequest
import com.mg.player.screenshots.PHONE_PORTRAIT
import com.mg.player.screenshots.PhoneRoot
import com.mg.player.screenshots.Showcase
import com.mg.player.screenshots.ShowcaseData
import com.mg.player.screenshots.ShowcaseServer
import com.mg.player.screenshots.clickable
import com.mg.player.screenshots.settle
import com.mg.player.screenshots.waitForText
import com.mg.player.ui.navigation.Navigator
import com.mg.player.ui.navigation.Screen
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import kotlinx.coroutines.runBlocking

/**
 * Die Handy-Oberfläche so bedienen wie ein Mensch: echte Fingertipps (keine Fernbedienung), untere
 * Navigationsleiste, Sender antippen, Poster öffnen, gedrückt halten für Favoriten, Zurück-Knopf und -Geste.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = PHONE_PORTRAIT)
class PhoneNavigationTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val server = ShowcaseServer()
    private val container get() = ApplicationProvider.getApplicationContext<PlayerApp>().container
    private lateinit var data: ShowcaseData
    private val navigator = Navigator(Screen.Home)

    @Before
    fun setUp() {
        runBlocking { container.settingsRepository.update { it.copy(hideGuide = false) } }
        data = Showcase.prepare(server, container)
        compose.setContent { PhoneRoot(container, navigator) }
        compose.waitForText("ZDF")
        compose.settle()
    }

    @After
    fun tearDown() = server.shutdown()

    private fun tab(label: String) {
        compose.clickable(label).performClick()
        compose.settle()
    }

    private fun settingsPage(label: String) {
        compose.onAllNodesWithText(label).onFirst().performScrollTo().performClick()
        compose.settle()
    }

    private fun waitForDescription(description: String) {
        compose.waitUntil(15_000) { compose.onAllNodes(hasContentDescription(description)).fetchSemanticsNodes().isNotEmpty() }
    }

    @Test
    fun bottomBarSwitchesSections() {
        tab("Start")
        compose.waitForText("Weiterschauen · Filme")

        tab("Filme")
        waitForDescription("Nordlicht")
        compose.clickable("Filme").assertIsSelected()

        tab("Serien")
        waitForDescription("Orbit")

        tab("Guide")
        compose.waitForText("Jetzt")
        compose.waitForText("Das Erste")

        tab("Suche")
        compose.waitForText("Wonach suchst du?")

        tab("Live TV")
        compose.waitForText("ZDF")
        compose.clickable("Live TV").assertIsSelected()
    }

    @Test
    fun guideCanBeHiddenAndShownWithoutChangingEpg() {
        tab("Einstellungen")
        settingsPage("Darstellung")
        compose.waitForText("Guide ausblenden")
        compose.onNodeWithText("Guide ausblenden").performClick()
        compose.waitUntil(5_000) { runBlocking { container.settingsRepository.current().hideGuide } }
        tab("Live TV")
        compose.onNodeWithText("Guide").assertDoesNotExist()
        compose.waitForText("ZDF")
        assertTrue(runBlocking { container.epgRepository.hasPrograms(data.playlistId) })

        tab("Einstellungen")
        settingsPage("Darstellung")
        compose.onNodeWithText("Guide ausblenden").performClick()
        compose.waitUntil(5_000) { !runBlocking { container.settingsRepository.current().hideGuide } }
        tab("Guide")
        compose.waitForText("Jetzt")
        compose.waitForText("Das Erste")
    }

    @Test
    fun tappingAChannelStartsLivePlayback() {
        compose.onAllNodesWithText("ZDF").onFirst().performClick()
        compose.waitForText("Player: Live(")
        val request = (navigator.current.screen as Screen.Player).request
        assertTrue(request is PlayRequest.Live)
    }

    @Test
    fun longPressMakesAChannelAFavorite() {
        compose.onAllNodesWithText("ZDF").onFirst().performTouchInput { longClick() }
        compose.waitForText("ist jetzt ein Favorit")
        // Nichts gestartet – nur markiert.
        assertEquals(Screen.Home, navigator.current.screen)
    }

    @Test
    fun posterOpensDetailAndPlaysMovie() {
        tab("Filme")
        waitForDescription("Nordlicht")
        compose.clickable("Nordlicht").performClick()
        compose.waitForText("Abspielen")
        assertEquals(Screen.MovieDetail(data.movieId), navigator.current.screen)

        compose.onNodeWithText("Abspielen").performClick()
        compose.waitForText("Player: Movie(movieId=${data.movieId}")
    }

    @Test
    fun seriesDetailListsEpisodesAndBackButtonReturns() {
        tab("Serien")
        waitForDescription("Stadt der Lichter")
        compose.clickable("Stadt der Lichter").performClick()
        compose.waitForText("Staffel 1")
        compose.settle()

        compose.clickable("Zurück").performClick()
        compose.settle()
        assertEquals(Screen.Home, navigator.current.screen)
    }

    @Test
    fun backGestureLeadsBackToLiveTv() {
        tab("Filme")
        waitForDescription("Nordlicht")
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.settle()
        compose.clickable("Live TV").assertIsSelected()
    }
}
