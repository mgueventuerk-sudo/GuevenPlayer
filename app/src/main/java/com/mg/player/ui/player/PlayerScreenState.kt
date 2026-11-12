package com.mg.player.ui.player

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.focus.FocusRequester
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.mg.player.data.recording.RecordingManager
import com.mg.player.domain.model.Category
import com.mg.player.domain.model.Channel
import com.mg.player.player.afr.AutoFrameRate
import kotlinx.coroutines.delay

/** Bedienzustand des Players (Overlay, Panels, Listen, Ziffern) und die Reaktionen auf Overlay-/„Mehr“-Aktionen. */
@Stable
internal class PlayerScreenState(
    private val vm: PlayerViewModel,
    private val overlayFocusState: MutableState<String?>,
    private val moreFocusState: MutableState<String?>,
    private val close: () -> Unit,
) {
    /** Seitenflächen getrennt gehalten: ohne ViewModel und damit einzeln prüfbar (siehe SidePanelsTest). */
    val panels = SidePanels()
    var overlayVisible by mutableStateOf(false)
    var panel by panels::panel
    var infoOpen by panels::infoOpen
    var interaction by mutableIntStateOf(0)
    var overlayFocusTick by mutableIntStateOf(0)

    // Fokus-Speicher: zuletzt gewähltes Element im Overlay und im „Mehr“-Panel (übersteht Aus-/Einblenden und Panels).
    var overlayFocus by overlayFocusState
    var moreFocus by moreFocusState
    var moreOpen by panels::moreOpen
    var panelFromMore by panels::panelFromMore
    var toast by mutableStateOf<String?>(null)
    var zapBanner by mutableStateOf(false)
    var digits by mutableStateOf("")

    // Schnell-Zapping-Liste (Live: Links-Taste oder Overlay-Button "Senderliste").
    var channelListOpen by mutableStateOf(false)

    // null = Senderliste der Kategorie, Category.RECENT = Verlauf (zuletzt gesehene Sender).
    var listCategory by mutableStateOf<String?>(null)
    val scrubber = Scrubber()
    val rootRequester = FocusRequester()

    // Nur wenn das Videobild selbst fokussiert ist, steuern die Pfeiltasten den Player.
    var rootFocused by mutableStateOf(false)
    val sideOpen: Boolean get() = panels.anyOpen

    /** Weder Steuerung noch Senderliste eingeblendet (Platz für Banner, Fokus aufs Video). */
    val bannerFree: Boolean get() = !overlayVisible && !channelListOpen

    fun backToMore() = panels.backToMore()

    /** Spulen per Links/Rechts bzw. Spultasten: Overlay mit fokussierter Zeitleiste, die Markierung wandert. */
    fun scrub(direction: Int, repeat: Boolean) {
        val e = vm.engine.value ?: return
        if (scrubber.press(direction, repeat, e.positionMs, e.durationMs) != null) {
            overlayFocus = TIMELINE_KEY
            if (overlayVisible) overlayFocusTick++ else overlayVisible = true
        }
    }

    fun openList(category: String?) {
        overlayVisible = false
        panels.closeAll()
        listCategory = category
        channelListOpen = vm.channelListSource() != null
    }

    fun addDigit(digit: Int) {
        if (digits.length < 4) digits += digit.toString()
    }

    /** Zurück: zuerst Listen und Panels, dann die Steuerung. */
    fun back() {
        when {
            channelListOpen -> channelListOpen = false

            infoOpen -> {
                infoOpen = false
                backToMore()
            }

            panel != null -> {
                panel = null
                backToMore()
            }

            moreOpen -> moreOpen = false

            else -> overlayVisible = false
        }
    }

    /**
     * Tippen neben ein geöffnetes Panel schließt es. Bis 3.19 blieb dabei „Mehr“ stehen (moreOpen wurde nicht
     * zurückgesetzt): Am Handy ließ sich das Panel dann nur noch mit der Zurück-Geste schließen, die Steuerung
     * blendete sich wegen des offenen Panels auch nicht mehr aus.
     */
    fun tap() {
        interaction++
        when {
            channelListOpen -> channelListOpen = false

            sideOpen -> panels.closeAll()

            else -> overlayVisible = !overlayVisible
        }
    }

    fun onOverlayAction(action: OverlayAction, liveChannel: Channel?, recordings: RecordingManager) {
        interaction++
        PANEL_FOR[action]?.let {
            panel = it
            return
        }
        if (playbackAction(action)) return
        when (action) {
            OverlayAction.MORE -> moreOpen = true
            OverlayAction.INFO -> infoOpen = true
            OverlayAction.STOP_RECORDING -> liveChannel?.let {
                recordings.stopChannel(it.id)
                toast = "Aufnahme gestoppt – zu finden unter „Aufnahmen“"
            }
            OverlayAction.CHANNEL_LIST -> openList(null)
            OverlayAction.HANDOFF -> {
                moreOpen = false
                vm.handoffToTv()
            }

            OverlayAction.HISTORY -> openList(Category.RECENT)
            OverlayAction.CLOSE -> close()
            else -> Unit
        }
    }

    /** Aktionen, die direkt die Wiedergabe steuern; false = keine solche Aktion. */
    private fun playbackAction(action: OverlayAction): Boolean {
        when (action) {
            OverlayAction.PLAY_PAUSE -> vm.togglePlayPause()
            OverlayAction.SEEK_BACK -> vm.seekBy(-10_000)
            OverlayAction.SEEK_FORWARD -> vm.seekBy(30_000)
            OverlayAction.FAVORITE -> vm.toggleFavorite()
            OverlayAction.NEXT_EPISODE -> vm.playNextNow()
            OverlayAction.MARK_INTRO -> vm.markIntroEnd()
            OverlayAction.PREVIOUS_CHANNEL -> vm.previousChannel()
            OverlayAction.RESTART -> vm.restartProgram()
            OverlayAction.GO_LIVE -> vm.goLive()
            OverlayAction.REWIND -> vm.rewindLive()
            else -> return false
        }
        return true
    }

    fun onMoreAction(action: OverlayAction) {
        interaction++
        when (action) {
            OverlayAction.FAVORITE -> vm.toggleFavorite()

            OverlayAction.ENGINE, OverlayAction.SOURCE, OverlayAction.SLEEP -> {
                moreOpen = false
                panelFromMore = true
                panel = OverlayPanel.valueOf(action.name)
            }

            OverlayAction.INFO -> {
                moreOpen = false
                panelFromMore = true
                infoOpen = true
            }

            OverlayAction.RESTART -> {
                moreOpen = false
                vm.restartProgram()
            }

            OverlayAction.REWIND -> {
                moreOpen = false
                vm.rewindLive()
            }

            OverlayAction.HISTORY -> openList(Category.RECENT)

            OverlayAction.HANDOFF -> {
                moreOpen = false
                vm.handoffToTv()
            }

            // Diese Aktionen bietet moreSections() im "Mehr"-Panel nicht an - absichtlich kein else, damit eine
            // neue Aktion dort (wie zuvor HANDOFF) bei Vergessen nicht lautlos ohne Wirkung bleibt, sondern nicht baut.
            OverlayAction.PLAY_PAUSE, OverlayAction.SEEK_BACK, OverlayAction.SEEK_FORWARD, OverlayAction.AUDIO,
            OverlayAction.SUBTITLES, OverlayAction.NEXT_EPISODE, OverlayAction.MARK_INTRO, OverlayAction.PREVIOUS_CHANNEL,
            OverlayAction.GO_LIVE, OverlayAction.RECORD, OverlayAction.STOP_RECORDING, OverlayAction.CHANNEL_LIST,
            OverlayAction.TRACKS, OverlayAction.MORE, OverlayAction.CLOSE,
            -> moreOpen = false
        }
    }
}

