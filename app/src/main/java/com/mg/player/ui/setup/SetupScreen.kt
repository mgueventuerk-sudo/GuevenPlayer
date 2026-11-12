package com.mg.player.ui.setup

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.mg.player.domain.model.PlaylistType
import com.mg.player.ui.accounts.PLAIN_HTTP_WARNING
import com.mg.player.ui.accounts.isPlainHttpUrl
import com.mg.player.ui.common.ActionButton
import com.mg.player.ui.common.CenteredMessage
import com.mg.player.ui.common.ChoiceChip
import com.mg.player.ui.common.LocalNetworkPermissionEffect
import com.mg.player.ui.common.SecureContent
import com.mg.player.ui.common.TvTextField
import com.mg.player.ui.common.appViewModel
import com.mg.player.ui.common.glass
import com.mg.player.ui.common.isTouch
import com.mg.player.ui.navigation.LocalNavigator
import com.mg.player.ui.navigation.Screen
import com.mg.player.ui.settings.ShareMode
import com.mg.player.ui.theme.AppShapes
import com.mg.player.ui.theme.AnimatedIcon
import com.mg.player.ui.theme.AppColors
import com.mg.player.ui.theme.AppIcons
import com.mg.player.ui.theme.BrandLogo
import com.mg.player.ui.theme.Dimens

