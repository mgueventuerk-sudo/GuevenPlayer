package com.mg.player.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.mg.player.data.combined.Combined
import com.mg.player.domain.model.ConnectionStatus
import com.mg.player.domain.model.EPG_SHIFT_MAX_HOURS
import com.mg.player.domain.model.Playlist
import com.mg.player.ui.accounts.AccountAvatar
import com.mg.player.ui.accounts.AccountStatusLine
import com.mg.player.ui.accounts.hostLabel
import com.mg.player.ui.accounts.isPlainHttp
import com.mg.player.ui.accounts.typeLabel
import com.mg.player.ui.accounts.updatedLabel
import com.mg.player.ui.common.ListSurface
import com.mg.player.ui.common.LocalSurfaceFocused
import com.mg.player.ui.common.RowColors
import com.mg.player.ui.navigation.Screen
import com.mg.player.ui.theme.AnimatedIcon
import com.mg.player.ui.theme.AppIcons

/** Bereich „Zugänge“: je Zugang eine Karte (öffnet seine Seite), dazu „Alle Zugänge“ und Aktualisieren. */
internal fun LazyListScope.accountOverviewItems(c: SettingsContext) {
    settingsGroup("accounts") {
        c.playlists.forEach { playlist ->
            row("pl-${playlist.id}", contentType = "account") {
                val key = "pl-${playlist.id}"
                AccountCard(
                    playlist = playlist,
                    active = playlist.id == c.s.activePlaylistId,
                    connection = c.connections[playlist.id],
                    onClick = { c.onOpen(SettingsSub.Account(playlist.id), key) },
                    modifier = c.returnFocus(key),
                )
            }
        }
        row("add-playlist") {
            ActionRow(
                "Zugang hinzufügen",
                { c.navigator.push(Screen.AddPlaylist) },
                "Xtream oder M3U – per QR-Code vom Handy oder von Hand",
                icon = AppIcons.Plus,
            )
        }
    }
    if (c.playlists.size >= 2) {
        settingsGroup("combined", Combined.NAME) {
            row("combined-open", modifier = c.returnFocus("combined-open")) {
                val cfg = c.combined
                ActionRow(
                    "„${Combined.NAME}“",
                    { c.onOpen(SettingsSub.Combined, "combined-open") },
                    "Eine Liste aus allen Zugängen, beste Quelle zuerst",
                    trailing = if (cfg.autoSwitch) "Wechsel an" else "Wechsel aus",
                    icon = AppIcons.Layers,
                )
            }
        }
    }
    accountUpdateItems(c)
}

private fun LazyListScope.accountUpdateItems(c: SettingsContext) {
    settingsGroup("update", "Aktualisieren") {
        row("refresh") {
            val running = if (c.sync.running) "läuft …" else null
            if (c.playlists.size > 1) {
                ActionRow(
                    "Alle Zugänge jetzt aktualisieren",
                    c.vm::refreshEverything,
                    "Playlist und EPG nacheinander im Hintergrund",
                    running,
                    AppIcons.Refresh,
                    chevron = false,
                )
            } else {
                ActionRow("Jetzt aktualisieren", c.vm::refreshAll, "Playlist und EPG im Hintergrund", running, AppIcons.Refresh, chevron = false)
            }
        }
        row("auto-update") {
            ToggleRow("Beim App-Start aktualisieren", c.s.autoUpdateOnStart, {
                c.vm.update { it.copy(autoUpdateOnStart = !it.autoUpdateOnStart) }
            }, icon = AppIcons.Download)
        }
    }
}

/** Unterseite eines Zugangs: alles, was nur ihn betrifft. */
internal fun LazyListScope.accountDetailItems(playlistId: Long, c: SettingsContext) {
    val playlist = c.playlists.firstOrNull { it.id == playlistId }
    if (playlist == null) {
        item(key = "gone") { SettingsPageHeader("Zugang gelöscht", null, breadcrumb = SettingsPage.ACCOUNTS.label) }
        return
    }
    val active = playlist.id == c.s.activePlaylistId
    item(key = "account-header", contentType = "header") { AccountHeader(playlist, active, c.connections[playlist.id], showBreadcrumb = !c.touch) }
    accountActionItems(playlist, active, c)
    accountOptionItems(playlist, c)
    accountDeleteItems(playlist, c)
}

