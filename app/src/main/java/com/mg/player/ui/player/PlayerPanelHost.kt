package com.mg.player.ui.player

import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import com.mg.player.domain.model.Channel
import com.mg.player.ui.common.LocalAppContainer
import kotlinx.coroutines.launch

/** Seitenpanel des Players (Ton, Untertitel, Engine, Quelle, Aufnehmen, Sleep-Timer). */
@Composable
internal fun PlayerPanelHost(
    vm: PlayerViewModel,
    ui: PlayerUiState,
    panel: OverlayPanel?,
    sleep: SleepTimerState,
    /** Nur gelesen, solange das Panel offen ist – sonst würde der Player zweimal pro Sekunde neu komponiert. */
    position: State<PlaybackPosition>,
    liveChannel: Channel?,
    onToast: (String) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (panel == null) return
    val recordings = LocalAppContainer.current.recordingManager
    val scope = rememberCoroutineScope()
    val isLive = ui.playable?.isLive == true
    if (panel == OverlayPanel.TRACKS) {
        TracksPanel(
            audio = vm.panelOptions(OverlayPanel.AUDIO),
            subtitles = vm.panelOptions(OverlayPanel.SUBTITLES),
            onAudio = { vm.selectPanelOption(OverlayPanel.AUDIO, it) },
            onSubtitle = { vm.selectPanelOption(OverlayPanel.SUBTITLES, it) },
            onClose = onClose,
            modifier = modifier,
        )
        return
    }
    val options = remember(panel, ui.engine.tracksVersion, ui.engineType, sleep.endsAtMs) {
        if (panel == OverlayPanel.SLEEP) sleep.options(ui.nowProgram, position.value, isLive) else vm.panelOptions(panel)
    }
    OptionPanel(
        title = panel.title,
        options = options,
        onSelect = { option ->
            when (panel) {
                OverlayPanel.RECORD -> liveChannel?.let { channel ->
                    val now = ui.nowProgram
                    scope.launch { onToast(startRecording(recordings, channel, now, option)) }
                }

                OverlayPanel.SLEEP -> onToast(sleep.select(option, ui.nowProgram, position.value, isLive))

                else -> vm.selectPanelOption(panel, option)
            }
            onClose()
        },
        onClose = onClose,
        modifier = modifier,
    )
}
