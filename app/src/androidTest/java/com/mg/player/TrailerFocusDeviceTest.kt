package com.mg.player

import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.onRoot
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.mg.player.ui.detail.TrailerScreen
import com.mg.player.ui.navigation.LocalNavigator
import com.mg.player.ui.navigation.Navigator
import com.mg.player.ui.navigation.Screen
import com.mg.player.ui.theme.PlayerTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Erster echter Compose-UI-Instrumentierungstest dieses Projekts (androidTest, nicht Robolectric). Prüft denselben
 * Befund wie die vorherige, rein virtuelle Robolectric-Messung (inzwischen wieder entfernt): ist der
 * Schliessen-Knopf von TrailerScreen (Box().clickable(), kein TV-Material3-Button) per D-Pad auf echtem/emuliertem
 * Android erreichbar? Robolectrics Fokussystem kann vom echten InputManager-gesteuerten D-Pad-Verhalten abweichen
 * (dieselbe Grenze, die bei der NetworkOnMainThreadException dieser Session schon einmal real war) - dieser Test
 * laeuft deshalb ueber die Geraetetests-CI (device-tests.yml) auf echten KVM-Emulatoren, API 24/28/37.
 *
 * API 28 scheiterte dreimal mit drei verschiedenen Produktionscode-Fixen (API 24 bestand jedes Mal) - das CI-Skript
 * (.github/scripts/run-device-tests.sh) druckt nur den failure-Text der JUnit-XML, kein system-out. Die
 * Diagnose unten steht deshalb direkt in der Zusicherungs-Nachricht, damit der nächste Fehlschlag den
 * tatsächlichen Zustand zeigt statt nur "kein Fokus gefunden".
 */
@RunWith(AndroidJUnit4::class)
class TrailerFocusDeviceTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun closeButtonCanReceiveDpadFocus() {
        compose.setContent {
            PlayerTheme {
                CompositionLocalProvider(LocalNavigator provides remember { Navigator(Screen.Home) }) {
                    TrailerScreen("abc123XYZ_-")
                }
            }
        }
        compose.waitForIdle()

        val hasWindowFocusAtStart = compose.activity.hasWindowFocus()
        val directions = listOf(Key.DirectionDown, Key.DirectionRight, Key.DirectionUp, Key.DirectionLeft)
        var found = closeButtonIsFocused()
        repeat(2) {
            directions.forEach { key ->
                if (!found) {
                    compose.onRoot().performKeyInput { pressKey(key) }
                    compose.waitForIdle()
                    found = closeButtonIsFocused()
                }
            }
        }
        assertTrue(diagnosticMessage(hasWindowFocusAtStart), found)
    }

    private fun closeButtonIsFocused(): Boolean =
        compose.onAllNodes(hasContentDescription("Zurück") and isFocused()).fetchSemanticsNodes().isNotEmpty()

    /** Alles, was zur Ursachensuche fehlt, falls der obige Check (noch einmal) scheitert. */
    private fun diagnosticMessage(hasWindowFocusAtStart: Boolean): String {
        val closeNodes = compose.onAllNodes(hasContentDescription("Zurück")).fetchSemanticsNodes()
        val anyFocusedInTree = compose.onAllNodes(isFocused()).fetchSemanticsNodes()
        return buildString {
            append("Schliessen-Knopf erreicht in keiner getesteten Tastenfolge den Fokus. DIAGNOSE: ")
            append("hasWindowFocus (beim Start)=$hasWindowFocusAtStart; ")
            append("hasWindowFocus (jetzt)=${compose.activity.hasWindowFocus()}; ")
            append("Knoten mit contentDescription=Zurück: ${closeNodes.size}; ")
            closeNodes.firstOrNull()?.let { append("dessen config=${it.config}; ") }
            append("irgendein fokussierter Knoten im ganzen Baum: ${anyFocusedInTree.size}; ")
            anyFocusedInTree.firstOrNull()?.let { append("dessen config=${it.config}") }
        }
    }
}
