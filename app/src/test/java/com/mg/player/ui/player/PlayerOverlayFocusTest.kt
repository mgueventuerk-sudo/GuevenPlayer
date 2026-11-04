package com.mg.player.ui.player

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.performSemanticsAction
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.mg.player.player.engine.EngineState
import com.mg.player.player.engine.PlaybackStatus
import com.mg.player.screenshots.TvHostPlain
import com.mg.player.screenshots.remote
import com.mg.player.screenshots.settle
import com.mg.player.ui.common.ActionButton
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** Fokus-Speicher und Spulen im Player-Overlay (Fernbedienung). */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "w960dp-h540dp-land-television-xhdpi")
class PlayerOverlayFocusTest {

    @get:Rule
    val compose = createComposeRule()

    private var visible by mutableStateOf(true)
    private var panelOpen by mutableStateOf(false)
    private var tick by mutableIntStateOf(0)
    private var focusKey by mutableStateOf<String?>(null)
    private val scrubber = Scrubber { 0L }
    private val seeks = mutableListOf<Long>()
    private val position = mutableStateOf(PlaybackPosition(positionMs = 600_000, durationMs = 2_820_000))

    private fun episode() = PlayerUiState(
        playable = Playable(PlayableKind.EPISODE, "e1", "Folge", "S01E01", "http://x.invalid/e1.mkv", 0, "ep:e1", seriesId = "s1"),
        engine = EngineState(status = PlaybackStatus.READY, isPlaying = true),
    )

    private fun show() {
        compose.setContent {
            TvHostPlain {
                Box(Modifier.fillMaxSize()) {
                    if (visible) {
                        PlayerOverlay(
                            episode(),
                            position,
                            hasPreviousChannel = false,
                            onAction = { if (it == OverlayAction.TRACKS) panelOpen = true },
                            focusTick = tick,
                            focusKey = focusKey,
                            onFocusKey = { focusKey = it },
                            scrubber = scrubber,
                            onScrubCommit = { seeks += it },
                        )
                    }
                    if (panelOpen) {
                        val requester = remember { FocusRequester() }
                        LaunchedEffect(Unit) { requester.requestFocus() }
                        ActionButton(
                            "Panel",
                            onClick = {
                                panelOpen = false
                                tick++
                            },
                            modifier = Modifier.align(Alignment.CenterEnd).focusRequester(requester),
                        )
                    }
                }
            }
        }
        compose.settle()
    }

    private fun focus(label: String) {
        compose.onNode(hasText(label) and hasClickAction()).performSemanticsAction(SemanticsActions.RequestFocus)
        compose.settle(300)
    }

    private fun assertFocused(label: String) {
        compose.onNode(isFocused()).assert(hasText(label))
    }

    @Test
    fun firstOpenStartsOnPlay() {
        show()
        compose.onNode(isFocused()).assert(hasContentDescription("Pause"))
    }

    @Test
    fun reopenedOverlayReturnsToLastButton() {
        show()
        focus("Stream-Info")
        visible = false
        compose.settle(300)
        visible = true
        compose.settle()
        assertFocused("Stream-Info")
    }

    @Test
    fun closingPanelReturnsToOpener() {
        show()
        focus("Ton & Untertitel")
        compose.onNode(isFocused()).performSemanticsAction(SemanticsActions.OnClick)
        compose.settle()
        assertFocused("Panel")
        compose.onNode(isFocused()).performSemanticsAction(SemanticsActions.OnClick)
        compose.settle()
        assertFocused("Ton & Untertitel")
    }

    @Test
    fun timelineMovesMarkerAndJumpsOnceOnOk() {
        show()
        compose.onNode(hasContentDescription("Zeitleiste")).performSemanticsAction(SemanticsActions.RequestFocus)
        compose.settle(300)
        compose.remote(Key.DirectionRight, times = 3)
        assertEquals(650_000L, scrubber.targetMs)
        assertTrue("vor OK schon gesprungen", seeks.isEmpty())
        compose.remote(Key.DirectionCenter)
        assertEquals(listOf(650_000L), seeks)
    }

    @Test
    fun backCancelsScrubbingWithoutJump() {
        show()
        compose.onNode(hasContentDescription("Zeitleiste")).performSemanticsAction(SemanticsActions.RequestFocus)
        compose.settle(300)
        compose.remote(Key.DirectionRight, times = 2)
        compose.remote(Key.Back)
        assertNull(scrubber.targetMs)
        assertTrue(seeks.isEmpty())
    }
}