/** Kinderleichte Einrichtung: QR-Code fürs Handy links, manuelle Eingabe rechts. */
@Composable
fun SetupScreen(onFinished: () -> Unit, firstSetup: Boolean = true) {
    SecureContent()
    val vm = appViewModel { SetupViewModel(it) }
    val state by vm.state.collectAsStateWithLifecycle()
    // QR-Einrichtung: Das Handy verbindet sich über das Heimnetz mit dem Fernseher.
    LocalNetworkPermissionEffect(
        needed = true,
        deniedHint = "Ohne „Geräte in der Nähe“ erreicht das Handy den Fernseher nicht – Daten bitte hier eingeben.",
    )

    LaunchedEffect(state.phase) { if (state.phase == SetupPhase.DONE) onFinished() }

    when (state.phase) {
        SetupPhase.IMPORTING, SetupPhase.DONE -> CenteredMessage(
            title = state.progressText.ifBlank { "Lade Playlist…" },
            subtitle = if (state.progressCount > 0) "${state.progressCount} Einträge verarbeitet" else "Das kann bei großen Listen einen Moment dauern.",
            loading = true,
        )

        SetupPhase.ERROR -> Column(
            Modifier.fillMaxSize().padding(horizontal = 24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(Modifier.size(72.dp).glass(CircleShape), contentAlignment = Alignment.Center) {
                AnimatedIcon(AppIcons.Wifi, tint = AppColors.Danger, active = true, size = 34.dp)
            }
            Spacer(Modifier.height(18.dp))
            Text("Laden fehlgeschlagen", style = MaterialTheme.typography.headlineSmall)
            Text(
                state.error.orEmpty(),
                color = AppColors.TextSecondary,
                textAlign = TextAlign.Center,
                modifier = Modifier.widthIn(max = 560.dp).padding(top = 8.dp, bottom = 24.dp),
            )
            if (isTouch) {
                // Hochformat: zwei Knöpfe nebeneinander passen nicht in 412 dp – untereinander, volle Breite.
                Column(Modifier.widthIn(max = 420.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    ActionButton("Erneut versuchen", onClick = vm::retry, icon = AppIcons.Refresh, primary = true, modifier = Modifier.fillMaxWidth())
                    ActionButton("Daten ändern", onClick = vm::backToForm, icon = AppIcons.User, modifier = Modifier.fillMaxWidth())
                }
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    ActionButton("Erneut versuchen", onClick = vm::retry, icon = AppIcons.Refresh, primary = true)
                    ActionButton("Daten ändern", onClick = vm::backToForm, icon = AppIcons.User)
                }
            }
        }

        SetupPhase.FORM -> SetupFormContent(state, vm, firstSetup)
    }
}

@Composable
private fun SetupFormContent(state: SetupUiState, vm: SetupViewModel, firstSetup: Boolean) {
    val navigator = LocalNavigator.current
    if (isTouch) {
        // Am Handy gibt es keinen QR-Code (man hält das Gerät ja schon in der Hand) – nur das Formular.
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 20.dp),
        ) {
            BrandLogo(markSize = 36.dp, fontSize = 22.sp, modifier = Modifier.padding(bottom = 14.dp))
            Text(if (firstSetup) "Willkommen" else "Weiteren Zugang hinzufügen", style = MaterialTheme.typography.headlineMedium)
            Text(
                "Gib die Zugangsdaten deines Anbieters ein – Xtream Codes oder ein M3U-Link.",
                style = MaterialTheme.typography.bodyLarge,
                color = AppColors.TextSecondary,
                modifier = Modifier.padding(top = 6.dp, bottom = 16.dp),
            )
            if (firstSetup) RestoreBackupOption(onClick = { navigator.push(Screen.Share(ShareMode.BACKUP_IMPORT)) })
            Column(Modifier.fillMaxWidth().glass(AppShapes.Panel).padding(20.dp)) { ManualForm(state, vm) }
        }
        return
    }
    Row(Modifier.fillMaxSize().padding(horizontal = 64.dp, vertical = 40.dp), horizontalArrangement = Arrangement.spacedBy(48.dp)) {
        Column(Modifier.weight(0.9f).fillMaxHeight(), verticalArrangement = Arrangement.Center) {
            BrandLogo(markSize = 40.dp, fontSize = 24.sp, modifier = Modifier.padding(bottom = 16.dp))
            Text(
                if (firstSetup) "Willkommen" else "Weiteren Zugang hinzufügen",
                style = MaterialTheme.typography.displaySmall,
            )
            Text(
                "Scanne den Code mit deinem Handy und gib deine Zugangsdaten bequem dort ein.",
                style = MaterialTheme.typography.bodyLarge,
                color = AppColors.TextSecondary,
                modifier = Modifier.padding(top = 6.dp, bottom = 16.dp),
            )
            if (firstSetup) RestoreBackupOption(onClick = { navigator.push(Screen.Share(ShareMode.BACKUP_IMPORT)) })
            Box(
                Modifier
                    .size(200.dp)
                    .clip(AppShapes.Panel)
                    .background(Color.White)
                    .padding(14.dp),
                contentAlignment = Alignment.Center,
            ) {
                val image = state.qrImage
                if (image != null) {
                    Image(image, contentDescription = "QR-Code für die Einrichtung", modifier = Modifier.fillMaxSize())
                } else {
                    Text("Kein Netzwerk gefunden", color = Color.Black)
                }
            }
            state.qrUrl?.let {
                Text(
                    "Oder im Handy-Browser öffnen:",
                    style = MaterialTheme.typography.labelLarge,
                    color = AppColors.TextMuted,
                    modifier = Modifier.padding(top = 18.dp),
                )
                Text(it.substringBefore("?"), style = MaterialTheme.typography.titleMedium, color = AppColors.TextPrimary)
            }
            Text(
                "Handy und Fernseher müssen im selben WLAN sein.",
                style = MaterialTheme.typography.bodySmall,
                color = AppColors.TextMuted,
                modifier = Modifier.padding(top = 8.dp),
            )
        }

        Column(
            Modifier
                .weight(1.1f)
                .fillMaxHeight()
                .glass(AppShapes.Panel)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 28.dp, vertical = 24.dp),
        ) {
            ManualForm(state, vm)
        }
    }
}

/**
 * Schon eine Sicherung vorhanden (z. B. Neuinstallation)? Dann gleich dort weiter statt erst Zugangsdaten
 * einzutippen, die die Sicherung ohnehin gleich ersetzt.
 */
