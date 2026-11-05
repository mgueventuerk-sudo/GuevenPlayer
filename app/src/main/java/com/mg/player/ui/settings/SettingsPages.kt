package com.mg.player.ui.settings

import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import com.mg.player.BuildConfig
import com.mg.player.core.vpn.VpnProfile
import com.mg.player.data.combined.CombinedConfig
import com.mg.player.data.combined.CombinedStats
import com.mg.player.data.sync.SyncState
import com.mg.player.domain.model.AppSettings
import com.mg.player.domain.model.ConnectionStatus
import com.mg.player.domain.model.Playlist
import com.mg.player.domain.model.StationReport
import com.mg.player.ui.common.ParentalGateState
import com.mg.player.ui.navigation.Navigator
import com.mg.player.ui.navigation.Screen
import com.mg.player.ui.theme.AppIcon
import com.mg.player.ui.theme.AppIcons

/** Bereiche der Einstellungen – links (TV) bzw. als Menü (Handy), rechts bzw. darunter ihr Inhalt. */
enum class SettingsPage(val label: String, val icon: AppIcon, val lead: String) {
    ACCOUNTS("Zugänge", AppIcons.User, "OK auf einem Zugang öffnet alles, was nur ihn betrifft."),
    PLAYBACK("Wiedergabe", AppIcons.Play, "Player, Serien, Bild und Ton"),
    CONTENT("Inhalte", AppIcons.Grid, "Kategorien, Favoriten und Film-Infos"),
    SPORT("Sport", AppIcons.Sport, "Ligen und Sender für die Sport-Übersicht"),
    SECURITY("Sicherheit", AppIcons.Shield, "VPN, Kindersicherung und Datenschutz"),
    LOOK("Darstellung", AppIcons.Palette, "Farben, Schrift und Effekte"),
    SYSTEM("System", AppIcons.Storage, "Sicherung, Speicher, Hilfe"),
}

/**
 * Unterseite innerhalb eines Bereichs (eine Ebene tiefer). Zurück führt in den Bereich, zum Eintrag, der sie geöffnet
 * hat. Als Text speicherbar (übersteht Drehen und Rückkehr aus anderen Bildschirmen).
 */
internal sealed interface SettingsSub {
    val page: SettingsPage
    val code: String

    data class Account(val playlistId: Long) : SettingsSub {
        override val page = SettingsPage.ACCOUNTS
        override val code = "account:$playlistId"
    }

    data object Combined : SettingsSub {
        override val page = SettingsPage.ACCOUNTS
        override val code = "combined"
    }

    data object PlaybackAdvanced : SettingsSub {
        override val page = SettingsPage.PLAYBACK
        override val code = "playback-advanced"
    }

    data object VpnLocations : SettingsSub {
        override val page = SettingsPage.SECURITY
        override val code = "vpn-locations"
    }

    companion object {
        fun decode(code: String?): SettingsSub? = when {
            code == null -> null
            code.startsWith("account:") -> code.substringAfter(':').toLongOrNull()?.let(::Account)
            code == Combined.code -> Combined
            code == PlaybackAdvanced.code -> PlaybackAdvanced
            code == VpnLocations.code -> VpnLocations
            else -> null
        }
    }
}

/** Zustand, den die Seiten anzeigen (alles mit Standardwerten – für Tests und Vorschauen einfach zu bauen). */
internal class SettingsState(
    val s: AppSettings = AppSettings(),
    val playlists: List<Playlist> = emptyList(),
    val connections: Map<Long, ConnectionStatus> = emptyMap(),
    val sync: SyncState = SyncState(),
    val hasPin: Boolean = false,
    val unlocked: Boolean = false,
    val touch: Boolean = false,
    val sportProblem: String? = null,
    val sportSummary: Pair<Int, Int>? = null,
    val vpn: VpnSettings = VpnSettings(),
    val combined: CombinedConfig = CombinedConfig(),
    val combinedStats: CombinedStats? = null,
    val sportStations: StationReport? = null,
)

