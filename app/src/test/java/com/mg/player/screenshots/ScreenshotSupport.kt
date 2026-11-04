package com.mg.player.screenshots

import android.os.Looper
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.ComposeTimeoutException
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.pressKey
import com.github.takahirom.roborazzi.ExperimentalRoborazziApi
import com.github.takahirom.roborazzi.captureRoboImage
import com.github.takahirom.roborazzi.captureScreenRoboImage
import com.mg.player.core.di.AppContainer
import com.mg.player.domain.model.AccentColor
import com.mg.player.domain.model.ContentType
import com.mg.player.domain.model.PlaylistType
import com.mg.player.domain.repository.PlaylistInput
import com.mg.player.integration.TestHost
import com.mg.player.ui.theme.AppColors
import com.mg.player.ui.theme.PlayerTheme
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.robolectric.Shadows.shadowOf

/** Fertig importierter Vorführ-Zugang. */
data class ShowcaseData(val playlistId: Long, val movieId: String, val seriesId: String)

object Showcase {
    /** Richtet den Zugang so ein wie die App: Import, aktiver Zugang, EPG, Suchindex. */
    fun prepare(server: ShowcaseServer, container: AppContainer): ShowcaseData = runBlocking {
        FakeArt.install(container.appContext)
        val base = server.start()
        val id = container.playlistRepository.addPlaylist(
            PlaylistInput(name = "Mein IPTV", type = PlaylistType.XTREAM, serverUrl = base, username = "demo", password = "demo", allowInsecureHttp = true),
        )
        container.playlistRepository.refreshContent(id) {}
        container.settingsRepository.update { it.copy(activePlaylistId = id) }
        val playlist = container.playlistRepository.observePlaylists().first().first { it.id == id }
        container.epgRepository.refresh(playlist) {}
        val movie = container.searchRepository.search(id, "Nordlicht").first { it.type == ContentType.MOVIE }
        val series = container.searchRepository.search(id, "Stadt der Lichter").first { it.type == ContentType.SERIES }
        ShowcaseData(id, movie.id, series.id)
    }
}

/** Wie am Fernseher: Bedienung per Steuerkreuz (Fokus sichtbar), umgeben vom App-Rahmen. */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun TvHost(container: AppContainer, content: @Composable () -> Unit) {
    val inputMode = LocalInputModeManager.current
    SideEffect { inputMode.requestInputMode(InputMode.Keyboard) }
    TestHost(container) { content() }
}

/** Nur Theme und Steuerkreuz-Bedienung – für einzelne Bausteine ohne App-Container. */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun TvHostPlain(content: @Composable () -> Unit) {
    val inputMode = LocalInputModeManager.current
    SideEffect { inputMode.requestInputMode(InputMode.Keyboard) }
    PlayerTheme { content() }
}

/** Tastendruck ohne Wartezeit danach (z. B. schnelles Durchblättern). */
@OptIn(ExperimentalTestApi::class)
fun ComposeContentTestRule.onRootKey(key: Key) {
    onRoot().performKeyInput { pressKey(key) }
    mainClock.advanceTimeBy(50)
}

/** Klickbare Fläche mit dieser Beschreibung (Tabs, Icon-Knöpfe). */
fun ComposeContentTestRule.clickable(description: String): SemanticsNodeInteraction =
    onNode(hasClickAction() and hasContentDescription(description), useUnmergedTree = false)

/** Taste auf der Fernbedienung (Steuerkreuz) – geht an das fokussierte Element. */
@OptIn(ExperimentalTestApi::class)
fun ComposeContentTestRule.remote(key: Key, times: Int = 1) {
    repeat(times) {
        onRoot().performKeyInput { pressKey(key) }
        settle(300)
    }
}

/** OK auf der Fernbedienung: TV-Flächen reagieren auf OK, nicht auf Berührung – daher die Klick-Aktion direkt auslösen. */
fun SemanticsNodeInteraction.pressOk(): SemanticsNodeInteraction = performSemanticsAction(SemanticsActions.OnClick)

fun ComposeTestRule.waitForText(text: String, timeoutMs: Long = 15_000) {
    try {
        waitUntil(timeoutMs) { onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty() }
    } catch (e: ComposeTimeoutException) {
        // Zur Fehlersuche festhalten, was stattdessen zu sehen war.
        onRoot().captureRoboImage("build/screenshots/fehler_${text.filter { it.isLetterOrDigit() }}.png")
        throw e
    }
}

/** Lässt Paging, Bilder und Animationen zur Ruhe kommen. */
fun ComposeTestRule.settle(ms: Long = 1_500) {
    repeat((ms / 100).toInt()) {
        mainClock.advanceTimeBy(100)
        shadowOf(Looper.getMainLooper()).idle()
        Thread.sleep(10)
    }
    waitForIdle()
}

/**
 * Nimmt denselben Bildschirm im dunklen und im hellen Erscheinungsbild auf
 * (build/outputs/roborazzi/build/screenshots/<name>_dunkel.png bzw. _hell.png).
 */
@OptIn(ExperimentalRoborazziApi::class)
fun ComposeContentTestRule.shootBothThemes(name: String, wholeScreen: Boolean = false) {
    for (dark in listOf(true, false)) {
        AppColors.apply(dark, AccentColor.BLUE)
        settle(800)
        val path = "build/screenshots/${name}_${if (dark) "dunkel" else "hell"}.png"
        if (wholeScreen) captureScreenRoboImage(path) else onRoot().captureRoboImage(path)
    }
    AppColors.apply(true, AccentColor.BLUE)
}
