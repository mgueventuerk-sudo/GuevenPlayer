package com.mg.player.ui.accounts

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.mg.player.domain.model.ConnectionStatus
import com.mg.player.domain.model.Playlist
import com.mg.player.ui.common.ListSurface
import com.mg.player.ui.common.LocalAppContainer
import com.mg.player.ui.common.LocalSurfaceFocused
import com.mg.player.ui.common.RowColors
import com.mg.player.ui.common.glass
import com.mg.player.ui.theme.AppShapes
import com.mg.player.ui.theme.AnimatedIcon
import com.mg.player.ui.theme.AppColors
import com.mg.player.ui.theme.AppIcon
import com.mg.player.ui.theme.AppIcons
import com.mg.player.ui.theme.Dimens
import kotlinx.coroutines.delay

/**
 * Schnellwechsel zwischen mehreren Zugängen (Xtream oder M3U). Als eigenes Fenster umgesetzt,
 * damit der Fokus der Fernbedienung im Dialog bleibt und "Zurück" ihn schließt.
 */
@Composable
fun AccountSwitcherDialog(
    playlists: List<Playlist>,
    activeId: Long?,
    onSelect: (Playlist) -> Unit,
    onAdd: () -> Unit,
    onDismiss: () -> Unit,
    /** „Alle Zugänge & EPG aktualisieren“ (nur bei mehreren Zugängen angeboten). */
    onRefreshAll: (() -> Unit)? = null,
) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        val focusId = playlists.firstOrNull { it.id == activeId }?.id ?: playlists.firstOrNull()?.id
        val requester = remember { FocusRequester() }
        val connections = rememberConnections()
        val monitor = LocalAppContainer.current.connectionMonitor
        // Beim Öffnen frisch abfragen – der Wechsel hängt davon ab, ob ein Zugang gerade frei ist.
        LaunchedEffect(monitor) { monitor.refresh() }
        LaunchedEffect(Unit) {
            // Das Dialogfenster braucht einen Moment, bis es Fokus annehmen kann.
            repeat(10) {
                if (runCatching { requester.requestFocus() }.isSuccess) return@LaunchedEffect
                delay(50)
            }
        }
        Column(
            Modifier
                .padding(horizontal = 16.dp)
                .fillMaxWidth()
                .widthIn(max = 660.dp)
                .glass(AppShapes.Panel, fill = AppColors.Panel)
                .padding(28.dp),
        ) {
            Text("Zugang wechseln", style = MaterialTheme.typography.headlineSmall)
            Text(
                "Alle Zugänge bleiben gespeichert – der Wechsel dauert nur einen Moment.",
                style = MaterialTheme.typography.bodyMedium,
                color = AppColors.TextSecondary,
                modifier = Modifier.padding(top = 4.dp, bottom = 18.dp),
            )
            LazyColumn(Modifier.heightIn(max = 430.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                items(playlists, key = { it.id }, contentType = { "account" }) { playlist ->
                    AccountRow(
                        playlist = playlist,
                        active = playlist.id == activeId,
                        connection = connections[playlist.id],
                        onClick = { onSelect(playlist) },
                        modifier = if (playlist.id == focusId) Modifier.focusRequester(requester) else Modifier,
                    )
                }
                item(key = "add", contentType = "add") {
                    ExtraRow(
                        AppIcons.Plus,
                        "Weiteren Zugang hinzufügen",
                        "Xtream Codes oder M3U – per Handy oder manuell",
                        onAdd,
                        if (focusId ==
                            null
                        ) {
                            Modifier.focusRequester(requester)
                        } else {
                            Modifier
                        },
                    )
                }
                if (onRefreshAll != null && playlists.size > 1) {
                    item(key = "refresh-all", contentType = "add") {
                        ExtraRow(AppIcons.Refresh, "Alle Zugänge & EPG aktualisieren", "Läuft im Hintergrund – kleine Anzeige in der Ecke", onRefreshAll)
                    }
                }
            }
        }
    }
}

@Composable
private fun AccountRow(playlist: Playlist, active: Boolean, connection: ConnectionStatus?, onClick: () -> Unit, modifier: Modifier = Modifier) {
    ListSurface(
        onClick = onClick,
        selected = active,
        modifier = modifier.fillMaxWidth(),
        containerColor = if (active) AppColors.AccentSoft else AppColors.Glass,
    ) {
        Row(Modifier.padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            AccountAvatar(playlist, size = 46.dp)
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    playlist.name,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    playlist.detailLabel(),
                    style = MaterialTheme.typography.bodySmall,
                    color = RowColors.secondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                AccountStatusLine(connection, Modifier.padding(top = 2.dp))
            }
            ConnectionBadge(connection, Modifier.padding(start = 12.dp))
            if (active) {
                Text(
                    "Aktiv",
                    modifier = Modifier
                        .padding(start = 12.dp)
                        .background(if (LocalSurfaceFocused.current) AppColors.OnFocus.copy(alpha = 0.14f) else AppColors.AccentSoft, RoundedCornerShape(50))
                        .padding(horizontal = 12.dp, vertical = 4.dp),
                    style = MaterialTheme.typography.labelMedium,
                    color = RowColors.accent,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
    }
}

@Composable
private fun ExtraRow(icon: AppIcon, title: String, subtitle: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    ListSurface(onClick = onClick, modifier = modifier.fillMaxWidth(), containerColor = AppColors.Glass) {
        Row(Modifier.padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            val focused = LocalSurfaceFocused.current
            Box(
                Modifier
                    .size(46.dp)
                    .clip(CircleShape)
                    .background(if (focused) AppColors.OnFocus.copy(alpha = 0.12f) else AppColors.GlassRaised),
                contentAlignment = Alignment.Center,
            ) {
                AnimatedIcon(icon, tint = RowColors.accent, active = focused, size = 26.dp)
            }
            Spacer(Modifier.width(16.dp))
            Column {
                Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = RowColors.secondary,
                )
            }
        }
    }
}