/** Overlay-Aktionen, die nur ein Seitenpanel öffnen. */
private val PANEL_FOR = mapOf(
    OverlayAction.TRACKS to OverlayPanel.TRACKS,
    OverlayAction.AUDIO to OverlayPanel.AUDIO,
    OverlayAction.SUBTITLES to OverlayPanel.SUBTITLES,
    OverlayAction.ENGINE to OverlayPanel.ENGINE,
    OverlayAction.SLEEP to OverlayPanel.SLEEP,
    OverlayAction.RECORD to OverlayPanel.RECORD,
    OverlayAction.SOURCE to OverlayPanel.SOURCE,
)

@Composable
internal fun rememberPlayerScreenState(vm: PlayerViewModel, close: () -> Unit): PlayerScreenState {
    val overlayFocus = rememberSaveable { mutableStateOf<String?>(null) }
    val moreFocus = rememberSaveable { mutableStateOf<String?>(null) }
    return remember(vm) { PlayerScreenState(vm, overlayFocus, moreFocus, close) }
}

/** Zeitgesteuertes Aus-/Einblenden, Fokus, Lebenszyklus und Zurück-Taste des Players. */
@Composable
internal fun PlayerScreenEffects(
    s: PlayerScreenState,
    vm: PlayerViewModel,
    ui: PlayerUiState,
    bingeHasFocus: Boolean,
    touch: Boolean,
    activity: android.app.Activity?,
    close: () -> Unit,
) {
    // Sendernummer per Zifferntasten: 1,5 s nach der letzten Ziffer wird umgeschaltet.
    LaunchedEffect(s.digits) {
        if (s.digits.isNotEmpty()) {
            delay(1_500)
            s.digits.toIntOrNull()?.let { vm.jumpToNumber(it) }
            s.digits = ""
        }
    }
    LaunchedEffect(Unit) {
        vm.events.collect { event ->
            when (event) {
                PlayerEvent.Close -> close()

                // Bildfrequenz umschalten nur am Fernseher – Handys regeln das selbst (und könnten sonst die Auflösung wechseln).
                is PlayerEvent.ApplyFrameRate -> if (!touch) activity?.let { AutoFrameRate.apply(it, event.fps) }

                is PlayerEvent.Toast -> s.toast = event.message
            }
        }
    }
    DisposableEffect(Unit) { onDispose { activity?.let { AutoFrameRate.reset(it) } } }
    // Im Bild-im-Bild-Fenster läuft das Video weiter.
    LifecycleEventEffect(Lifecycle.Event.ON_PAUSE) { if (!activity.inPictureInPicture()) vm.onLifecyclePause() }
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) {
        s.infoOpen = false
        vm.onLifecycleStop()
    }
    LifecycleEventEffect(Lifecycle.Event.ON_START) { vm.onLifecycleStart() }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { vm.onLifecycleResume() }
    LaunchedEffect(s.infoOpen) { vm.setStreamInfoVisible(s.infoOpen) }

    // Overlay blendet sich nach 5 Sekunden ohne Eingabe aus (nicht, solange ein Seitenpanel offen ist).
    LaunchedEffect(s.overlayVisible) { if (!s.overlayVisible) s.scrubber.cancel() }
    LaunchedEffect(s.overlayVisible, s.sideOpen, s.interaction, s.scrubber.active) {
        if (s.overlayVisible && !s.sideOpen && !s.scrubber.active) {
            delay(5_000)
            s.overlayVisible = false
        }
    }
    // Panel zu, Overlay noch offen: Fokus zurück aufs Overlay, sonst ginge der nächste Tastendruck ins Leere.
    LaunchedEffect(s.sideOpen) { if (!s.sideOpen && s.overlayVisible) s.overlayFocusTick++ }
    LaunchedEffect(s.toast) {
        if (s.toast != null) {
            delay(2_500)
            s.toast = null
        }
    }
    // Die Senderliste schließt sich nach 8 Sekunden ohne Eingabe.
    LaunchedEffect(s.channelListOpen, s.interaction) {
        if (s.channelListOpen) {
            delay(8_000)
            s.channelListOpen = false
        }
    }
    LaunchedEffect(s.overlayVisible, s.sideOpen, bingeHasFocus, s.channelListOpen) {
        if (s.bannerFree && !s.sideOpen && !bingeHasFocus) runCatching { s.rootRequester.requestFocus() }
    }
    LaunchedEffect(ui.channelNumber, ui.playable?.id) {
        if (ui.playable?.isLive == true) {
            s.zapBanner = true
            delay(3_500)
            s.zapBanner = false
        }
    }

    // Zurück schließt zuerst Listen und Panels. Die Steuerung selbst nur am Fernseher und nur, wenn sie wirklich zu
    // sehen ist (ohne geladenes Video zeigt sie nichts) – am Handy verlässt Zurück den Player direkt.
    val overlayShown = s.overlayVisible && ui.playable != null
    BackHandler(enabled = (overlayShown && !touch) || s.sideOpen || s.channelListOpen) { s.back() }
}
