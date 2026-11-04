package com.mg.player.screenshots

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.mg.player.core.PlayerApp
import com.mg.player.core.vpn.VpnDropReason
import com.mg.player.core.vpn.VpnState
import com.mg.player.domain.binge.BingeState
import com.mg.player.domain.model.Category
import com.mg.player.domain.model.Channel
import com.mg.player.domain.model.EpgProgram
import com.mg.player.domain.model.Episode
import com.mg.player.player.engine.EngineState
import com.mg.player.player.engine.PlaybackStatus
import com.mg.player.player.engine.TrackOption
import com.mg.player.ui.player.InfoRow
import com.mg.player.ui.player.NextEpisodeCard
import com.mg.player.ui.player.OptionPanel
import com.mg.player.ui.player.Playable
import com.mg.player.ui.player.PlayableKind
import com.mg.player.ui.player.PlaybackPosition
import com.mg.player.ui.player.PlayerOverlay
import com.mg.player.ui.player.PlayerUiState
import com.mg.player.ui.player.QuickZapPanel
import com.mg.player.ui.player.StreamInfo
import com.mg.player.ui.player.StreamInfoPanel
import com.mg.player.ui.player.ZapBanner
import com.mg.player.ui.theme.AppColors
import com.mg.player.ui.vpn.LocalVpnState
import com.mg.player.ui.vpn.VpnBanner
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Player-Oberfläche über einem (nachgestellten) Videobild. Über Videos ist die Oberfläche immer dunkel –
 * die Aufnahmen "hell" belegen, dass das auch bei hellem Erscheinungsbild so bleibt.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w960dp-h540dp-land-television-xhdpi")
class PlayerShotTest {

    @get:Rule
    val compose = createComposeRule()

    private val server = ShowcaseServer()
    private val container get() = ApplicationProvider.getApplicationContext<PlayerApp>().container
    private lateinit var data: ShowcaseData
    private val now = System.currentTimeMillis()

    @Before
    fun setUp() {
        data = Showcase.prepare(server, container)
    }

    @After
    fun tearDown() = server.shutdown()

    private val channel = Channel(
        id = "c1", name = "ZDF HD", logoUrl = ShowcaseServer.art("logo", 101, "ZDF"), streamUrl = "http://x.invalid/1.ts",
        categoryId = Category.ALL, epgKey = "zdf.de", nowTitle = null, nowStartMs = null, nowEndMs = null, catchupDays = 7, quality = "HD",
    )