/** Was die Seiten auslösen, das über die Seite hinausgeht (Dialoge, Unterseiten). */
internal class SettingsActions(
    val onSetPin: () -> Unit = {},
    val onEditAutoRefresh: (Playlist) -> Unit = {},
    /** Unterseite öffnen; [from] = Schlüssel der Zeile, auf die der Fokus beim Zurückkehren springt. */
    val onOpen: (sub: SettingsSub, from: String) -> Unit = { _, _ -> },
    /** Rückfrage vor einer Aktion, die sich nicht rückgängig machen lässt. */
    val onConfirm: (ConfirmRequest) -> Unit = {},
    /** Fokus-Ziel einer Zeile, die eine Unterseite öffnet (Rückkehr an dieselbe Stelle). */
    val returnFocus: (key: String) -> Modifier = { Modifier },
)

/** Was die Seiten brauchen – einmal gebündelt statt dutzender Parameter. */
internal class SettingsContext(
    val vm: SettingsViewModel,
    val navigator: Navigator,
    val gate: ParentalGateState,
    private val state: SettingsState,
    private val actions: SettingsActions,
) {
    val s get() = state.s
    val playlists get() = state.playlists
    val connections get() = state.connections
    val sync get() = state.sync
    val hasPin get() = state.hasPin
    val unlocked get() = state.unlocked
    val touch get() = state.touch
    val sportProblem get() = state.sportProblem
    val sportSummary get() = state.sportSummary
    val sportStations get() = state.sportStations
    val vpn get() = state.vpn
    val combined get() = state.combined
    val combinedStats get() = state.combinedStats
    val onSetPin get() = actions.onSetPin
    val onEditAutoRefresh get() = actions.onEditAutoRefresh
    val onOpen get() = actions.onOpen
    val onConfirm get() = actions.onConfirm
    val returnFocus get() = actions.returnFocus
}

/** Rückfrage mit Titel, Erklärung und rotem Bestätigen-Knopf. */
internal class ConfirmRequest(val title: String, val message: String, val confirmText: String, val onConfirm: () -> Unit)

/** VPN-Standorte und Schalter für die Seite „Sicherheit“. */
internal class VpnSettings(
    val profiles: List<VpnProfile> = emptyList(),
    val activeId: String? = null,
    val enabled: Boolean = false,
    val alertSound: Boolean = false,
)

internal fun LazyListScope.settingsPage(page: SettingsPage, c: SettingsContext) {
    item(key = "page-header", contentType = "header") { SettingsPageHeader(page.label, page.leadFor(c.touch)) }
    when (page) {
        SettingsPage.ACCOUNTS -> accountOverviewItems(c)
        SettingsPage.PLAYBACK -> playbackItems(c)
        SettingsPage.CONTENT -> contentItems(c)
        SettingsPage.SPORT -> sportItems(c)
        SettingsPage.SECURITY -> securityItems(c)
        SettingsPage.LOOK -> lookItems(c)
        SettingsPage.SYSTEM -> systemItems(c)
    }
}

internal fun LazyListScope.settingsSubPage(sub: SettingsSub, c: SettingsContext) {
    when (sub) {
        is SettingsSub.Account -> accountDetailItems(sub.playlistId, c)
        SettingsSub.Combined -> combinedItems(c)
        SettingsSub.PlaybackAdvanced -> playbackAdvancedItems(c)
        SettingsSub.VpnLocations -> vpnLocationItems(c)
    }
}

