package com.mg.player.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.mg.player.core.util.TimeUtils
import com.mg.player.core.vpn.VpnController
import com.mg.player.core.vpn.VpnProfile
import com.mg.player.core.vpn.VpnState
import com.mg.player.core.vpn.VpnTraffic
import com.mg.player.ui.common.IconActionButton
import com.mg.player.ui.common.ListSurface
import com.mg.player.ui.common.LocalAppContainer
import com.mg.player.ui.common.glass
import com.mg.player.ui.navigation.Screen
import com.mg.player.ui.theme.AppColors
import com.mg.player.ui.theme.AppIcons
import com.mg.player.ui.theme.Dimens
import com.mg.player.ui.vpn.LocalVpnState
import com.mg.player.ui.vpn.VpnBadge
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Bereich „Sicherheit“: eingebautes VPN (nur GuevenPlayer), Kindersicherung und Datenschutz. */
internal fun LazyListScope.securityItems(c: SettingsContext) {
    val vpnSetup = { c.gate.runWithPin { c.navigator.push(Screen.Share(ShareMode.VPN_IMPORT)) } }
    val profiles = c.vpn.profiles
    settingsGroup("vpn", "VPN – nur GuevenPlayer") {
        row("vpn-status", contentType = "vpn-status") { VpnStatusPanel() }
        if (profiles.isEmpty()) {
            row("vpn-setup") {
                ActionRow("VPN einrichten", vpnSetup, "WireGuard-Datei (z. B. Surfshark) per QR-Code vom Handy", icon = AppIcons.Shield)
            }
        } else {
            row("vpn-enabled") {
                val vpn = LocalAppContainer.current.vpn
                ToggleRow(
                    "VPN immer an",
                    c.vpn.enabled,
                    { vpn.setEnabled(!c.vpn.enabled) },
                    "Verbindet beim Start, nach dem Einschalten und bei Netzwechsel",
                    AppIcons.Shield,
                )
            }
            row("vpn-locations", modifier = c.returnFocus("vpn-locations")) {
                val active = profiles.firstOrNull { it.id == c.vpn.activeId } ?: profiles.first()
                ActionRow(
                    "Standorte",
                    { c.onOpen(SettingsSub.VpnLocations, "vpn-locations") },
                    "${profiles.size} eingerichtet",
                    trailing = active.name,
                    icon = AppIcons.Globe,
                )
            }
        }
        row("surfshark") {
            val surfshark = LocalAppContainer.current.surfshark
            val account by surfshark.account.collectAsStateWithLifecycle()
            LaunchedEffect(surfshark) { withContext(Dispatchers.Default) { surfshark.refresh() } }
            ActionRow(
                "Surfshark-Server wählen",
                { c.navigator.push(Screen.Surfshark) },
                if (account != null) "Alle Server – Wechsel mit einem Klick" else "Einmal eine Surfshark-Datei übertragen",
                icon = AppIcons.Speed,
            )
        }
        row("vpn-check") { VpnCheckRow() }
    }
    parentalItems(c)
    privacyItems(c)
}

/** Kindersicherung und App-Sperre (gleiche PIN). */
private fun LazyListScope.parentalItems(c: SettingsContext) {
    settingsGroup("pin", "Kindersicherung") {
        row("set-pin") {
            ActionRow(
                if (c.hasPin) "PIN ändern" else "PIN festlegen",
                { c.gate.runWithPin(c.onSetPin) },
                "4-stellige PIN für gesperrte Kategorien und die App-Sperre",
                icon = AppIcons.Lock,
            )
        }
        if (c.hasPin && c.unlocked) {
            row("lock-again") {
                ActionRow(
                    "Gesperrte Kategorien wieder sperren",
                    c.vm::lockAgain,
                    "Seit der letzten PIN-Eingabe freigeschaltet",
                    icon = AppIcons.Shield,
                    chevron = false,
                )
            }
        }
        row("app-lock") {
            ToggleRow(
                "App-Sperre",
                c.s.appLock,
                {
                    when {
                        c.s.appLock -> c.gate.runWithPin { c.vm.update { it.copy(appLock = false) } }
                        c.hasPin -> c.vm.update { it.copy(appLock = true) }
                        else -> c.onSetPin()
                    }
                },
                if (c.hasPin) "PIN beim Öffnen; Inhalt in „Zuletzt verwendet“ verdeckt" else "Zuerst eine PIN festlegen",
                AppIcons.Lock,
            )
        }
        if (c.hasPin) {
            row("remove-pin") {
                ActionRow(
                    "PIN entfernen",
                    { c.gate.runWithPin { c.vm.removePin() } },
                    "Gesperrte Kategorien sind danach frei zugänglich",
                    icon = AppIcons.Close,
                    danger = true,
                    chevron = false,
                )
            }
        }
    }
}