private fun LazyListScope.accountActionItems(playlist: Playlist, active: Boolean, c: SettingsContext) {
    settingsGroup("account-actions") {
        if (!active) {
            row("switch") {
                ActionRow(
                    "Zu diesem Zugang wechseln",
                    { c.vm.switchTo(playlist.id) { c.navigator.resetTo(Screen.Home) } },
                    icon = AppIcons.Swap,
                    chevron = false,
                )
            }
        }
        row("refresh-one") {
            ActionRow(
                "Jetzt aktualisieren",
                { c.vm.refreshPlaylist(playlist.id) },
                "Playlist und EPG neu laden",
                trailing = if (c.sync.running) "läuft …" else null,
                icon = AppIcons.Refresh,
                chevron = false,
            )
        }
        row("refresh-epg-one") {
            ActionRow("Nur EPG aktualisieren", { c.vm.refreshPlaylist(playlist.id, epgOnly = true) }, icon = AppIcons.Guide, chevron = false)
        }
        row("edit") {
            ActionRow(
                "Zugangsdaten bearbeiten",
                { c.gate.runWithPin { c.navigator.push(Screen.EditPlaylist(playlist.id)) } },
                "Name, Server, Benutzer, Passwort, Adressen – Favoriten und Verlauf bleiben",
                icon = AppIcons.User,
            )
        }
    }
}

private fun LazyListScope.accountDeleteItems(playlist: Playlist, c: SettingsContext) {
    settingsGroup("account-danger") {
        row("delete") {
            ActionRow(
                "Zugang löschen",
                {
                    c.onConfirm(
                        ConfirmRequest(
                            "„${playlist.name}“ löschen?",
                            "Sender, Filme, Serien und EPG dieses Zugangs werden entfernt. Favoriten und Fortschritt anderer Zugänge bleiben.",
                            "Löschen",
                        ) { c.vm.deletePlaylist(playlist.id) },
                    )
                },
                icon = AppIcons.Trash,
                danger = true,
                chevron = false,
            )
        }
    }
}

/** Einstellungen, die nur diesen Zugang betreffen. */
private fun LazyListScope.accountOptionItems(playlist: Playlist, c: SettingsContext) {
    settingsGroup("account-options", "Einstellungen für diesen Zugang") {
        row("auto-refresh") {
            ActionRow(
                "Automatisch aktualisieren",
                { c.onEditAutoRefresh(playlist) },
                "Playlist und EPG nach so vielen Tagen neu laden",
                trailing = autoRefreshLabel(playlist.autoRefreshDays),
                icon = AppIcons.Clock,
            )
        }
        row("countries") {
            ActionRow(
                "Länder & Sprachen",
                { c.navigator.push(Screen.CountryFilter(firstRun = false, playlistId = playlist.id)) },
                "Nur Gruppen der gewählten Länder anzeigen",
                icon = AppIcons.Globe,
            )
        }
        row("epg-shift") {
            val hours = playlist.epgShiftHours
            StepperRow(
                "EPG-Zeitverschiebung",
                if (hours > 0) "+$hours h" else "$hours h",
                onMinus = { c.vm.setEpgShift(playlist.id, (hours - 1).coerceAtLeast(-EPG_SHIFT_MAX_HOURS)) },
                onPlus = { c.vm.setEpgShift(playlist.id, (hours + 1).coerceAtMost(EPG_SHIFT_MAX_HOURS)) },
                description = "Korrigiert falsche Sendezeiten dieses Anbieters",
                icon = AppIcons.Calendar,
            )
        }
        row("http") {
            ToggleRow(
                "Unverschlüsseltes HTTP zulassen",
                playlist.allowInsecureHttp,
                { c.vm.setAllowInsecureHttp(playlist.id, !playlist.allowInsecureHttp) },
                "Nur einschalten, wenn der Anbieter kein HTTPS kann",
                AppIcons.Shield,
            )
        }
    }
    if (playlist.isPlainHttp() || playlist.allowInsecureHttp) {
        item(key = "http-notice", contentType = "notice") {
            SettingsNotice("Ohne HTTPS können Benutzername, Passwort und Adressen im Netz mitgelesen werden. Das eingebaute VPN schützt davor.")
        }
    }
}

/** „Alle Zugänge“: Wechsel, Kategorien, welche Zugänge einfließen und Werkzeuge. */
internal fun LazyListScope.combinedItems(c: SettingsContext) {
    item(key = "combined-header", contentType = "header") {
        SettingsPageHeader(
            Combined.NAME,
            "Live-TV, Filme und Serien aller Zugänge in einer Liste",
            breadcrumb = SettingsPage.ACCOUNTS.label.takeUnless { c.touch },
        )
    }
    combinedMainItems(c)
    combinedAccountItems(c)
    combinedToolItems(c)
}

