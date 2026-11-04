package com.mg.player.ui.player

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.displayCutoutPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.mg.player.core.util.TimeUtils
import com.mg.player.core.vpn.VpnState
import com.mg.player.domain.model.MatchedEvent
import com.mg.player.ui.common.LiveBadge
import com.mg.player.ui.common.glass
import com.mg.player.ui.common.isTouch
import com.mg.player.ui.theme.AppColors
import com.mg.player.ui.theme.AppIcons
import com.mg.player.ui.vpn.LocalVpnState
import com.mg.player.ui.vpn.VpnBadge

enum class OverlayAction {
    PLAY_PAUSE,
    SEEK_BACK,
    SEEK_FORWARD,
    AUDIO,
    SUBTITLES,
    ENGINE,
    FAVORITE,
    NEXT_EPISODE,
    MARK_INTRO,
    PREVIOUS_CHANNEL,
    RESTART,
    REWIND,
    GO_LIVE,
    RECORD,
    STOP_RECORDING,
    CHANNEL_LIST,
    SOURCE,
    INFO,
    SLEEP,
    HISTORY,
    TRACKS,
    MORE,

    /** Nur am Handy: Zurück-Knopf oben links. */
    CLOSE,
}

/**
 * Modernes Player-Overlay: Titel oben, Play/Pause groß in der Bildmitte (bei Filmen/Serien mit −10 s / +30 s
 * daneben), unten EPG bzw. Zeitleiste und die Funktions-Buttons (Runter-Taste).
 */