private fun LazyListScope.privacyItems(c: SettingsContext) {
    settingsGroup("privacy", "Datenschutz") {
        row("online-extras") {
            ToggleRow(
                "Online-Zusatzinfos",
                c.s.onlineExtras,
                { c.vm.update { it.copy(onlineExtras = !it.onlineExtras) } },
                "Sport-Spielpläne und Film-Infos aus dem Internet. Aus = keine Anfragen an diese Dienste",
                AppIcons.Globe,
            )
        }
        if (c.s.onlineExtras) {
            row("sport-guide") {
                val store = LocalAppContainer.current.sport.guideStore
                ToggleRow(
                    "Sport-Programm aus dem Internet",
                    c.s.sportGuide,
                    {
                        if (c.s.sportGuide) store.clear()
                        c.vm.update { it.copy(sportGuide = !it.sportGuide) }
                    },
                    "Öffentliches Programm der Sportsender (epgshare01), einmal am Tag",
                    AppIcons.Sport,
                )
            }
        }
        row("doh") {
            ToggleRow(
                "Verschlüsseltes DNS",
                c.s.dohFallback,
                { c.vm.update { it.copy(dohFallback = !it.dohFallback) } },
                "Solange das VPN nicht verbunden ist (DNS über HTTPS)",
                AppIcons.Lock,
            )
        }
        row("no-history") {
            ToggleRow(
                "Ohne Verlauf",
                c.s.noHistory,
                { c.vm.update { it.copy(noHistory = !it.noHistory) } },
                "Kein „Zuletzt gesehen“, kein Fortschritt, keine Aktivitäten im Protokoll",
                AppIcons.EyeOff,
            )
        }
    }
}

/** Unterseite „VPN-Standorte“: wählen, löschen, hinzufügen; Neu verbinden und Ton bei Trennung. */
internal fun LazyListScope.vpnLocationItems(c: SettingsContext) {
    val vpnSetup = { c.gate.runWithPin { c.navigator.push(Screen.Share(ShareMode.VPN_IMPORT)) } }
    val profiles = c.vpn.profiles
    item(key = "vpn-header", contentType = "header") {
        SettingsPageHeader("VPN-Standorte", "OK wählt einen Standort", breadcrumb = SettingsPage.SECURITY.label.takeUnless { c.touch })
    }
    settingsGroup("vpn-profiles") {
        profiles.forEach { profile ->
            row("vpn-${profile.id}", contentType = "vpn-profile") {
                val vpn = LocalAppContainer.current.vpn
                VpnProfileRow(profile, active = profile.id == (c.vpn.activeId ?: profiles.first().id), vpn)
            }
        }
        row("vpn-add") {
            ActionRow("Standort hinzufügen", vpnSetup, "Weitere .conf-Datei übertragen", icon = AppIcons.Plus)
        }
    }
    if (profiles.isNotEmpty()) {
        settingsGroup("vpn-more") {
            row("vpn-reconnect") {
                val vpn = LocalAppContainer.current.vpn
                ActionRow("Neu verbinden", {
                    if (c.vpn.enabled) vpn.restart() else vpn.setEnabled(true)
                }, "Fragt bei Bedarf erneut nach der VPN-Erlaubnis", icon = AppIcons.Refresh, chevron = false)
            }
            row("vpn-sound") {
                val store = LocalAppContainer.current.vpnStore
                ToggleRow("Ton/Vibration bei Trennung", c.vpn.alertSound, {
                    store.setAlertSound(!c.vpn.alertSound)
                }, "Zusätzlich zum roten Band und der Benachrichtigung", AppIcons.Audio)
            }
        }
    }
}