@Composable
private fun RestoreBackupOption(onClick: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 16.dp)) {
        Text(
            "Schon eine Sicherung? ",
            style = MaterialTheme.typography.bodyLarge,
            color = AppColors.TextSecondary,
        )
        ActionButton("Wiederherstellen", onClick = onClick, icon = AppIcons.Download, compact = true)
    }
}

/** Eingabe von Hand: Typ, Name, Server/Benutzer/Passwort bzw. M3U-Link, EPG. */
@Composable
private fun ManualForm(state: SetupUiState, vm: SetupViewModel) {
    Text("Manuell eingeben", style = MaterialTheme.typography.headlineSmall)
    Spacer(Modifier.height(16.dp))
    val form = state.form
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        ChoiceChip("Xtream Codes", form.type == PlaylistType.XTREAM, icon = AppIcons.User, onClick = { vm.updateForm { it.copy(type = PlaylistType.XTREAM) } })
        ChoiceChip("M3U-Link", form.type == PlaylistType.M3U, icon = AppIcons.ListIcon, onClick = { vm.updateForm { it.copy(type = PlaylistType.M3U) } })
    }
    Spacer(Modifier.height(16.dp))
    val fieldModifier = Modifier.fillMaxWidth().padding(bottom = 14.dp)
    TvTextField(
        form.name,
        { v -> vm.updateForm { it.copy(name = v) } },
        "Name (z. B. Anbieter 2)",
        fieldModifier,
        imeAction = ImeAction.Next,
    )
    if (form.type == PlaylistType.XTREAM) {
        TvTextField(
            form.server,
            { v -> vm.updateForm { it.copy(server = v) } },
            "Server-URL",
            fieldModifier,
            placeholder = "https://anbieter.tv:8080",
            keyboardType = KeyboardType.Uri,
            imeAction = ImeAction.Next,
        )
        HttpWarning(form.server)
        TvTextField(form.username, { v -> vm.updateForm { it.copy(username = v) } }, "Benutzername", fieldModifier, imeAction = ImeAction.Next)
        TvTextField(
            form.password,
            { v -> vm.updateForm { it.copy(password = v) } },
            "Passwort",
            fieldModifier,
            password = true,
            keyboardType = KeyboardType.Password,
            imeAction = ImeAction.Next,
        )
    } else {
        TvTextField(
            form.m3uUrl,
            { v -> vm.updateForm { it.copy(m3uUrl = v) } },
            "M3U-URL",
            fieldModifier,
            placeholder = "https://…/get.php?username=…",
            keyboardType = KeyboardType.Uri,
            imeAction = ImeAction.Next,
        )
        HttpWarning(form.m3uUrl)
    }
    TvTextField(
        form.epgUrl,
        { v -> vm.updateForm { it.copy(epgUrl = v) } },
        "EPG-URL (optional)",
        fieldModifier,
        keyboardType = KeyboardType.Uri,
    )
    HttpWarning(form.epgUrl)
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        ChoiceChip(
            "HTTP zulassen",
            form.allowInsecureHttp,
            icon = AppIcons.Shield,
            onClick = { vm.updateForm { it.copy(allowInsecureHttp = !it.allowInsecureHttp) } },
        )
        Text(
            "Nur aktivieren, wenn dein IPTV-Anbieter HTTP braucht; Zugangsdaten können im Netzwerk mitgelesen werden.",
            color = if (form.allowInsecureHttp) AppColors.Danger else AppColors.TextSecondary,
            style = MaterialTheme.typography.bodySmall,
        )
    }
    state.error?.let { Text(it, color = AppColors.Danger, modifier = Modifier.padding(bottom = 10.dp)) }
    ActionButton("Speichern & laden", onClick = vm::submitForm, primary = true, icon = AppIcons.Download, modifier = Modifier.padding(top = 6.dp))
}

/** Warnung direkt unter der Adresse, sobald sie mit http:// beginnt. */
@Composable
private fun HttpWarning(url: String) {
    if (!isPlainHttpUrl(url)) return
    Text(PLAIN_HTTP_WARNING, color = AppColors.Danger, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(bottom = 12.dp))
}
