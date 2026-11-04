package com.mg.player.screenshots

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.requestFocus
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.mg.player.core.PlayerApp
import com.mg.player.domain.model.PlaylistType
import com.mg.player.domain.repository.PlaylistInput
import com.mg.player.ui.settings.SettingsPage
import com.mg.player.ui.settings.SettingsScreen
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Rundgang durch alle Bereiche und Unterseiten der Einstellungen am Fernseher, nur per Fernbedienung:
 * Bereiche mit „Runter“, Unterseiten mit OK, zurück mit der Zurück-Taste (Fokus kehrt auf die Zeile zurück).
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w960dp-h540dp-land-television-xhdpi")
class SettingsTourShotTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val server = ShowcaseServer()
    private val container get() = ApplicationProvider.getApplicationContext<PlayerApp>().container

    @Before
    fun setUp() {
        Showcase.prepare(server, container)
        runBlocking {
            container.playlistRepository.addPlaylist(
                PlaylistInput(name = "Zweitanbieter", type = PlaylistType.M3U, m3uUrl = "https://example.invalid/liste.m3u"),
            )
        }
    }

    @After
    fun tearDown() = server.shutdown()

    private fun open(text: String) {
        compose.onNode(hasText(text) and hasClickAction()).pressOk()
        compose.settle()
    }

    @Test
    fun tour() {
        compose.setContent { TvHost(container) { SettingsScreen() } }
        compose.waitForText("Zweitanbieter")
        compose.settle()
        compose.onAllNodesWithText(SettingsPage.entries.first().label)[0].requestFocus()
        compose.settle(500)
        SettingsPage.entries.forEachIndexed { i, page ->
            compose.waitForText(page.lead)
            compose.settle()
            compose.shootBothThemes("einstellungen_${i}_${page.name.lowercase()}")
            compose.remote(Key.DirectionDown)
        }
    }

    @Test
    fun accountPageAndBack() {
        compose.setContent { TvHost(container) { SettingsScreen() } }
        compose.waitForText("Zweitanbieter")
        compose.settle()
        open("Mein IPTV")
        compose.waitForText("Einstellungen für diesen Zugang".uppercase())
        compose.shootBothThemes("einstellungen_zugang")
        // Zurück-Taste der Fernbedienung (läuft über den Back-Dispatcher der Activity).
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.settle()
        compose.waitForText(SettingsPage.ACCOUNTS.lead)
        // Fokus steht wieder auf der Karte, die die Unterseite geöffnet hat.
        assertTrue(compose.onAllNodesWithText("Mein IPTV").fetchSemanticsNodes().isNotEmpty())
        open("„Alle Zugänge“")
        compose.waitForText("Zugänge und Rangfolge".uppercase())
        compose.shootBothThemes("einstellungen_alle_zugaenge")
    }
}