private fun LazyListScope.combinedMainItems(c: SettingsContext) {
    val cfg = c.combined
    val footer = "Kein Prüfen von Hand nötig: Die Zuordnung läuft nach jeder Aktualisierung von selbst. Der Senderkatalog " +
        "(iptv-org) wird wöchentlich mit dem Internet abgeglichen – nur die öffentliche Liste wird geladen, nichts gesendet. " +
        catalogLine(c.vm.catalogOnlineAt)
    settingsGroup("combined-auto-match", footer = footer) {
        row("combined-match-status") {
            ActionRow(
                "Automatische Zuordnung",
                c.vm::rebuildCombined,
                matchSummary(c.combinedStats),
                trailing = "Neu zuordnen",
                icon = AppIcons.CheckCircle,
                chevron = false,
            )
        }
    }
    settingsGroup("combined-main") {
        if (!Combined.isCombined(c.s.activePlaylistId)) {
            row("combined-show") {
                ActionRow(
                    "„${Combined.NAME}“ jetzt anzeigen",
                    { c.vm.switchTo(Combined.ID) { c.navigator.resetTo(Screen.Home) } },
                    icon = AppIcons.Layers,
                    chevron = false,
                )
            }
        }
        row("combined-auto") {
            ToggleRow(
                "Automatisch zur nächsten Quelle",
                cfg.autoSwitch,
                { c.vm.updateCombined(rebuild = false) { it.copy(autoSwitch = !it.autoSwitch) } },
                if (cfg.autoSwitch) {
                    "Klappt eine Quelle nicht, startet sofort die nächste – auch in einem anderen Zugang"
                } else {
                    "Vor dem Wechsel in einen anderen Zugang wird gefragt"
                },
                AppIcons.Swap,
            )
        }
        row("combined-groups") {
            ToggleRow(
                "Kategorien nach Zugang trennen",
                cfg.byAccount,
                { c.vm.updateCombined(rebuild = true) { it.copy(byAccount = !it.byAccount) } },
                if (cfg.byAccount) "Gruppen je Zugang, jeder Inhalt einmal" else "Gleiche Gruppen werden zusammengelegt („Deutschland · Sport“)",
                AppIcons.Grid,
            )
        }
    }
}

/** Welche Zugänge einfließen und in welcher Rangfolge. */
private fun LazyListScope.combinedAccountItems(c: SettingsContext) {
    val cfg = c.combined
    val order = cfg.included(c.playlists.map { it.id })
    settingsGroup("combined-accounts", "Zugänge und Rangfolge", footer = "Bei gleicher Qualität zählt zuerst der freieste Zugang, dann diese Rangfolge.") {
        c.playlists.forEach { playlist ->
            row("combined-pl-${playlist.id}") {
                val included = playlist.id !in cfg.excluded
                ToggleRow(
                    playlist.name,
                    included,
                    { c.vm.updateCombined(rebuild = true) { it.copy(excluded = if (included) it.excluded + playlist.id else it.excluded - playlist.id) } },
                    if (included) "Rang ${order.indexOf(playlist.id) + 1}" else "Fließt nicht ein",
                    AppIcons.User,
                )
            }
        }
        if (order.size > 1) {
            row("combined-order") {
                val names = c.playlists.associate { it.id to it.name }
                ActionRow(
                    "Zweiten Zugang nach vorn",
                    { order.getOrNull(1)?.let(c.vm::moveAccountUp) },
                    order.mapIndexed { i, id -> "${i + 1}. ${names[id].orEmpty()}" }.joinToString("  ·  "),
                    icon = AppIcons.Reorder,
                    chevron = false,
                )
            }
        }
    }
}

private fun LazyListScope.combinedToolItems(c: SettingsContext) {
    settingsGroup("combined-tools", "Werkzeuge") {
        row("combined-names") {
            ActionRow(
                "Namen für den Abgleich aufs Handy",
                { c.navigator.push(Screen.Share(ShareMode.NAMES)) },
                "Ohne Zugangsdaten und Adressen",
                icon = AppIcons.Document,
            )
        }
    }
}

