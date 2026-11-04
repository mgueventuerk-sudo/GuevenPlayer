package com.mg.player.integration

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.mg.player.core.PlayerApp
import com.mg.player.core.security.PinHasher
import com.mg.player.screenshots.Showcase
import com.mg.player.screenshots.ShowcaseServer
import com.mg.player.screenshots.TvHost
import com.mg.player.screenshots.clickable
import com.mg.player.screenshots.pressOk
import com.mg.player.screenshots.settle
import com.mg.player.screenshots.waitForText
import com.mg.player.ui.common.LocalParentalGate
import com.mg.player.ui.common.ParentalGateHost
import com.mg.player.ui.home.HomeScreen
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** Mit PIN darf niemand ohne PIN eine Sicherung (alle Zugangsdaten) aufs Handy holen oder eine fremde einspielen. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "w960dp-h540dp-land-television-xhdpi")
class SettingsPinGateTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val server = ShowcaseServer()
    private val container get() = ApplicationProvider.getApplicationContext<PlayerApp>().container

    @Before
    fun setUp() {
        Showcase.prepare(server, container)
        runBlocking { container.settingsRepository.update { it.copy(parentalPinHash = PinHasher.hash("1234")) } }
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun backupExportAsksForPin() {
        compose.setContent {
            TvHost(container) {
                HomeScreen()
                ParentalGateHost(LocalParentalGate.current)
            }
        }
        compose.waitForText("Quellen")
        compose.clickable("Einstellungen").pressOk()
        compose.waitForText("System")
        compose.onNode(hasText("System") and hasClickAction()).pressOk()
        compose.waitForText("Sicherung erstellen")
        compose.onNode(hasText("Sicherung erstellen") and hasClickAction()).pressOk()
        compose.settle()
        compose.onNode(hasText("PIN eingeben")).assertExists()
    }
}
