package com.mg.player.screenshots

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import com.mg.player.core.di.AppContainer
import com.mg.player.integration.TestHost
import com.mg.player.ui.common.FormFactor
import com.mg.player.ui.common.LocalFormFactor
import com.mg.player.ui.common.LocalToast
import com.mg.player.ui.common.ToastHost
import com.mg.player.ui.navigation.Navigator
import com.mg.player.ui.navigation.Screen
import com.mg.player.ui.phone.PhoneHomeScreen
import com.mg.player.ui.phone.PhoneMovieDetail
import com.mg.player.ui.phone.PhoneSeriesDetail
import com.mg.player.ui.theme.PlayerTheme

/** Hochformat eines OnePlus 13 (≈ 412 × 915 dp, xxhdpi). */
const val PHONE_PORTRAIT = "w412dp-h915dp-port-xxhdpi"

/** Dasselbe Handy quer – so läuft der Player. */
const val PHONE_LANDSCAPE = "w915dp-h412dp-land-xxhdpi"

/** Wie am Handy: Touch-Oberfläche, umgeben vom App-Rahmen. */
@Composable
fun PhoneHost(container: AppContainer, navigator: Navigator = remember { Navigator(Screen.Home) }, content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalFormFactor provides FormFactor.TOUCH) {
        TestHost(container, navigator) { content() }
    }
}

/** Nur Theme und Touch-Oberfläche – für einzelne Bausteine. */
@Composable
fun PhoneHostPlain(content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalFormFactor provides FormFactor.TOUCH) { PlayerTheme { content() } }
}

/**
 * Kleine Ausgabe von AppRoot fürs Handy: zeigt den aktuellen Bildschirm des Navigators. Der Player selbst
 * wird nur als Text angezeigt (ohne echte Wiedergabe), damit der Test prüfen kann, was gestartet würde.
 */
@Composable
fun PhoneRoot(container: AppContainer, navigator: Navigator) {
    PhoneHost(container, navigator) {
        Box(Modifier.fillMaxSize()) {
            when (val screen = navigator.current.screen) {
                Screen.Home -> PhoneHomeScreen()
                is Screen.MovieDetail -> PhoneMovieDetail(screen.movieId)
                is Screen.SeriesDetail -> PhoneSeriesDetail(screen.seriesId)
                is Screen.Player -> Text("Player: ${screen.request}")
                else -> Text("Bildschirm: $screen")
            }
            ToastHost(LocalToast.current, Modifier.align(Alignment.BottomCenter).padding(bottom = 96.dp))
        }
    }
}
