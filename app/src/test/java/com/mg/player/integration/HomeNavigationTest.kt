package com.mg.player.integration

import androidx.activity.ComponentActivity
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.mg.player.core.PlayerApp
import com.mg.player.domain.model.AccentColor
import com.mg.player.domain.model.ThemeMode
import com.mg.player.screenshots.Showcase
import com.mg.player.screenshots.ShowcaseServer
import com.mg.player.screenshots.TvHost
import com.mg.player.screenshots.clickable
import com.mg.player.screenshots.pressOk
import com.mg.player.screenshots.remote
import com.mg.player.screenshots.settle
import com.mg.player.screenshots.waitForText
import com.mg.player.ui.ApplyLook
import com.mg.player.ui.home.HomeScreen
import com.mg.player.ui.theme.AppColors
import com.mg.player.ui.theme.accentGradient
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Bedienung der Startseite mit der Fernbedienung: obere Leiste, Verweilen auf einem Tab, Zurück-Taste
 * (Inhalt -> Leiste -> Live-TV -> "Beenden?") und das Umschalten auf hell in den Einstellungen.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "w960dp-h540dp-land-television-xhdpi")
class HomeNavigationTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val server = ShowcaseServer()
    private val container get() = ApplicationProvider.getApplicationContext<PlayerApp>().container
    private var showcasePlaylistId = 0L

    @Before
    fun setUp() {
        runBlocking { container.settingsRepository.update { it.copy(hideGuide = false) } }
        showcasePlaylistId = Showcase.prepare(server, container).playlistId
    }

    @After
    fun tearDown() {
        server.shutdown()
        AppColors.apply(true, AccentColor.BLUE)
    }

    private fun back() {
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.settle(600)
    }

    private fun assertFocusedTab(label: String) {
        compose.onNode(isFocused() and hasContentDescription(label)).assertExists()
    }

    @Test
    fun remoteNavigationThroughTopBarAndBack() {
        // Eine gespeicherte Gruppe darf den Kaltstart nicht vom ersten Sender weglenken.
        runBlocking {
            container.settingsRepository.update { it.copy(lastChannelCategoryId = "$showcasePlaylistId:L:3") }
        }
        compose.setContent { TvHost(container) { HomeScreen() } }
        compose.waitForText("Quellen")
        compose.settle()
        // Kaltstart wählt und fokussiert Live TV, auch wenn zuvor eine konkrete Gruppe gespeichert war.
        compose.waitForText("ZDF")
        assertFocusedTab("Live TV")
        compose.onNode(isFocused() and hasText("Alle Sender")).assertDoesNotExist()

        // Startseite liegt direkt links neben Live TV; die bestehenden Bereiche bleiben erhalten.
        compose.remote(Key.DirectionRight)
        assertFocusedTab("Sport")
        compose.remote(Key.DirectionRight)
        assertFocusedTab("Filme")
        compose.remote(Key.DirectionRight)
        assertFocusedTab("Serien")
        compose.remote(Key.DirectionRight)
        assertFocusedTab("TV-Guide")
        compose.settle(800)
        compose.waitForText("Sendung auswählen")

        // Runter in den Guide, dann Zurück: Fokus geht in die Leiste (auf den gewählten Tab).
        compose.remote(Key.DirectionDown)
        back()
        assertFocusedTab("TV-Guide")

        // Zurück in der Leiste: erst Live-TV, dann die Rückfrage zum Beenden.
        back()
        compose.waitForText("Quellen")
        back()
        compose.waitForText("GuevenPlayer beenden?")
    }

    /** Runter aus der Leiste landet in Live-TV, Filme und Serien zuerst in den Gruppen – nie direkt in Sendern/Postern. */
    @Test
    fun downFromTopBarLandsOnGroups() {
        compose.setContent { TvHost(container) { HomeScreen() } }
        compose.waitForText("Quellen")
        compose.settle()
        assertFocusedTab("Live TV")
        compose.remote(Key.DirectionDown)
        compose.onNode(isFocused() and hasText("Alle Sender")).assertExists()

        compose.remote(Key.DirectionUp)
        repeat(2) { compose.remote(Key.DirectionRight) }
        assertFocusedTab("Filme")
        compose.settle(800)
        compose.waitForText("Alle Filme")
        compose.remote(Key.DirectionDown)
        compose.onNode(isFocused() and hasText("Alle Filme")).assertExists()

        // Hoch durch die Gruppen bis in die Leiste (je nach gewählter Gruppe ein oder mehrere Schritte).
        repeat(4) {
            if (compose.onAllNodes(isFocused() and hasContentDescription("Filme")).fetchSemanticsNodes().isEmpty()) compose.remote(Key.DirectionUp)
        }
        assertFocusedTab("Filme")
        compose.remote(Key.DirectionRight)
        assertFocusedTab("Serien")
        compose.settle(800)
        compose.waitForText("Alle Serien")
        compose.remote(Key.DirectionDown)
        compose.onNode(isFocused() and hasText("Alle Serien")).assertExists()
    }

    @Test
    fun movingRightFromGroupsStartsAtFirstLiveChannelAndPoster() {
        compose.setContent { TvHost(container) { HomeScreen() } }
        compose.waitForText("ZDF")
        compose.settle()

        compose.remote(Key.DirectionDown)
        compose.onNode(isFocused() and hasText("Alle Sender")).assertExists()
        compose.remote(Key.DirectionRight)
        compose.onAllNodes(isFocused(), useUnmergedTree = true).fetchSemanticsNodes().forEach {
            println("FOCUSED AFTER GROUP RIGHT: ${it.config}")
        }
        compose.onNode(isFocused() and hasText("DE: ZDF FHD")).assertExists()
        compose.remote(Key.DirectionDown, times = 6)
        back()
        compose.onNode(isFocused() and hasText("Alle Sender")).assertExists()
        compose.remote(Key.DirectionRight)
        compose.onNode(isFocused() and hasText("DE: ZDF FHD")).assertExists()

        compose.clickable("Filme").pressOk()
        compose.waitForText("Alle Filme")
        compose.settle()
        compose.remote(Key.DirectionRight)
        compose.onNode(isFocused() and hasText("DE - Nordlicht (2023) [4K]")).assertExists()
        compose.remote(Key.DirectionDown, times = 10)
        back()
        compose.onNode(isFocused() and hasText("Alle Filme")).assertExists()
        compose.remote(Key.DirectionRight)
        compose.onNode(isFocused() and hasText("DE - Nordlicht (2023) [4K]")).assertExists()
    }

    @Test
    fun enteringAnotherSettingsPageStartsAtItsFirstControl() {
        compose.setContent { TvHost(container) { HomeScreen() } }
        compose.waitForText("Quellen")
        compose.clickable("Einstellungen").pressOk()
        compose.waitForText("Zugänge")
        compose.settle()

        compose.onNode(hasText("Zugänge") and hasClickAction()).performSemanticsAction(SemanticsActions.RequestFocus)
        compose.onNode(isFocused() and hasText("Zugänge")).assertExists()
        compose.remote(Key.DirectionDown)
        compose.onNode(isFocused() and hasText("Wiedergabe")).assertExists()
        compose.remote(Key.DirectionRight)
        compose.onNode(isFocused() and hasText("Automatisch optimieren")).assertExists()
    }

    @Test
    fun leftFromLiveOpensHome() {
        compose.setContent { TvHost(container) { HomeScreen() } }
        compose.waitForText("Quellen")
        compose.waitForText("ZDF")
        assertFocusedTab("Live TV")

        compose.remote(Key.DirectionLeft)
        assertFocusedTab("Start")
        compose.waitForText("Für dich")
    }

    @Test
    fun okOnTabOpensSectionAndFocusesContent() {
        compose.setContent { TvHost(container) { HomeScreen() } }
        compose.waitForText("Quellen")
        compose.settle()
        compose.clickable("Einstellungen").pressOk()
        compose.waitForText("Darstellung")
        compose.settle(2_000)
        // Fokus liegt danach im Inhalt (nicht mehr in der Leiste).
        compose.onNode(isFocused() and hasContentDescription("Einstellungen")).assertDoesNotExist()
    }

    @Test
    fun choosingLightInSettingsSwitchesTheWholeApp() {
        compose.setContent {
            val settings by container.settingsRepository.settings.collectAsState(initial = null)
            settings?.let { ApplyLook(it.themeMode, it.accent) }
            TvHost(container) { HomeScreen() }
        }
        compose.waitForText("Quellen")
        compose.clickable("Einstellungen").pressOk()
        compose.waitForText("Darstellung")
        compose.onNode(hasText("Darstellung") and hasClickAction()).pressOk()
        compose.waitForText("Erscheinungsbild")
        compose.onNode(hasText("Hell") and hasClickAction()).pressOk()
        compose.settle()
        assertEquals(ThemeMode.LIGHT, runBlocking { container.settingsRepository.current().themeMode })
        assertFalse("Oberfläche ist jetzt hell", AppColors.isDark)

        compose.onNode(hasContentDescription("Blau gewählt"), useUnmergedTree = true).assertExists()
        compose.runOnUiThread { runBlocking { container.settingsRepository.update { it.copy(accent = AccentColor.ROSE) } } }
        compose.settle()
        assertEquals(accentGradient(AccentColor.ROSE).first(), AppColors.Accent)
    }
}
