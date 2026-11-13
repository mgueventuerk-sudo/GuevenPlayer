package com.mg.player.ui.player

import android.graphics.Rect
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toAndroidRect
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.roundToIntRect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mg.player.data.handoff.HandoffRequest
import com.mg.player.domain.model.Category
import com.mg.player.domain.model.PlayRequest
import com.mg.player.domain.model.PlayerEngineType
import com.mg.player.ui.common.LocalAppContainer
import com.mg.player.ui.common.LocalToast
import com.mg.player.ui.common.appViewModel
import com.mg.player.ui.common.findActivity
import com.mg.player.ui.common.isTouch
import com.mg.player.ui.navigation.LocalNavigator
import com.mg.player.ui.theme.AppColors

@Composable
fun PlayerScreen(request: PlayRequest) {
    AppColors.VideoScope()
    val vm = appViewModel(key = request.toString()) { PlayerViewModel(it, request) }
    val navigator = LocalNavigator.current
    val activity = LocalContext.current.findActivity()
    val ui by vm.ui.collectAsStateWithLifecycle()
    // Bewusst als State weitergereicht: Nur die Zeitleiste liest die Position und zeichnet sich neu.
    val position = vm.position.collectAsStateWithLifecycle()
    val binge by vm.binge.collectAsStateWithLifecycle()
    val engine by vm.engine.collectAsStateWithLifecycle()
    val streamInfo by vm.streamInfo.collectAsStateWithLifecycle()
    val accountPrompt by vm.accounts.prompt.collectAsStateWithLifecycle()
    val recordings = LocalAppContainer.current.recordingManager
    val recordingChannels by recordings.activeChannels.collectAsStateWithLifecycle()
    val touch = isTouch
    var videoBounds by remember { mutableStateOf<Rect?>(null) }
    val inPip by rememberPictureInPicture(playing = ui.playable != null && ui.engineType != PlayerEngineType.EXTERNAL, videoBounds = videoBounds)
    val touchFeedback = remember { TouchFeedback() }
    val appToast = LocalToast.current
    // Sleep-Timer abgelaufen: anhalten und den Player schließen (am Fernseher greift danach der Bildschirmschoner).
    val sleep = rememberSleepTimer {
        vm.engine.value?.pause()
        appToast.show("Sleep-Timer abgelaufen – Wiedergabe beendet")
        navigator.pop()
    }
    val s = rememberPlayerScreenState(vm) { navigator.pop() }
    val bingeHasFocus = s.bannerFree && !s.sideOpen && (binge.skipIntroVisible || binge.nextVisible)
    PlayerScreenEffects(s, vm, ui, bingeHasFocus, touch, activity) { navigator.pop() }

    val isLive = ui.playable?.isLive == true
    val liveChannel = ui.playable?.channel?.takeIf { isLive }
    val isRecording = liveChannel != null && liveChannel.id in recordingChannels
    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .focusRequester(s.rootRequester)
            .onFocusChanged { s.rootFocused = it.isFocused }
            .focusable()
            .onKeyEvent { event ->
                s.interaction++
                if (!s.rootFocused) {
                    // Ein Button hat den Fokus: Pfeiltasten und OK an die Fokus-Navigation durchreichen.
                    return@onKeyEvent handleMediaKey(event, isLive, vm, s::scrub)
                }
                handlePlayerKey(
                    event,
                    isLive,
                    vm,
                    PlayerKeyActions(
                        showOverlay = { s.overlayVisible = true },
                        onDigit = s::addDigit,
                        showChannelList = { s.openList(null) },
                        showHistory = { s.openList(Category.RECENT) },
                        scrub = s::scrub,
                    ),
                )
            }
            .playerTouchControls(
                enabled = touch && !inPip,
                isLive = isLive,
                feedback = touchFeedback,
                onTap = s::tap,
                onAction = { action ->
                    s.interaction++
                    when (action) {
                        DoubleTapAction.SEEK_BACK -> vm.seekBy(-10_000)
                        DoubleTapAction.SEEK_FORWARD -> vm.seekBy(10_000)
                        DoubleTapAction.PREVIOUS_CHANNEL -> vm.zap(-1)
                        DoubleTapAction.NEXT_CHANNEL -> vm.zap(1)
                    }
                },
            ),
    ) {
        engine?.let { e ->
            EngineSurface(
                e,
                Modifier.fillMaxSize().then(
                    if (touch) {
                        Modifier.onGloballyPositioned {
                            videoBounds =
                                it.boundsInWindow().roundToIntRect().toAndroidRect()
                        }
                    } else {
                        Modifier
                    },
                ),
            )
        }
        PlaybackStatusMessage(ui, Modifier.align(Alignment.Center))
        // Kleines Fenster (Bild im Bild): nur das Video, keine Bedienelemente.
        if (inPip) return@Box
        if (touch) TouchFeedbackLayer(touchFeedback)

        if (isLive && s.zapBanner && s.bannerFree) {
            ZapBanner(ui, Modifier.align(Alignment.BottomStart).padding(40.dp))
        }
        if (isRecording && !s.overlayVisible) {
            RecBadge(Modifier.align(Alignment.TopEnd).padding(top = 28.dp, end = 32.dp))
        }
        val liveMatch by rememberLiveMatch(liveChannel?.id)
        GoalFlash(liveMatch, visible = !s.overlayVisible, Modifier.align(Alignment.TopStart).padding(top = 28.dp, start = 32.dp))
        if (s.digits.isNotEmpty()) DigitsBadge(s.digits, Modifier.align(Alignment.TopEnd).padding(40.dp))

        if (s.overlayVisible) {
            PlayerOverlay(
                ui = ui,
                position = position,
                hasPreviousChannel = vm.hasPreviousChannel,
                isRecording = isRecording,
                liveMatch = liveMatch,
                focusTick = s.overlayFocusTick,
                onSeekFraction = {
                    s.interaction++
                    vm.seekToFraction(it)
                },
                focusKey = s.overlayFocus,
                onFocusKey = { s.overlayFocus = it },
                scrubber = s.scrubber,
                onScrubCommit = {
                    s.interaction++
                    vm.seekTo(it)
                },
                onAction = { s.onOverlayAction(it, liveChannel, recordings) },
            )
        }

        if (s.channelListOpen) {
            vm.channelListSource()?.let { (playlistId, categoryId) ->
                val history = s.listCategory == Category.RECENT
                QuickZapPanel(
                    playlistId = playlistId,
                    categoryId = s.listCategory ?: categoryId,
                    // Im Verlauf steht der laufende Sender oben – der Fokus liegt auf dem davor gesehenen.
                    currentNumber = if (history) 2 else ui.channelNumber,
                    title = if (history) "Verlauf" else "Senderliste",
                    onSelect = { channel ->
                        s.channelListOpen = false
                        vm.zapTo(channel.id)
                    },
                    onEmpty = {
                        s.channelListOpen = false
                        s.toast = "In dieser Liste sind keine Sender"
                    },
                    modifier = Modifier.align(Alignment.CenterStart),
                )
            }
        }

        SkipIntroButton(
            state = binge,
            takeFocus = bingeHasFocus && binge.skipIntroVisible,
            onSkip = vm::skipIntro,
            modifier = Modifier.align(Alignment.BottomEnd).padding(end = 48.dp, bottom = if (s.overlayVisible) 200.dp else 56.dp),
        )
        NextEpisodeCard(
            // Bei eingeblendeter Steuerung ausgeblendet (dort gibt es "Nächste Folge" als Knopf) – sonst läge die
            // Karte über Play/Pause.
            state = if (s.overlayVisible) binge.copy(nextVisible = false) else binge,
            next = ui.nextEpisode,
            takeFocus = bingeHasFocus && binge.nextVisible,
            onPlayNow = vm::playNextNow,
            onCancel = vm::cancelNext,
            modifier = Modifier.align(Alignment.BottomEnd).padding(end = 48.dp, bottom = 48.dp),
        )

        if (s.moreOpen) {
            MorePanel(
                // Uebergabe nur am Handy anbieten: Von dort gibt man ab, nicht umgekehrt. Massgeblich ist der laufende
                // Inhalt, nicht der Start-Wunsch (nach dem Zappen aus einem Catch-up laeuft z. B. laengst Live).
                sections = moreSections(
                    ui,
                    sleep.buttonLabel,
                    handoffPossible = isTouch && ui.playable?.toPlayRequest(0L)?.let(HandoffRequest::isSupported) == true,
                ),
                focusKey = s.moreFocus,
                onFocusKey = { s.moreFocus = it },
                onAction = { s.onMoreAction(it.action) },
                onClose = { s.moreOpen = false },
                modifier = Modifier.align(Alignment.CenterEnd),
            )
        }

        PlayerPanelHost(
            vm = vm,
            ui = ui,
            panel = s.panel,
            sleep = sleep,
            position = position,
            liveChannel = liveChannel,
            onToast = { s.toast = it },
            onClose = {
                s.panel = null
                s.backToMore()
            },
            modifier = Modifier.align(Alignment.CenterEnd),
        )
        if (s.infoOpen) {
            StreamInfoPanel(
                streamInfo,
                onClose = {
                    s.infoOpen = false
                    s.backToMore()
                },
                modifier = Modifier.align(Alignment.CenterEnd),
            )
        }

        s.toast?.let { message -> ToastBanner(message, Modifier.align(Alignment.TopCenter).padding(top = 32.dp)) }
        // Zugang belegt / Stream ausgefallen: nachfragen (nie automatisch wechseln).
        accountPrompt?.let { AccountPromptDialog(it, vm.accounts::answer) }
    }
}
