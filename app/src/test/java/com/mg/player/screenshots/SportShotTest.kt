package com.mg.player.screenshots

import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.mg.player.core.PlayerApp
import com.mg.player.data.reminder.LaunchRequest
import com.mg.player.domain.model.PlayRequest
import com.mg.player.ui.home.HomeScreen
import com.mg.player.ui.navigation.Navigator
import com.mg.player.ui.navigation.Screen
import com.mg.player.ui.sport.LIST_TAG
import com.mg.player.ui.sport.SportAssignScreen
import com.mg.player.ui.sport.SportLeaguesScreen
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Sport-Sektion am Fernseher und am Handy: Tab erscheint erst mit Events, „Jetzt live“ startet den Sender,
 * ein kommendes Spiel bietet Erinnerung oder Sender an. Ablage: build/outputs/roborazzi/build/screenshots (Namen mit „sport“).
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w960dp-h540dp-land-television-xhdpi")
class SportShotTest {

    @get:Rule
    val compose = createComposeRule()

    private val server = ShowcaseServer()
    private val container get() = ApplicationProvider.getApplicationContext<PlayerApp>().container

    @Before
    fun setUp() {
        server.sportEnabled = true
        Showcase.prepare(server, container)
        LaunchRequest.consumed()
        runBlocking {
            container.reminders.items.value.forEach { container.reminders.remove(it.id) }
            container.sport.repository.refresh(force = true)
        }
    }

    @After
    fun tearDown() {
        LaunchRequest.consumed()
        server.shutdown()
    }

    @Test
    fun tvSportTabLiveAndUpcoming() {
        compose.setContent { TvHost(container) { HomeScreen() } }
        compose.waitForText("ZDF")
        // Der Tab erscheint, sobald es Spiele auf den eigenen Sendern gibt.
        compose.waitUntil(15_000) { container.sport.repository.visible.value }
        compose.waitUntil(15_000) { runCatching { compose.clickable("Sport").fetchSemanticsNode() }.isSuccess }
        compose.clickable("Sport").pressOk()
        compose.settle()
        compose.waitForText("Jetzt live")
        compose.waitForText("Bayern Munich – Borussia Dortmund")
        compose.waitForText("RB Leipzig – VfB Stuttgart")
        compose.shootBothThemes("02b_sport")

        // Laufendes Spiel: startet direkt den Sender (über die PIN-Prüfung in AppRoot).
        compose.onNodeWithText("Bayern Munich – Borussia Dortmund").pressOk()
        compose.settle(300)
        val request = LaunchRequest.requests.replayCache.last() as PlayRequest.Live
        assertTrue(request.channelId.endsWith(":L:301"))
        LaunchRequest.consumed()

        // Kommendes Spiel: Nachfrage mit Erinnerung.
        compose.onNodeWithText("RB Leipzig – VfB Stuttgart").pressOk()
        compose.waitForText("Erinnerung setzen")
        compose.waitForText("Beginnt in")
        compose.shootBothThemes("02c_sport_kommendes_spiel")
        compose.onNodeWithText("Erinnerung setzen").pressOk()
        compose.settle()
        assertEquals(listOf("RB Leipzig – VfB Stuttgart"), container.reminders.items.value.map { it.title })
    }

    /**
     * Mehrere Sportarten: je Abschnitt Unterüberschriften „Fußball“, „Kampfsport“. Spiele auf Sendern, die der Zugang nicht
     * hat, stehen unten mit „läuft auf … – nicht in deinem Zugang“ bzw. „OK: Sender suchen“; dazu die Korrekturliste.
     */
    @Test
    fun tvSportBySportWithHintsAndCorrections() {
        server.sportMixed = true
        runBlocking { container.sport.repository.refresh(force = true) }
        compose.setContent { TvHost(container) { HomeScreen() } }
        compose.waitForText("ZDF")
        compose.waitUntil(15_000) { runCatching { compose.clickable("Sport").fetchSemanticsNode() }.isSuccess }
        compose.clickable("Sport").pressOk()
        compose.settle()
        compose.waitForText("Jetzt live")
        compose.waitForText("Fußball")
        // Je nach Uhrzeit liegt der UFC-Kampf knapp unter dem sichtbaren Bereich.
        compose.onNodeWithTag(LIST_TAG).performScrollToNode(hasText("Moicano – Nolan"))
        compose.waitForText("Kampfsport")
        compose.shootBothThemes("02e_sport_nach_sportart")

        // Ganz nach unten: „Ohne Sender“ – erst Fußball, dann Kampfsport.
        compose.onNodeWithTag(LIST_TAG).performScrollToNode(hasText("läuft auf DAZN 1", substring = true))
        compose.settle()
        compose.waitForText("OK: Sender suchen")
        compose.waitForText("läuft auf DAZN 1 – nicht in deinem Zugang")
        compose.shootBothThemes("02f_sport_nicht_im_zugang")
    }

    @Test
    fun tvCorrectionList() {
        server.sportMixed = true
        runBlocking { container.sport.repository.refresh(force = true) }
        compose.setContent { TvHost(container) { SportAssignScreen() } }
        compose.waitForText("Sport: Sender korrigieren")
        compose.waitForText("Premier League")
        compose.waitForText("Spiele ohne Sender")
        compose.shootBothThemes("02g_sport_sender_korrigieren")
    }

    @Test
    fun tvLeagueSelection() {
        compose.setContent { TvHost(container) { SportLeaguesScreen() } }
        compose.waitForText("Champions League")
        compose.shootBothThemes("02d_sport_ligen")
        // Bundesliga ist voreingestellt: abwählen wird gespeichert, der Rest bleibt.
        compose.onNodeWithText("Bundesliga").pressOk()
        compose.settle()
        val followed = runBlocking { container.sport.repository.followed(container.settingsRepository.current()) }
        assertFalse("espn:soccer/ger.1" in followed)
        assertTrue("espn:soccer/uefa.champions" in followed)
    }

    @Test
    @Config(qualifiers = PHONE_PORTRAIT)
    fun phoneSportSection() {
        val navigator = Navigator(Screen.Home)
        compose.setContent { PhoneRoot(container, navigator) }
        compose.settle()
        // Am Handy: Pokal-Symbol oben in der Kopfzeile.
        compose.waitUntil(15_000) { runCatching { compose.clickable("Sport").fetchSemanticsNode() }.isSuccess }
        compose.clickable("Sport").performClick()
        compose.settle()
        compose.waitForText("Jetzt live")
        compose.waitForText("Werder Bremen – Hamburg SV")
        compose.shootBothThemes("handy_09_sport")
    }
}
