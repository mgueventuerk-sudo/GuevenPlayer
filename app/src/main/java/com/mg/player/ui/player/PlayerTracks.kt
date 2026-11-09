package com.mg.player.ui.player

import com.mg.player.core.util.TimeUtils
import com.mg.player.domain.model.PlayerEngineType
import com.mg.player.player.engine.TrackOption

enum class OverlayPanel(val title: String) {
    AUDIO("Tonspur"),
    SUBTITLES("Untertitel"),
    TRACKS("Ton & Untertitel"),
    ENGINE("Player-Engine"),
    RECORD("Aufnehmen"),
    SOURCE("Quelle"),
    SLEEP("Sleep-Timer"),
}

/** Dauer-Optionen beim Aufnehmen. Die ID ist die Dauer in Minuten bzw. [RECORD_UNTIL_END]. */
const val RECORD_UNTIL_END = "end"
private val recordDurations = listOf(30 to "30 Minuten", 60 to "1 Stunde", 120 to "2 Stunden", 240 to "Bis ich stoppe (höchstens 4 Std.)")

private const val SUBTITLES_OFF = "__off__"

/** Optionen für das Seitenpanel – direkt von der laufenden Engine abgefragt. */
fun PlayerViewModel.panelOptions(panel: OverlayPanel): List<TrackOption> {
    val current = engine.value
    return when (panel) {
        OverlayPanel.AUDIO -> current?.audioTracks().orEmpty()

        OverlayPanel.SUBTITLES -> {
            val tracks = current?.subtitleTracks().orEmpty()
            listOf(TrackOption(SUBTITLES_OFF, "Aus", tracks.none { it.selected })) + tracks
        }

        OverlayPanel.ENGINE -> PlayerEngineType.entries.map {
            TrackOption(it.name, it.label, it == ui.value.engineType)
        }

        OverlayPanel.SOURCE -> {
            val playable = ui.value.playable
            // „Alle Zugänge“ (Film/Folge): Zugang · Qualität · Sprache je Quelle.
            if (!playable?.vodSources.isNullOrEmpty()) {
                return playable.vodSources.mapIndexed { index, source ->
                    val label = listOfNotNull("Quelle ${index + 1}", source.fullLabel.ifBlank { null }, if (index == 0) "beste" else null).joinToString(" · ")
                    TrackOption(index.toString(), label, index == playable.variantIndex)
                }
            }
            playable?.variants.orEmpty().mapIndexed { index, variant ->
                val label = SourceLabels.live(index, variant, playable?.accountNames?.get(variant.playlistId), best = index == 0)
                TrackOption(index.toString(), label, index == playable?.variantIndex)
            }
        }

        OverlayPanel.SLEEP, OverlayPanel.TRACKS -> emptyList()

        // kommt aus dem SleepTimerState des PlayerScreen
        OverlayPanel.RECORD -> {
            val now = ui.value.nowProgram
            val untilEnd = now?.let {
                TrackOption(RECORD_UNTIL_END, "Bis Sendungsende (${TimeUtils.formatClock(it.endMs)}) · ${it.title}", false)
            }
            listOfNotNull(untilEnd) + recordDurations.map { (minutes, label) -> TrackOption(minutes.toString(), label, false) }
        }
    }
}

fun PlayerViewModel.selectPanelOption(panel: OverlayPanel, option: TrackOption) {
    val current = engine.value
    when (panel) {
        OverlayPanel.AUDIO -> current?.let {
            audioRestorer.remember(ui.value.playable, it, option)
            it.selectAudioTrack(option.id)
        }

        OverlayPanel.SUBTITLES -> current?.selectSubtitleTrack(option.id.takeIf { it != SUBTITLES_OFF })

        OverlayPanel.ENGINE -> switchEngine(PlayerEngineType.valueOf(option.id))

        OverlayPanel.RECORD, OverlayPanel.SLEEP, OverlayPanel.TRACKS -> Unit

        // erledigt der PlayerScreen (Aufnahme bzw. Sleep-Timer)
        OverlayPanel.SOURCE -> option.id.toIntOrNull()?.let { selectVariant(it) }
    }
}
