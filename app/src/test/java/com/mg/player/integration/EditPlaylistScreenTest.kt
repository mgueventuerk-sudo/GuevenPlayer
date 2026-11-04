package com.mg.player.integration

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextReplacement
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.mg.player.core.PlayerApp
import com.mg.player.domain.model.PlaylistType
import com.mg.player.domain.repository.PlaylistInput
import com.mg.player.ui.setup.EditPlaylistScreen
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** Bildschirm „Zugang bearbeiten“: vorausgefüllt, Passwort nie sichtbar, Name ändern und speichern. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "w960dp-h540dp-xhdpi")
class EditPlaylistScreenTest {

    @get:Rule
    val compose = createComposeRule()

    private val container get() = ApplicationProvider.getApplicationContext<PlayerApp>().container

    @Test
    fun prefilledWithoutPasswordAndSavesName() {
        val id = runBlocking {
            container.playlistRepository.addPlaylist(PlaylistInput("Anbieter A", PlaylistType.XTREAM, "https://a.example", "max", "streng-geheim"))
        }
        var done = false
        compose.setContent { TestHost(container) { EditPlaylistScreen(id, onDone = { done = true }) } }
        compose.waitUntil(5_000) { compose.onAllNodes(hasText("Anbieter A")).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("max").assertExists()
        assertEquals(0, compose.onAllNodes(hasText("streng-geheim", substring = true)).fetchSemanticsNodes().size)

        compose.onNode(hasSetTextAction() and hasText("Anbieter A")).performTextReplacement("Anbieter Neu")
        compose.onNodeWithText("Prüfen & speichern").performSemanticsAction(SemanticsActions.OnClick)
        compose.mainClock.advanceTimeBy(2_000)
        compose.waitForIdle()
        // Hauptschleife und Bild-Uhr mitlaufen lassen: Nach einem anderen Robolectric-Test im selben Prozess blieb die
        // Neuzusammensetzung sonst gelegentlich stehen – gespeichert war, aber „onDone“ kam nie (in 3.35 aufgefallen).
        compose.waitUntil(20_000) {
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
            compose.mainClock.advanceTimeByFrame()
            done
        }
        assertEquals("Anbieter Neu", runBlocking { container.playlistRepository.getPlaylist(id)!!.name })
        assertEquals("streng-geheim", runBlocking { container.playlistRepository.getPlaylist(id)!!.password })
    }
}
