package com.mg.player.ui.player

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.mg.player.core.util.TimeUtils
import com.mg.player.data.recording.RecordingManager
import com.mg.player.domain.model.Channel
import com.mg.player.domain.model.EpgProgram
import com.mg.player.domain.model.accessDeniedReason
import com.mg.player.domain.model.playlistIdOf
import com.mg.player.player.engine.PlaybackStatus
import com.mg.player.player.engine.TrackOption
import com.mg.player.ui.accounts.rememberConnections
import com.mg.player.ui.common.CenteredMessage
import com.mg.player.ui.common.LoadingIndicator
import com.mg.player.ui.common.LocalAppContainer
import com.mg.player.ui.common.glass
import com.mg.player.ui.common.isTouch
import com.mg.player.ui.live.ChannelLogo
import com.mg.player.ui.theme.AppShapes
import com.mg.player.ui.theme.AppColors
import com.mg.player.ui.theme.AppIcons
import com.mg.player.ui.theme.Dimens

/** Aufnahme "bis Sendungsende" läuft zwei Minuten länger – Sender überziehen gern. */
private const val RECORD_POST_ROLL_MS = 2 * 60_000L

/** Ladeanzeige bzw. Fehlermeldung über dem Videobild. */
@Composable
internal fun PlaybackStatusMessage(ui: PlayerUiState, modifier: Modifier = Modifier) {
    val touch = isTouch
    val error = ui.loadError
    val failure = ui.failure
    when {
        error != null -> CenteredMessage(
            error,
            subtitle = if (touch) "Mit „Zurück“ geht es zur vorherigen Seite." else "Zurück-Taste drücken, um zurückzukehren.",
            icon = AppIcons.Info,
        )

        failure != null -> CenteredMessage("Stream nicht verfügbar", subtitle = refinedFailure(ui, failure), icon = AppIcons.Wifi)

        ui.engine.status == PlaybackStatus.ERROR && !ui.reconnecting -> CenteredMessage(
            "Stream nicht verfügbar",
            subtitle = ui.engine.errorMessage?.let {
                if (touch) "$it – antippen und im Menü eine andere Engine wählen." else "$it – OK drücken und eine andere Player-Engine wählen."
            },
            icon = AppIcons.Engine,
        )

        ui.isBuffering || ui.waitingForNetwork -> Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
            LoadingIndicator(size = 56.dp)
            val hint = when {
                ui.waitingForNetwork -> "Keine Internetverbindung – die Wiedergabe startet automatisch, sobald das Netz zurück ist."
                ui.reconnecting -> ui.recoveryText?.let { "$it …" } ?: "Verbindung wird wiederhergestellt…"
                else -> null
            }
            hint?.let {
                Text(
                    it,
                    modifier = Modifier.padding(top = 14.dp).glass(RoundedCornerShape(50), fill = AppColors.Panel).padding(horizontal = 18.dp, vertical = 8.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    color = AppColors.TextPrimary,
                )
            }
        }
    }
}

@Composable
internal fun ZapBanner(ui: PlayerUiState, modifier: Modifier = Modifier) {
    val playable = ui.playable ?: return
    Row(
        modifier
            .glass(AppShapes.Panel, fill = AppColors.Panel)
            .padding(horizontal = 18.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        playable.channel?.let { channel ->
            ChannelLogo(channel.logoUrl, channel.name, Modifier.size(width = 64.dp, height = 44.dp))
            Spacer(Modifier.width(14.dp))
        }
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                ui.channelNumber?.let {
                    Text(it.toString(), style = MaterialTheme.typography.titleLarge, color = AppColors.AccentText, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.width(10.dp))
                }
                Text(playable.title, style = MaterialTheme.typography.titleLarge, maxLines = 1)
            }
            ui.nowProgram?.let { Text(it.title, style = MaterialTheme.typography.bodyMedium, color = AppColors.TextSecondary, maxLines = 1) }
        }
    }
}

/** Eingetippte Sendernummer oben rechts. */
@Composable
internal fun DigitsBadge(digits: String, modifier: Modifier = Modifier) {
    Text(
        digits,
        modifier = modifier
            .glass(AppShapes.Panel, fill = AppColors.Panel)
            .padding(horizontal = 26.dp, vertical = 8.dp),
        style = MaterialTheme.typography.displayMedium,
        color = AppColors.TextPrimary,
    )
}

@Composable
internal fun ToastBanner(message: String, modifier: Modifier = Modifier) {
    Text(
        message,
        modifier = modifier
            .glass(RoundedCornerShape(50), fill = AppColors.Panel)
            .padding(horizontal = 24.dp, vertical = 12.dp),
        style = MaterialTheme.typography.bodyLarge,
        fontWeight = FontWeight.Medium,
        color = AppColors.TextPrimary,
    )
}

/** Startet eine Aufnahme aus dem Aufnehmen-Panel. Liefert die Rückmeldung für den Hinweis oben. */
internal suspend fun startRecording(recordings: RecordingManager, channel: Channel, now: EpgProgram?, option: TrackOption): String {
    val untilEnd = option.id == RECORD_UNTIL_END
    val endMs = if (untilEnd) {
        (now?.endMs ?: System.currentTimeMillis()) + RECORD_POST_ROLL_MS
    } else {
        System.currentTimeMillis() + (option.id.toLongOrNull() ?: 60) * 60_000
    }
    val title = if (untilEnd) now?.title.orEmpty() else now?.title ?: channel.name
    val programStart = if (untilEnd) now?.startMs ?: 0L else 0L
    return recordings.start(channel, title, endMs, programStart) ?: "Aufnahme läuft bis ${TimeUtils.formatClock(endMs)}"
}

/**
 * „Zugriff verweigert“ genauer erklären, wenn die Kontodaten des Zugangs den Grund kennen
 * (Abo abgelaufen oder alle Verbindungen belegt). Dafür wird der Stand einmal frisch abgefragt.
 */
@Composable
private fun refinedFailure(ui: PlayerUiState, failure: String): String {
    if (!failure.startsWith("Zugriff verweigert")) return failure
    // „Alle Zugänge“: der Zugang der laufenden Quelle, nicht die Kombination.
    val playlistId = ui.playable?.let { it.activePlaylistId ?: playlistIdOf(it.id) } ?: return failure
    val monitor = LocalAppContainer.current.connectionMonitor
    LaunchedEffect(playlistId) { monitor.refresh(force = true) }
    val status = rememberConnections()[playlistId] ?: return failure
    return status.accessDeniedReason(System.currentTimeMillis(), usedHere = monitor.recentlyUsedHere(playlistId)) ?: failure
}