/** Karte eines Zugangs in der Übersicht: Name, Art und Server, Marken für Zustand und Hinweise. */
@Composable
private fun AccountCard(playlist: Playlist, active: Boolean, connection: ConnectionStatus?, onClick: () -> Unit, modifier: Modifier = Modifier) {
    ListSurface(onClick = onClick, modifier = Modifier.fillMaxWidth().then(modifier), containerColor = Color.Transparent, focusedScale = 1.01f) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            AccountAvatar(playlist, size = 42.dp)
            Column(Modifier.weight(1f).padding(start = 14.dp)) {
                Text(playlist.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, color = RowColors.primary, maxLines = 1)
                Text(
                    listOf(playlist.typeLabel(), playlist.hostLabel(), playlist.updatedLabel()).joinToString("  ·  "),
                    style = MaterialTheme.typography.bodySmall,
                    color = RowColors.muted,
                    maxLines = 1,
                )
                AccountChips(playlist, active, connection, Modifier.padding(top = 6.dp))
                AccountStatusLine(connection, Modifier.padding(top = 3.dp))
            }
            Spacer(Modifier.width(12.dp))
            AnimatedIcon(AppIcons.ChevronRight, tint = RowColors.muted, active = LocalSurfaceFocused.current, size = 18.dp)
        }
    }
}

@Composable
private fun AccountChips(playlist: Playlist, active: Boolean, connection: ConnectionStatus?, modifier: Modifier = Modifier) {
    ChipRow(modifier) {
        if (active) SettingsChip("Aktiv", ChipTone.ACCENT)
        connection?.let { SettingsChip("● ${it.label} Verbindungen", if (it.inUse) ChipTone.SUCCESS else ChipTone.NEUTRAL) }
        if (playlist.isPlainHttp()) SettingsChip("⚠ HTTP", ChipTone.WARNING)
        if (playlist.autoRefreshDays > 0) SettingsChip("Auto ${autoRefreshLabel(playlist.autoRefreshDays)}")
        if (playlist.epgShiftHours != 0) SettingsChip("EPG ${if (playlist.epgShiftHours > 0) "+" else ""}${playlist.epgShiftHours} h")
    }
}

/** Kopf der Zugangs-Seite: großer Avatar, Name, Marken. */
@Composable
private fun AccountHeader(playlist: Playlist, active: Boolean, connection: ConnectionStatus?, showBreadcrumb: Boolean) {
    Column(Modifier.padding(start = 6.dp, top = 6.dp)) {
        // Am Handy steht der Weg zurück schon in der Kopfzeile.
        if (showBreadcrumb) {
            Text("${SettingsPage.ACCOUNTS.label}  ›", style = MaterialTheme.typography.labelLarge, color = com.mg.player.ui.theme.AppColors.TextMuted)
        }
        Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            AccountAvatar(playlist, size = 52.dp)
            Column(Modifier.padding(start = 14.dp)) {
                Text(playlist.name, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, maxLines = 1)
                Text(
                    listOf(playlist.typeLabel(), playlist.hostLabel(), playlist.updatedLabel()).joinToString("  ·  "),
                    style = MaterialTheme.typography.bodySmall,
                    color = com.mg.player.ui.theme.AppColors.TextMuted,
                    maxLines = 1,
                )
                AccountChips(playlist, active, connection, Modifier.padding(top = 6.dp))
            }
        }
    }
}

/** Kurzfassung des letzten Aufbaus: wie viel zusammengeführt, erkannt und verhindert wurde. */
/** Stand des Senderkatalogs für die Fußzeile. */
internal fun catalogLine(onlineAtMs: Long): String = if (onlineAtMs <= 0L) {
    "Stand: mitgelieferter Katalog."
} else {
    "Zuletzt abgeglichen: " + java.text.SimpleDateFormat("dd.MM.yyyy", java.util.Locale.GERMANY).format(java.util.Date(onlineAtMs)) + "."
}

internal fun matchSummary(stats: com.mg.player.data.combined.CombinedStats?): String {
    if (stats == null) return "Senderkatalog, EPG-ID, Name und laufendes Programm werden verglichen"
    val de = java.text.NumberFormat.getIntegerInstance(java.util.Locale.GERMANY)
    return "${de.format(stats.channels)} Sender, ${de.format(stats.movies)} Filme, ${de.format(stats.series)} Serien · " +
        "${de.format(stats.identified)} von ${de.format(stats.sources)} Senderquellen im Katalog erkannt · " +
        "${de.format(stats.refused)} Verwechslungen verhindert · " +
        "Katalog ${de.format(stats.catalogSize)} Sender"
}
