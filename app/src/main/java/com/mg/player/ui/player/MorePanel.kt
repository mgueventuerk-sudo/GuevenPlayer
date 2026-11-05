package com.mg.player.ui.player

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.mg.player.player.engine.TrackOption
import com.mg.player.ui.common.ListSurface
import com.mg.player.ui.common.MarqueeText
import com.mg.player.ui.common.RowColors
import com.mg.player.ui.common.requestFocusWhenReady
import com.mg.player.ui.theme.AnimatedIcon
import com.mg.player.ui.theme.AppColors
import com.mg.player.ui.theme.AppIcon
import com.mg.player.ui.theme.AppIcons

internal data class MoreEntry(val key: String, val label: String, val icon: AppIcon, val action: OverlayAction, val enabled: Boolean = true)

internal data class MoreSection(val title: String, val entries: List<MoreEntry>)

/** Inhalt des „Mehr“-Panels: alles, was nicht in die feste Reihe gehört, nach Themen gegliedert. Nicht Verfügbares bleibt ausgegraut stehen. */
internal fun moreSections(ui: PlayerUiState, sleepLabel: String): List<MoreSection> {
    val playable = ui.playable ?: return emptyList()
    val archive = playable.channel?.hasArchive == true
    val live = playable.isLive
    val picture = buildList {
        add(MoreEntry("ENGINE", "Player-Engine: ${ui.engineType.label}", AppIcons.Engine, OverlayAction.ENGINE))
        if (live || playable.vodSources.size > 1) {
            add(MoreEntry("SOURCE", playable.sourceLabel ?: "Quelle", AppIcons.Layers, OverlayAction.SOURCE, enabled = playable.sourceLabel != null))
        }
    }
    val playback = buildList {
        add(MoreEntry("SLEEP", sleepLabel, AppIcons.Clock, OverlayAction.SLEEP))
        if (live) {
            add(MoreEntry("RESTART", "Von Anfang an", AppIcons.Restart, OverlayAction.RESTART, enabled = archive && ui.nowProgram != null))
            add(MoreEntry("REWIND", "10 Min zurück", AppIcons.Back, OverlayAction.REWIND, enabled = archive))
        }
        if (playable.kind == PlayableKind.CATCHUP) add(MoreEntry("REWIND", "10 Min zurück", AppIcons.Back, OverlayAction.REWIND, enabled = archive))
    }
    val channel = if (live) {
        listOf(
            MoreEntry(
                "FAVORITE",
                if (ui.isFavorite) "Aus Favoriten entfernen" else "Zu Favoriten",
                if (ui.isFavorite) AppIcons.HeartFilled else AppIcons.Heart,
                OverlayAction.FAVORITE,
            ),
            MoreEntry("HISTORY", "Verlauf", AppIcons.Clock, OverlayAction.HISTORY),
        )
    } else {
        emptyList()
    }
    val info = listOf(MoreEntry("INFO", "Stream-Info", AppIcons.Info, OverlayAction.INFO))
    return listOf(
        MoreSection("Bild & Ton", picture),
        MoreSection("Wiedergabe", playback),
        MoreSection("Sender", channel),
        MoreSection("Info", info),
    ).filter { it.entries.isNotEmpty() }
}

/** Gegliedertes „Mehr“-Panel; der Fokus startet auf dem zuletzt gewählten Eintrag ([focusKey]). */
@Composable
internal fun MorePanel(
    sections: List<MoreSection>,
    focusKey: String?,
    onFocusKey: (String) -> Unit,
    onAction: (MoreEntry) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val requester = remember { FocusRequester() }
    val entries = sections.flatMap { it.entries }
    val target = entries.firstOrNull { it.key == focusKey && it.enabled }?.key ?: entries.firstOrNull { it.enabled }?.key
    LaunchedEffect(Unit) { requester.requestFocusWhenReady() }
    PanelFrame("Mehr", onClose, modifier) {
        Column(Modifier.verticalScroll(rememberScrollState())) {
            sections.forEach { section ->
                Text(
                    section.title,
                    modifier = Modifier.padding(start = 4.dp, top = 16.dp, bottom = 6.dp),
                    style = MaterialTheme.typography.labelLarge,
                    color = AppColors.TextSecondary,
                )
                section.entries.forEach { entry ->
                    ListSurface(
                        onClick = { if (entry.enabled) onAction(entry) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 2.dp)
                            .alpha(if (entry.enabled) 1f else DISABLED_ALPHA)
                            .onFocusChanged { if (it.isFocused) onFocusKey(entry.key) }
                            .then(if (entry.key == target) Modifier.focusRequester(requester) else Modifier),
                    ) {
                        Row(Modifier.padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                            AnimatedIcon(entry.icon, tint = AppColors.TextPrimary, active = false, size = 22.dp)
                            Spacer(Modifier.width(12.dp))
                            MarqueeText(entry.label, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
        }
    }
}

/** Ton und Untertitel nebeneinander; eine Auswahl lässt das Panel offen, Zurück schließt es. */
@Composable
internal fun TracksPanel(
    audio: List<TrackOption>,
    subtitles: List<TrackOption>,
    onAudio: (TrackOption) -> Unit,
    onSubtitle: (TrackOption) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val requester = remember { FocusRequester() }
    LaunchedEffect(Unit) { requester.requestFocusWhenReady() }
    PanelFrame("Ton & Untertitel", onClose, modifier, width = TRACKS_WIDTH) {
        Row(Modifier.fillMaxHeight().padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
            TrackColumn("Tonspur", audio, onAudio, requester.takeIf { audio.isNotEmpty() }, Modifier.weight(1f))
            TrackColumn("Untertitel", subtitles, onSubtitle, requester.takeIf { audio.isEmpty() }, Modifier.weight(1f))
        }
    }
}

/** Zwei Spalten nebeneinander brauchen mehr Platz; am Handy begrenzt der Rahmen auf die Bildschirmbreite. */
private const val TRACKS_WIDTH = 640

@Composable
private fun TrackColumn(title: String, options: List<TrackOption>, onSelect: (TrackOption) -> Unit, requester: FocusRequester?, modifier: Modifier) {
    val focusIndex = options.indexOfFirst { it.selected }.coerceAtLeast(0)
    Column(modifier.verticalScroll(rememberScrollState())) {
        Text(title, style = MaterialTheme.typography.titleMedium, color = AppColors.TextSecondary)
        Spacer(Modifier.height(8.dp))
        if (options.isEmpty()) {
            Text("Keine Auswahl verfügbar", style = MaterialTheme.typography.bodyMedium, color = AppColors.TextSecondary)
        }
        options.forEachIndexed { index, option ->
            ListSurface(
                onClick = { onSelect(option) },
                selected = option.selected,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 2.dp)
                    .then(if (requester != null && index == focusIndex) Modifier.focusRequester(requester) else Modifier),
            ) {
                Row(Modifier.padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    MarqueeText(
                        option.label,
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.bodyLarge,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (option.selected) AnimatedIcon(AppIcons.Check, tint = RowColors.accent, active = true, size = 22.dp)
                }
            }
        }
    }
}
