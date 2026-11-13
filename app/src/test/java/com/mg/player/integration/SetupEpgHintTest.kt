package com.mg.player.integration

import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.mg.player.core.PlayerApp
import com.mg.player.screenshots.PhoneHost
import com.mg.player.screenshots.waitForText
import com.mg.player.ui.setup.SetupScreen
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Xtream-Zugänge bringen ihr EPG über xmltv.php automatisch mit (EpgRepositoryImpl) - das EPG-URL-Feld im Setup
 * zeigte das bisher nicht an und wirkte wie bei M3U, wo eine eigene EPG-Adresse oft wirklich nötig ist. Nutzer, die
 * das nicht wussten, trugen dort eine fremde, oft schlechtere EPG-URL ein.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class SetupEpgHintTest {

    @get:Rule
    val compose = createComposeRule()

    private val container get() = ApplicationProvider.getApplicationContext<PlayerApp>().container
    private val hint = "wird bei Xtream automatisch geladen"

    @Test
    fun epgHintShowsForXtreamAndNotForM3u() {
        compose.setContent { PhoneHost(container) { SetupScreen(onFinished = {}, firstSetup = true) } }
        compose.waitForText("Manuell eingeben")

        // Vorgabe ist Xtream (SetupForm()) - der Hinweis muss ohne weiteres Zutun da sein.
        assertEquals(1, compose.onAllNodes(hasText(hint)).fetchSemanticsNodes().size)

        compose.onNodeWithText("M3U-Link").performClick()
        compose.waitForIdle()
        assertEquals(0, compose.onAllNodes(hasText(hint)).fetchSemanticsNodes().size)
    }
}