@Composable
fun PlayerOverlay(
    ui: PlayerUiState,
    position: State<PlaybackPosition>,
    hasPreviousChannel: Boolean,
    onAction: (OverlayAction) -> Unit,
    modifier: Modifier = Modifier,
    isRecording: Boolean = false,
    /** Laufendes Spiel auf dem Sender: Spielstand oben neben der Uhr. */
    liveMatch: MatchedEvent? = null,
    /** Erhöht sich, wenn ein Seitenpanel schließt: dann den Fokus zurück aufs Overlay holen. */
    focusTick: Int = 0,
    /** Am Handy: Sprung auf der Zeitleiste (0…1). */
    onSeekFraction: ((Float) -> Unit)? = null,
    /** Zuletzt fokussiertes Element (Fokus-Speicher): darauf landet der Fokus beim Einblenden und nach Panels. */
    focusKey: String? = null,
    onFocusKey: (String) -> Unit = {},
    /** Am Fernseher: Spulen über die Zeitleiste (Markierung, OK springt einmal). */
    scrubber: Scrubber? = null,
    onScrubCommit: (Long) -> Unit = {},
) {
    val playable = ui.playable ?: return
    // Am Handy (quer, ~410 dp hoch) sind Ränder und Knöpfe kleiner; die Kamera-Aussparung bleibt frei.
    val touch = isTouch
    val side = if (touch) 20.dp else 56.dp
    val firstRequester = remember { FocusRequester() }
    val requesters = remember { HashMap<String, FocusRequester>() }
    val currentOnFocusKey by rememberUpdatedState(onFocusKey)
    fun Modifier.memo(key: String): Modifier =
        focusRequester(requesters.getOrPut(key) { FocusRequester() }).onFocusChanged { if (it.isFocused) currentOnFocusKey(key) }
    LaunchedEffect(focusTick) {
        val remembered = focusKey?.let(requesters::get)
        if (remembered == null || runCatching { remembered.requestFocus() }.isFailure) runCatching { firstRequester.requestFocus() }
    }
    // Der OK-Druck, der das Overlay geöffnet hat, darf nicht gleich "Pause" auslösen: Sein Loslassen und
    // Tasten-Wiederholungen (etwas längeres Drücken) werden geschluckt. Ein neuer Tastendruck geht sofort durch.
    var armed by remember { mutableStateOf(false) }
    // Weich einblenden und leicht hochgleiten (Apple TV); Ausblenden sofort, damit nichts nachläuft.
    val appear = remember { Animatable(0f) }
    LaunchedEffect(Unit) { appear.animateTo(1f, tween(APPEAR_MS, easing = FastOutSlowInEasing)) }
    val lift = with(LocalDensity.current) { APPEAR_LIFT.toPx() }

    Box(
        modifier
            .fillMaxSize()
            .graphicsLayer {
                alpha = appear.value
                translationY = (1f - appear.value) * lift
            }
            .onPreviewKeyEvent { event ->
                if (armed) return@onPreviewKeyEvent false
                val isOk = event.key == Key.DirectionCenter || event.key == Key.Enter || event.key == Key.NumPadEnter
                when {
                    !isOk -> false

                    event.type == KeyEventType.KeyUp -> {
                        armed = true
                        true
                    }

                    event.nativeKeyEvent.repeatCount > 0 -> true

                    else -> {
                        // Frischer Druck (das Loslassen des Öffnens ging verloren): durchlassen.
                        armed = true
                        false
                    }
                }
            },
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(170.dp)
                .background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.7f), Color.Transparent))),
        )
        Row(
            Modifier.then(if (touch) Modifier.displayCutoutPadding() else Modifier).padding(horizontal = side, vertical = if (touch) 14.dp else 34.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (touch) {
                RoundControl(AppIcons.Back, "Zurück", 44.dp, showLabel = false) { onAction(OverlayAction.CLOSE) }
                Spacer(Modifier.width(14.dp))
            }
            Column(Modifier.weight(1f)) {
                val number = ui.channelNumber
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (playable.isLive && number != null) {
                        Text(number.toString(), style = MaterialTheme.typography.headlineSmall, color = AppColors.TextMuted)
                        Spacer(Modifier.width(14.dp))
                    }
                    Text(
                        playable.title,
                        style = MaterialTheme.typography.headlineSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    if (playable.isLive && playable.kind != PlayableKind.CATCHUP) StatusDot("LIVE", AppColors.Live, Modifier.padding(start = 14.dp))
                    if (isRecording) StatusDot("REC", AppColors.Live, Modifier.padding(start = 12.dp))
                }
                if (!playable.isLive && !playable.subtitle.isNullOrBlank()) {
                    Text(playable.subtitle, style = MaterialTheme.typography.titleSmall, color = AppColors.TextSecondary, maxLines = 1)
                }
            }
            liveMatch?.let { LiveScoreChip(it, Modifier.padding(end = 16.dp)) }
            val vpn = LocalVpnState.current
            if (vpn !is VpnState.Off && vpn !is VpnState.Unsupported) VpnBadge(vpn, Modifier.padding(end = 16.dp))
            Text(TimeUtils.formatClock(System.currentTimeMillis()), style = MaterialTheme.typography.titleLarge, color = AppColors.TextPrimary)
        }

        Column(
            Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth()
                .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.85f))))
                .then(if (touch) Modifier.displayCutoutPadding() else Modifier)
                .padding(start = side, end = side, top = if (touch) 40.dp else 70.dp, bottom = if (touch) 12.dp else 34.dp),
        ) {
            if (playable.isLive) {
                LiveInfo(ui.nowProgram, ui.nextProgram)
            } else {
                SeekInfo(
                    position,
                    onSeek = onSeekFraction.takeIf { touch },
                    scrubber = scrubber.takeIf { !touch },
                    onScrubCommit = onScrubCommit,
                    modifier = Modifier.memo(TIMELINE_KEY),
                )
            }
            Spacer(Modifier.height(if (touch) 8.dp else 18.dp))
            Row(
                Modifier
                    .focusRestorer(FocusRequester.Default)
                    .horizontalScroll(rememberScrollState())
                    .padding(vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                // Feste Reihe (höchstens 5): jeder Knopf steht immer an derselben Stelle, der Rest liegt unter „Mehr“.
                if (playable.isLive) {
                    OverlayButton(AppIcons.ListIcon, "Senderliste", Modifier.memo("CHANNEL_LIST")) { onAction(OverlayAction.CHANNEL_LIST) }
                    OverlayButton(AppIcons.Swap, "Vorheriger Sender", Modifier.memo("PREVIOUS_CHANNEL"), enabled = hasPreviousChannel) {
                        onAction(OverlayAction.PREVIOUS_CHANNEL)
                    }
                    if (isRecording) {
                        OverlayButton(AppIcons.Stop, "Aufnahme stoppen", Modifier.memo("RECORD"), iconTint = AppColors.Live) {
                            onAction(OverlayAction.STOP_RECORDING)
                        }
                    } else {
                        OverlayButton(AppIcons.Record, "Aufnehmen", Modifier.memo("RECORD"), iconTint = AppColors.Live) { onAction(OverlayAction.RECORD) }
                    }
                    OverlayButton(AppIcons.Audio, "Ton & Untertitel", Modifier.memo("TRACKS")) { onAction(OverlayAction.TRACKS) }
                } else {
                    OverlayButton(AppIcons.Audio, "Ton & Untertitel", Modifier.memo("TRACKS")) { onAction(OverlayAction.TRACKS) }
                    if (playable.kind == PlayableKind.EPISODE) {
                        OverlayButton(AppIcons.SkipNext, "Nächste Folge", Modifier.memo("NEXT_EPISODE"), enabled = ui.nextEpisode != null) {
                            onAction(OverlayAction.NEXT_EPISODE)
                        }
                        OverlayButton(AppIcons.Sparkle, "Intro-Ende hier", Modifier.memo("MARK_INTRO")) { onAction(OverlayAction.MARK_INTRO) }
                    }
                    if (playable.kind == PlayableKind.CATCHUP) {
                        OverlayButton(AppIcons.GoLive, "Zurück zu Live", Modifier.memo("GO_LIVE")) { onAction(OverlayAction.GO_LIVE) }
                    }
                    OverlayButton(AppIcons.Info, "Stream-Info", Modifier.memo("INFO")) { onAction(OverlayAction.INFO) }
                }
                OverlayButton(AppIcons.Settings, "Mehr", Modifier.memo("MORE")) { onAction(OverlayAction.MORE) }
            }
        }

        // Steuerung in der Bildmitte – hier liegt beim Öffnen der Fokus.
        Row(
            Modifier.align(Alignment.Center),
            horizontalArrangement = Arrangement.spacedBy(if (touch) 56.dp else 40.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (!playable.isLive) {
                RoundControl(AppIcons.Back10, "−10 s", if (touch) 54.dp else 56.dp, Modifier.memo("SEEK_BACK"), showLabel = !touch) {
                    onAction(OverlayAction.SEEK_BACK)
                }
            }
            RoundControl(
                if (ui.engine.isPlaying) AppIcons.Pause else AppIcons.Play,
                if (ui.engine.isPlaying) "Pause" else "Abspielen",
                if (touch) 76.dp else 80.dp,
                Modifier.focusRequester(firstRequester).memo("PLAY"),
                showLabel = !touch,
            ) { onAction(OverlayAction.PLAY_PAUSE) }
            if (!playable.isLive) {
                RoundControl(AppIcons.Forward30, "+30 s", if (touch) 54.dp else 56.dp, Modifier.memo("SEEK_FORWARD"), showLabel = !touch) {
                    onAction(OverlayAction.SEEK_FORWARD)
                }
            }
        }
    }
}

private const val APPEAR_MS = 280
private val APPEAR_LIFT = 18.dp