/** Zustand mit „verbunden seit“, Datenmenge und letztem Abbruch. */
@Composable
private fun VpnStatusPanel() {
    val container = LocalAppContainer.current
    val state = LocalVpnState.current
    val lastDrop by container.vpnStore.lastDrop.collectAsStateWithLifecycle()
    val traffic by produceState<VpnTraffic?>(null, state) {
        // Statistik aus der nativen Bibliothek im Hintergrund lesen (nie im Hauptthread).
        while (state is VpnState.Connected) {
            value = withContext(Dispatchers.IO) { container.vpn.traffic() }
            delay(2_000)
        }
    }
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        VpnBadge(state)
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(statusLine(state), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            if (state is VpnState.Connected) {
                traffic?.let {
                    Text(
                        "Empfangen ${TimeUtils.formatBytes(it.rxBytes)} · gesendet ${TimeUtils.formatBytes(it.txBytes)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = AppColors.TextSecondary,
                    )
                }
            }
            lastDrop?.let {
                Text(
                    "Letzter Abbruch: ${TimeUtils.formatDate(it.atMs)} – ${it.reason}",
                    style = MaterialTheme.typography.bodySmall,
                    color = AppColors.TextMuted,
                )
            }
        }
    }
}

private fun statusLine(state: VpnState): String = when (state) {
    is VpnState.Connected -> "Verbunden mit ${state.profile} seit ${TimeUtils.formatClock(state.sinceMs)} Uhr"
    is VpnState.Connecting -> "Verbindet mit ${state.profile} …"
    is VpnState.Disconnected -> "Getrennt seit ${TimeUtils.formatClock(state.sinceMs)} Uhr – ${state.reason.text}"
    VpnState.Off -> "VPN aus – Verbindungen ungeschützt"
    VpnState.Unsupported -> "Das eingebaute VPN braucht Android 7 oder neuer"
}

/** Ein Standort: OK macht ihn aktiv, rechts „Löschen“ mit Rückfrage. */
@Composable
private fun VpnProfileRow(profile: VpnProfile, active: Boolean, vpn: VpnController) {
    val store = LocalAppContainer.current.vpnStore
    var confirmDelete by remember(profile.id) { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        ListSurface(
            onClick = { if (!active) vpn.select(profile.id) },
            modifier = Modifier.weight(1f),
            containerColor = androidx.compose.ui.graphics.Color.Transparent,
            focusedScale = 1.01f,
        ) {
            Row(Modifier.padding(horizontal = 12.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(profile.country ?: "–", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, modifier = Modifier.width(48.dp))
                Text(profile.name, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f), maxLines = 1)
                if (active) SettingsChip("Aktiv", ChipTone.ACCENT)
            }
        }
        IconActionButton(
            icon = AppIcons.Trash,
            label = if (confirmDelete) "Wirklich löschen?" else "Löschen",
            onClick = {
                if (confirmDelete) {
                    store.remove(profile.id)
                    if (store.profiles.value.isEmpty()) {
                        vpn.stop()
                    } else if (active) {
                        vpn.restart()
                    }
                } else {
                    confirmDelete = true
                }
            },
            iconTint = if (confirmDelete) AppColors.Danger else null,
            modifier = Modifier.padding(start = 8.dp, end = 4.dp),
        )
    }
}

/** „Verbindung prüfen“: zeigt die öffentliche IP, unter der Server die App gerade sehen. */
@Composable
private fun VpnCheckRow() {
    val container = LocalAppContainer.current
    val state = LocalVpnState.current
    val scope = rememberCoroutineScope()
    var result by remember { mutableStateOf<String?>(null) }
    ActionRow(
        "Verbindung prüfen",
        {
            result = "Wird geprüft …"
            scope.launch {
                val ip = container.publicIp()
                result = when {
                    ip == null -> "Prüfung fehlgeschlagen – keine Verbindung zum Prüfdienst"
                    state is VpnState.Connected -> "Öffentliche IP: $ip – über das VPN (${state.profile})"
                    else -> "Öffentliche IP: $ip – ohne VPN, Anbieter sehen deine echte Adresse"
                }
            }
        },
        result ?: "Unter welcher IP-Adresse Anbieter die App gerade sehen",
        icon = AppIcons.Wifi,
        chevron = false,
    )
}
