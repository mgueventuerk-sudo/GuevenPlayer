package com.mg.player.integration

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import com.mg.player.core.di.AppContainer
import com.mg.player.ui.common.AmbientBackground
import com.mg.player.ui.common.BackdropState
import com.mg.player.ui.common.LocalAppContainer
import com.mg.player.ui.common.LocalBackdrop
import com.mg.player.ui.common.LocalParentalGate
import com.mg.player.ui.common.LocalToast
import com.mg.player.ui.common.ParentalGateState
import com.mg.player.ui.common.ToastState
import com.mg.player.ui.navigation.LocalNavigator
import com.mg.player.ui.navigation.Navigator
import com.mg.player.ui.navigation.Screen
import com.mg.player.ui.theme.PlayerTheme

/** Stellt einem einzelnen Screen alles bereit, was sonst AppRoot liefert (Container, Navigation, PIN, Hinweise). */
@Composable
fun TestHost(container: AppContainer, navigator: Navigator = remember { Navigator(Screen.Home) }, content: @Composable () -> Unit) {
    PlayerTheme {
        CompositionLocalProvider(
            LocalAppContainer provides container,
            LocalNavigator provides navigator,
            LocalParentalGate provides remember { ParentalGateState(container.parentalControl) },
            LocalToast provides remember { ToastState() },
        ) {
            // Wie in MainActivity: Aurora-Hintergrund mit dem Bild des fokussierten Titels.
            val backdrop = remember { BackdropState() }
            CompositionLocalProvider(LocalBackdrop provides backdrop) {
                AmbientBackground(artworkUrl = backdrop.url) { content() }
            }
        }
    }
}
