package com.mg.player.screenshots

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.mg.player.core.PlayerApp
import com.mg.player.data.repository.Counterpart
import com.mg.player.ui.accounts.AccountSwitcherDialog
import com.mg.player.ui.common.ConfirmDialog
import com.mg.player.ui.common.PinDialog
import com.mg.player.ui.home.HomeScreen
import com.mg.player.ui.player.AccountChoice
import com.mg.player.ui.player.AccountPrompt
import com.mg.player.ui.player.AccountPromptDialog
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Dialoge (eigene Fenster) über der Startseite – dunkel und hell. */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w960dp-h540dp-land-television-xhdpi")
class DialogShotTest {

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

    @Test
    fun exitDialog() {
        compose.setContent {
            TvHost(container) {
                HomeScreen()
                ConfirmDialog("GuevenPlayer beenden?", "Beenden", "Abbrechen", onConfirm = {}, onDismiss = {})
            }
        }
        compose.waitForText("Beenden")
        compose.settle()
        compose.shootBothThemes("16_dialog_beenden", wholeScreen = true)
    }

    @Test
    fun pinDialog() {
        compose.setContent {
            TvHost(container) {
                HomeScreen()
                PinDialog("PIN eingeben", onPinEntered = {}, onDismiss = {}, subtitle = "Diese Kategorie ist gesperrt.")
            }
        }
        compose.waitForText("PIN eingeben")
        compose.settle()
        compose.shootBothThemes("17_dialog_pin", wholeScreen = true)
    }

    @Test
    fun accountSwitcher() {
        val playlists = runBlocking { container.playlistRepository.observePlaylists().first() }
        compose.setContent {
            TvHost(container) {
                HomeScreen()
                AccountSwitcherDialog(playlists, playlists.first().id, onSelect = {}, onAdd = {}, onDismiss = {})
            }
        }
        compose.waitForText("Zugang wechseln")
        // Der Zugang wird gerade genutzt: grünes "1/1" (laut Anbieter 1 von 1 Verbindungen belegt).
        val inUse = "In Nutzung – 1 von 1 Verbindungen belegt (voll)"
        compose.waitUntil(15_000) { compose.onAllNodes(hasContentDescription(inUse)).fetchSemanticsNodes().isNotEmpty() }
        compose.onAllNodesWithText("1/1").onFirst().assertIsDisplayed()
        compose.settle()
        compose.shootBothThemes("18_dialog_zugaenge", wholeScreen = true)
    }

    @Test
    fun busyAccountPrompt() {
        var choice: AccountChoice? = null
        val prompt = AccountPrompt(
            title = "Zugang belegt",
            text = "Über „Mein IPTV“ schaut gerade jemand (1/1). Wenn du startest, wird diese Wiedergabe beim Anbieter wahrscheinlich beendet.",
            alternatives = listOf(Counterpart(99, "Zweitzugang", "99:L:101", "ZDF HD")),
            continueLabel = "Trotzdem starten",
        )
        compose.setContent {
            TvHost(container) {
                HomeScreen()
                AccountPromptDialog(prompt) { choice = it }
            }
        }
        compose.waitForText("Zugang belegt")
        compose.waitForText("Über „Zweitzugang“ schauen")
        compose.settle()
        compose.shootBothThemes("19_dialog_zugang_belegt", wholeScreen = true)
        compose.onNodeWithText("Trotzdem starten").pressOk()
        compose.waitForIdle()
        assertEquals(AccountChoice.Continue, choice)
    }
}