private fun LazyListScope.systemItems(c: SettingsContext) {
    settingsGroup("backup", "Sicherung") {
        // Sicherungen enthalten alle Zugangsdaten und ersetzen beim Wiederherstellen PIN und Sperren.
        row("backup-export") {
            ActionRow(
                "Sicherung erstellen",
                { c.gate.runWithPin { c.navigator.push(Screen.Share(ShareMode.BACKUP_EXPORT)) } },
                "Zugänge, Favoriten, Fortschritt und Einstellungen aufs Handy holen",
                icon = AppIcons.Upload,
            )
        }
        row("backup-import") {
            ActionRow(
                "Sicherung wiederherstellen",
                { c.gate.runWithPin { c.navigator.push(Screen.Share(ShareMode.BACKUP_IMPORT)) } },
                "Sicherungsdatei vom Handy übertragen",
                icon = AppIcons.Download,
            )
        }
    }
    settingsGroup("storage", "Speicher") {
        row("clear-images") {
            ActionRow("Bild-Cache leeren", c.vm::clearImageCache, "Logos und Cover werden neu geladen", icon = AppIcons.Storage, chevron = false)
        }
        row("clear-epg") { ActionRow("EPG-Cache leeren", c.vm::clearEpgCache, "Programmdaten danach neu laden", icon = AppIcons.Calendar, chevron = false) }
        row("clear-history") {
            ActionRow(
                "Wiedergabeverlauf löschen",
                {
                    c.onConfirm(
                        ConfirmRequest(
                            "Verlauf löschen?",
                            "„Zuletzt gesehen“, Fortschritt bei Filmen und Folgen und die Sehgewohnheiten werden gelöscht.",
                            "Löschen",
                            c.vm::clearHistory,
                        ),
                    )
                },
                icon = AppIcons.Trash,
                danger = true,
                chevron = false,
            )
        }
    }
    settingsGroup("help", "Hilfe") {
        row("log") {
            ActionRow("Fehlerprotokoll anzeigen", { c.navigator.push(Screen.Share(ShareMode.LOG)) }, "Lässt sich aufs Handy holen", icon = AppIcons.Document)
        }
        row("supervisor-stats") {
            val stats = remember { c.vm.supervisorStats() }
            ActionRow("Wiedergabe-Wächter: Bilanz", { c.navigator.push(Screen.Share(ShareMode.LOG)) }, stats, icon = AppIcons.Shield)
        }
    }
    aboutItems()
}

/** Einleitungssatz; am Handy wird getippt statt OK gedrückt. */
internal fun SettingsPage.leadFor(touch: Boolean): String =
    if (touch && this == SettingsPage.ACCOUNTS) "Ein Tipp auf einen Zugang öffnet alles, was nur ihn betrifft." else lead

/** Statuszeile eines Bereichs in der Navigation (zeigt auf einen Blick den wichtigsten Zustand). */
internal fun SettingsPage.status(c: SettingsContext, vpnLine: String): String = when (this) {
    SettingsPage.ACCOUNTS -> when (c.playlists.size) {
        0 -> "Noch kein Zugang"
        1 -> c.playlists.first().name
        else -> "${c.playlists.size} Zugänge · ${activeName(c)}"
    }

    SettingsPage.PLAYBACK -> if (c.s.automaticOptimization) "Automatisch optimiert" else "${c.s.liveEngine.label} · Wächter ${onOff(c.s.playbackSupervisor)}"

    SettingsPage.CONTENT -> if (c.s.tmdbActive) "TMDB an" else "Kategorien, Favoriten"

    SettingsPage.SPORT -> "${c.vm.followedLeagues(c.s)} Ligen"

    SettingsPage.SECURITY -> listOf(vpnLine, if (c.hasPin) "PIN gesetzt" else "keine PIN").joinToString(" · ")

    SettingsPage.LOOK -> "${c.s.themeMode.label} · ${c.s.accent.label}"

    SettingsPage.SYSTEM -> "Version ${BuildConfig.VERSION_NAME.split('.').take(2).joinToString(".")}"
}

private fun activeName(c: SettingsContext): String {
    val active = c.s.activePlaylistId
    if (com.mg.player.data.combined.Combined.isCombined(active)) return com.mg.player.data.combined.Combined.NAME
    return c.playlists.firstOrNull { it.id == active }?.name ?: "keiner aktiv"
}

internal fun onOff(on: Boolean) = if (on) "an" else "aus"
