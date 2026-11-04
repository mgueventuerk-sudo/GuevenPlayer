package com.mg.player.ui.player

import android.content.Context
import android.media.AudioManager
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.click
import androidx.compose.ui.test.doubleClick
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.swipeRight
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.mg.player.screenshots.PHONE_LANDSCAPE
import com.mg.player.screenshots.PhoneHostPlain
import com.mg.player.screenshots.settle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** Gesten im Player am Handy – mit echten Fingerbewegungen auf einer Testfläche. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = PHONE_LANDSCAPE)
class TouchControlsTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val taps = mutableListOf<String>()
    private val feedback = TouchFeedback()

    private fun showSurface(isLive: Boolean) {
        compose.setContent {
            PhoneHostPlain {
                Box(
                    Modifier
                        .fillMaxSize()
                        .testTag("video")
                        .playerTouchControls(
                            enabled = true,
                            isLive = isLive,
                            feedback = feedback,
                            onTap = { taps += "tap" },
                            onAction = { taps += it.name },
                        ),
                ) { TouchFeedbackLayer(feedback) }
            }
        }
        compose.settle(300)
    }

    @Test
    fun singleTapTogglesOverlay() {
        showSurface(isLive = false)
        compose.onNodeWithTag("video").performClick()
        compose.settle(600)
        assertEquals(listOf("tap"), taps)
    }

    @Test
    fun doubleTapLeftAndRightSeeksInMovies() {
        showSurface(isLive = false)
        compose.onNodeWithTag("video").performTouchInput { doubleClick(Offset(width * 0.2f, centerY)) }
        compose.settle(200)
        // Kurz eingeblendeter Hinweis.
        compose.onNodeWithText("−10 s").assertExists()
        compose.onNodeWithTag("video").performTouchInput { doubleClick(Offset(width * 0.8f, centerY)) }
        compose.settle(1_000)
        assertEquals(listOf("SEEK_BACK", "SEEK_FORWARD"), taps)
        assertNull("Hinweis verschwindet wieder", feedback.hint)
    }

    @Test
    fun doubleTapZapsInLiveTv() {
        showSurface(isLive = true)
        compose.onNodeWithTag("video").performTouchInput { doubleClick(Offset(width * 0.8f, centerY)) }
        compose.settle(300)
        compose.onNodeWithTag("video").performTouchInput { doubleClick(Offset(width * 0.2f, centerY)) }
        compose.settle(300)
        assertEquals(listOf("NEXT_CHANNEL", "PREVIOUS_CHANNEL"), taps)
    }

    @Test
    fun swipeOnTheRightChangesVolume() {
        val audio = ApplicationProvider.getApplicationContext<Context>().getSystemService(AudioManager::class.java)
        val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        audio.setStreamVolume(AudioManager.STREAM_MUSIC, max / 2, 0)
        showSurface(isLive = false)

        compose.onNodeWithTag("video").performTouchInput {
            down(Offset(width * 0.85f, height * 0.8f))
            repeat(10) { moveBy(Offset(0f, -height * 0.06f)) }
            up()
        }
        compose.settle(300)
        assertTrue("lauter als vorher", audio.getStreamVolume(AudioManager.STREAM_MUSIC) > max / 2)
        assertNull("Balken nach dem Loslassen weg", feedback.swipe)
        assertTrue(taps.isEmpty())
    }

    @Test
    fun swipeOnTheLeftChangesBrightnessAndShowsIndicator() {
        showSurface(isLive = false)
        compose.onNodeWithTag("video").performTouchInput {
            down(Offset(width * 0.15f, height * 0.2f))
            repeat(6) { moveBy(Offset(0f, height * 0.06f)) }
        }
        compose.settle(100)
        assertEquals(SwipeLevel.BRIGHTNESS, feedback.swipe)
        compose.onNodeWithContentDescription("Helligkeit", substring = true).assertExists()
        compose.onNodeWithTag("video").performTouchInput { up() }
        compose.settle(100)
        val brightness = compose.activity.window.attributes.screenBrightness
        assertTrue("dunkler als die System-Helligkeit (0,5): $brightness", brightness in 0f..0.49f)
        assertNull(feedback.swipe)
    }

    @Test
    fun seekBarFollowsTapAndDrag() {
        val seeks = mutableListOf<Float>()
        compose.setContent {
            PhoneHostPlain {
                val position = remember { mutableStateOf(PlaybackPosition(10 * 60_000L, 100 * 60_000L)) }
                Column(Modifier.fillMaxWidth()) { SeekInfo(position, onSeek = { seeks += it }) }
            }
        }
        compose.settle(300)
        val bar = compose.onNodeWithContentDescription("Zeitleiste")
        bar.performTouchInput { click(Offset(width * 0.5f, centerY)) }
        compose.settle(300)
        bar.performTouchInput { swipeRight(startX = width * 0.1f, endX = width * 0.9f) }
        compose.settle(300)
        bar.performTouchInput { swipeLeft(startX = width * 0.9f, endX = 0f) }
        compose.settle(300)
        assertEquals(3, seeks.size)
        assertEquals(0.5f, seeks[0], 0.02f)
        assertEquals(0.9f, seeks[1], 0.03f)
        assertEquals(0f, seeks[2], 0.02f)
    }

    @Test
    fun disabledOnTvDoesNothing() {
        compose.setContent {
            PhoneHostPlain {
                Box(
                    Modifier.fillMaxSize().testTag("video").playerTouchControls(
                        enabled = false,
                        isLive = false,
                        feedback = feedback,
                        onTap = { taps += "tap" },
                        onAction = { taps += it.name },
                    ),
                )
            }
        }
        compose.onNodeWithTag("video").performTouchInput { swipeDown() }
        compose.onNodeWithTag("video").performClick()
        compose.settle(600)
        assertTrue(taps.isEmpty())
    }
}
