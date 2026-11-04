package com.mg.player.screenshots

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.mg.player.core.PlayerApp
import com.mg.player.domain.model.Category
import com.mg.player.domain.model.Channel
import com.mg.player.domain.model.EpgProgram
import com.mg.player.player.engine.EngineState
import com.mg.player.player.engine.PlaybackStatus
import com.mg.player.ui.player.DoubleTapAction
import com.mg.player.ui.player.DoubleTapHint
import com.mg.player.ui.player.InfoRow
import com.mg.player.ui.player.LevelIndicator
import com.mg.player.ui.player.Playable
import com.mg.player.ui.player.PlayableKind
import com.mg.player.ui.player.PlaybackPosition
import com.mg.player.ui.player.PlayerOverlay
import com.mg.player.ui.player.PlayerUiState
import com.mg.player.ui.player.StreamInfo
import com.mg.player.ui.player.StreamInfoPanel
import com.mg.player.ui.player.SwipeLevel
import com.mg.player.ui.theme.AppColors
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Player am Handy (quer, Vollbild): Touch-Steuerung mit Zurück-Knopf, ziehbarer Zeitleiste und Gesten-Anzeigen. */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = PHONE_LANDSCAPE)
class PhonePlayerShotTest {

    @get:Rule
    val compose = createComposeRule()

    private val server = ShowcaseServer()
    private val container get() = ApplicationProvider.getApplicationContext<PlayerApp>().container
    private val now = System.currentTimeMillis()

    @Before
    fun setUp() {
        Showcase.prepare(server, container)
    }

    @After
    fun tearDown() = server.shutdown()

    private val channel = Channel(
        id = "c1", name = "ZDF HD", logoUrl = ShowcaseServer.art("logo", 101, "ZDF"), streamUrl = "http://x.invalid/1.ts",
        categoryId = Category.ALL, epgKey = "zdf.de", nowTitle = null, nowStartMs = null, nowEndMs = null, catchupDays = 7, quality = "HD",
    )

    private fun liveState() = PlayerUiState(
        playable = Playable(PlayableKind.LIVE, "c1", "ZDF HD", null, "http://x.invalid/1.ts", 0, null, channel = channel),
        engine = EngineState(status = PlaybackStatus.READY, isPlaying = true),
        nowProgram = EpgProgram(
            "Terra X: Die Alpen",
            "Faszinierende Bilder und neue Erkenntnisse über die Entstehung der Alpen.",
            now - 25 * 60_000,
            now + 20 * 60_000,
        ),
        nextProgram = EpgProgram("heute journal", null, now + 20 * 60_000, now + 50 * 60_000),
        isFavorite = true,
        channelNumber = 1,
        channelCount = 17,
    )

    private fun movieState() = PlayerUiState(
        playable = Playable(PlayableKind.MOVIE, "m1", "Nordlicht", null, "http://x.invalid/m1.mkv", 0, "mv:m1"),
        engine = EngineState(status = PlaybackStatus.READY, isPlaying = true),
    )

    @Composable
    private fun VideoFrame(content: @Composable () -> Unit) {
        AppColors.VideoScope()
        val frame = remember { FakeArt.scene("Alpen Panorama", 1920, 1080).asImageBitmap() }
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            Image(frame, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            content()
        }
    }

    @Test
    fun liveOverlay() {
        compose.setContent {
            PhoneHost(container) {
                VideoFrame { PlayerOverlay(liveState(), remember { mutableStateOf(PlaybackPosition()) }, hasPreviousChannel = true, onAction = {}) }
            }
        }
        compose.waitForText("Terra X")
        compose.shootBothThemes("handy_20_player_live")
    }

    @Test
    fun movieOverlay() {
        compose.setContent {
            PhoneHost(container) {
                VideoFrame {
                    PlayerOverlay(
                        movieState(),
                        remember { mutableStateOf(PlaybackPosition(38 * 60_000L, 104 * 60_000L)) },
                        hasPreviousChannel = false,
                        onAction = {},
                        onSeekFraction = {},
                    )
                }
            }
        }
        compose.waitForText("Nordlicht")
        compose.shootBothThemes("handy_21_player_film")
    }

    @Test
    fun gestureFeedback() {
        compose.setContent {
            PhoneHost(container) {
                VideoFrame {
                    Box(Modifier.fillMaxSize()) {
                        LevelIndicator(SwipeLevel.VOLUME, 0.65f, Modifier.align(Alignment.TopCenter).padding(top = 28.dp))
                        DoubleTapHint(DoubleTapAction.SEEK_FORWARD, Modifier.align(Alignment.CenterEnd).padding(horizontal = 72.dp))
                    }
                }
            }
        }
        compose.waitForText("+10 s")
        compose.shootBothThemes("handy_22_player_gesten")
    }

    @Test
    fun streamInfoFitsLandscape() {
        // Viele Zeilen im nur ~410 dp hohen Querformat: "Schließen" muss sichtbar bleiben (Zeilen scrollen).
        val rows = listOf(
            InfoRow("VPN", "Aktiv · WireGuard", true), InfoRow("Auflösung", "1920 × 1080 · 50 fps"), InfoRow("Video", "H.264 · 8,2 Mbit/s"),
            InfoRow("Audio", "AAC · Stereo"), InfoRow("Download", "11,4 Mbit/s", true), InfoRow("Puffer", "12,3 s"),
            InfoRow("Verbindung", "WLAN · 5 GHz"), InfoRow("Engine", "ExoPlayer"), InfoRow("Verworfene Bilder", "0"), InfoRow("Quelle", "1 von 3"),
        )
        compose.setContent {
            PhoneHost(container) {
                VideoFrame {
                    Box(Modifier.fillMaxSize()) {
                        StreamInfoPanel(StreamInfo(rows), onClose = {}, modifier = Modifier.align(Alignment.CenterStart))
                    }
                }
            }
        }
        compose.waitForText("Stream-Info")
        compose.onNodeWithText("Schließen").assertIsDisplayed()
        compose.shootBothThemes("handy_player_streaminfo")
    }
}