    private fun liveState() = PlayerUiState(
        playable = Playable(
            PlayableKind.LIVE, "c1", "ZDF HD", null, "http://x.invalid/1.ts", 0, null, channel = channel,
            variants = listOf(
                channel,
                channel.copy(quality = "FHD"),
            ),
        ),
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

    private val episode =
        Episode("e2", "s1", 1, 2, "Nachtfahrt", "http://x.invalid/e2.mkv", ShowcaseServer.art("still", 70112, "Stadt der Lichter 1×2"), null, 2_820)

    private fun episodeState() = PlayerUiState(
        playable = Playable(PlayableKind.EPISODE, "e1", "Stadt der Lichter", "S01E01 · Der Anfang", "http://x.invalid/e1.mkv", 0, "ep:e1", seriesId = "s1"),
        engine = EngineState(status = PlaybackStatus.READY, isPlaying = false),
        nextEpisode = episode,
    )

    /** Nachgestelltes Videobild als Hintergrund, darüber die Player-Oberfläche (immer dunkel). */
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
            TvHost(container) {
                VideoFrame {
                    PlayerOverlay(liveState(), remember { mutableStateOf(PlaybackPosition()) }, hasPreviousChannel = true, onAction = {}, isRecording = true)
                }
            }
        }
        compose.waitForText("Terra X")
        compose.shootBothThemes("12_player_live")
    }

    @Test
    fun episodeOverlay() {
        compose.setContent {
            TvHost(container) {
                VideoFrame {
                    PlayerOverlay(
                        episodeState(),
                        remember {
                            mutableStateOf(PlaybackPosition(31 * 60_000L, 47 * 60_000L))
                        },
                        hasPreviousChannel = false,
                        onAction = {},
                    )
                }
            }
        }
        compose.waitForText("Intro-Ende")
        compose.shootBothThemes("13a_player_serie")
    }

    /** VPN getrennt während der Wiedergabe: rotes Band oben über dem Vollbild, Schild im Overlay rot. */
    @Test
    fun liveOverlayVpnDisconnected() {
        val down = VpnState.Disconnected("Deutschland · Frankfurt", "DE", VpnDropReason.STALE, 0L, retrying = true)
        compose.setContent {
            TvHost(container) {
                CompositionLocalProvider(LocalVpnState provides down) {
                    VideoFrame {
                        PlayerOverlay(liveState(), remember { mutableStateOf(PlaybackPosition()) }, hasPreviousChannel = true, onAction = {})
                        Box(Modifier.fillMaxSize()) { VpnBanner(down, touch = false, Modifier.align(Alignment.TopCenter)) }
                    }
                }
            }
        }
        compose.waitForText("Verbindung ungeschützt")
        compose.shootBothThemes("12c_player_vpn_getrennt")
    }

    /** Abspann läuft: Karte "Nächste Folge" mit Countdown (ohne eingeblendete Steuerung). */
    @Test
    fun nextEpisodeCard() {
        compose.setContent {
            TvHost(container) {
                VideoFrame {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomEnd) {
                        NextEpisodeCard(
                            BingeState(nextVisible = true, nextSecondsLeft = 7, nextProgress = 0.3f),
                            episode,
                            takeFocus = true,
                            onPlayNow = {},
                            onCancel = {},
                            modifier = Modifier.padding(end = 48.dp, bottom = 48.dp),
                        )
                    }
                }
            }
        }
        compose.waitForText("Nächste Folge")
        compose.shootBothThemes("13b_player_naechste_folge")
    }

    @Test
    fun channelListAndZapBanner() {
        compose.setContent {
            TvHost(container) {
                VideoFrame {
                    Box(Modifier.fillMaxSize()) {
                        QuickZapPanel(data.playlistId, Category.ALL, currentNumber = 3, onSelect = {}, modifier = Modifier.align(Alignment.CenterStart))
                        ZapBanner(liveState(), Modifier.align(Alignment.BottomEnd).padding(40.dp))
                    }
                }
            }
        }
        compose.waitForText("Senderliste")
        compose.waitForText("Das Erste")
        compose.shootBothThemes("14_player_senderliste")
    }

    @Test
    fun sidePanels() {
        compose.setContent {
            TvHost(container) {
                VideoFrame {
                    Box(Modifier.fillMaxSize()) {
                        OptionPanel(
                            "Tonspur",
                            listOf(
                                TrackOption("1", "Deutsch · Dolby Digital 5.1", true),
                                TrackOption("2", "Deutsch · Stereo", false),
                                TrackOption("3", "Englisch · AAC", false),
                            ),
                            onSelect = {},
                            onClose = {},
                            modifier = Modifier.align(Alignment.CenterEnd),
                        )
                        StreamInfoPanel(
                            StreamInfo(
                                listOf(
                                    InfoRow("VPN", "Aktiv · WireGuard", true),
                                    InfoRow("Auflösung", "1920 × 1080 · 50 fps"),
                                    InfoRow("Video", "H.264 · 8,2 Mbit/s"),
                                    InfoRow("Download", "11,4 Mbit/s", true),
                                    InfoRow("Verbindung", "WLAN · 5 GHz"),
                                ),
                            ),
                            onClose = {},
                            modifier = Modifier.align(Alignment.CenterStart),
                        )
                    }
                }
            }
        }
        compose.waitForText("Stream-Info")
        compose.shootBothThemes("15_player_panels")
    }
}
