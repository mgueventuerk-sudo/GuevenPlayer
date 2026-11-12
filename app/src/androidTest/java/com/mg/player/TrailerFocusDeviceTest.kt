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
        assertTrue(
            "Schliessen-Knopf (contentDescription=Zurück) erreicht auf echtem/emuliertem Android in keiner " +
                "getesteten Tastenfolge den Fokus",
            found,
        )
    }

    private fun closeButtonIsFocused(): Boolean =
        compose.onAllNodes(hasContentDescription("Zurück") and isFocused()).fetchSemanticsNodes().isNotEmpty()
}
