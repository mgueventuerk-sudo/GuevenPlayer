package com.mg.player.integration

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTextReplacement
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.mg.player.core.PlayerApp
import com.mg.player.screenshots.PhoneHost
import com.mg.player.screenshots.waitForText
import com.mg.player.ui.setup.SetupScreen
import org.junit.Test
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Manche Anbieter verschicken eine komplette "get.php?username=…&password=…"-URL - bisher musste man Server,
 * Benutzername und Passwort händisch daraus trennen. Fügt man so eine URL ins Server-Feld ein, zerlegt
 * SetupViewModel.updateServerField() sie jetzt sofort in die drei Felder (reine lokale String-Verarbeitung,
 * ConnectionMonitor.credentialsFromM3u()).
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class SetupPasteUrlTest {

    @get:Rule
    val compose = createComposeRule()

    private val container get() = ApplicationProvider.getApplicationContext<PlayerApp>().container

    @Test
    fun pastingAFullGetPhpUrlSplitsItIntoTheThreeFields() {
        compose.setContent { PhoneHost(container) { SetupScreen(onFinished = {}, firstSetup = true) } }
        compose.waitForText("Manuell eingeben")

        compose.onNodeWithContentDescription("Server-URL")
            .performTextReplacement("http://anbieter.example:8080/get.php?username=demo&password=geheim123&type=m3u_plus")
        compose.waitForIdle()

        compose.onNodeWithText("http://anbieter.example:8080").assertExists()
        compose.onNodeWithText("demo").assertExists()
        compose.onNodeWithText("geheim123").assertExists()
    }
}
