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
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.ui.unit.dp
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
import com.mg.player.ui.theme.AppShapes
import com.mg.player.ui.theme.AppColors
import com.mg.player.ui.theme.AppIcons
import com.mg.player.ui.theme.Dimens

/** Zugang bearbeiten: links QR-Code fürs Handy (Fernseher), rechts das ausgefüllte Formular. */
@Composable
fun EditPlaylistScreen(playlistId: Long, onDone: () -> Unit) {
    SecureContent()
    val vm = appViewModel(key = "edit-$playlistId") { EditPlaylistViewModel(it, playlistId) }
    val state by vm.state.collectAsStateWithLifecycle()
    LocalNetworkPermissionEffect(needed = true, deniedHint = "Ohne „Geräte in der Nähe“ erreicht das Handy den Fernseher nicht – Daten bitte hier eingeben.")
    LaunchedEffect(state.done) { if (state.done) onDone() }
    if (!state.loaded) {
        CenteredMessage(title = "Lade Zugang…", loading = true)
        return
    }
    if (isTouch) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 20.dp)) {
            Text("Zugang bearbeiten", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.padding(bottom = 16.dp))
            Column(Modifier.fillMaxWidth().glass(AppShapes.Panel).padding(20.dp)) { EditForm(state, vm) }
        }
        return
    }
    Row(Modifier.fillMaxSize().padding(horizontal = 64.dp, vertical = 40.dp), horizontalArrangement = Arrangement.spacedBy(48.dp)) {
        Column(Modifier.weight(0.9f).fillMaxHeight(), verticalArrangement = Arrangement.Center) {
            Text("Zugang bearbeiten", style = MaterialTheme.typography.displaySmall)
            Text(
                "Favoriten, Verlauf, Sperren und Einstellungen bleiben erhalten. Neue Zugangsdaten werden vor dem Speichern geprüft.",
                style = MaterialTheme.typography.bodyLarge,
                color = AppColors.TextSecondary,
                modifier = Modifier.padding(top = 6.dp, bottom = 16.dp),
            )
            Box(
                Modifier.size(180.dp).clip(AppShapes.Panel).background(Color.White).padding(12.dp),
                contentAlignment = Alignment.Center,
            ) {
                val image = state.qrImage
                if (image != null) {
                    Image(image, "QR-Code zum Bearbeiten am Handy", Modifier.fillMaxSize())
                } else {
                    Text("Kein Netzwerk gefunden", color = Color.Black)
                }
            }
            Text(
                "Mit dem Handy scannen und die neuen Daten dort eingeben – sie landen hier im Formular.",
                style = MaterialTheme.typography.bodySmall,
                color = AppColors.TextMuted,
                modifier = Modifier.padding(top = 10.dp),
            )
        }
        Column(
            Modifier.weight(1.1f).fillMaxHeight().glass(AppShapes.Panel).verticalScroll(rememberScrollState())
                .padding(horizontal = 28.dp, vertical = 24.dp),
        ) { EditForm(state, vm) }
    }
}

@Composable
private fun EditForm(state: EditUiState, vm: EditPlaylistViewModel) {
    val f = state.form
    val field = Modifier.fillMaxWidth().padding(bottom = 14.dp)
    Text(if (state.type == PlaylistType.XTREAM) "Xtream Codes" else "M3U-Link", style = MaterialTheme.typography.headlineSmall)
    Spacer(Modifier.height(14.dp))
    TvTextField(f.name, { v -> vm.update { it.copy(name = v) } }, "Name", field, imeAction = ImeAction.Next)
    if (state.type == PlaylistType.XTREAM) {
        TvTextField(f.server, { v -> vm.update { it.copy(server = v) } }, "Server-URL", field, keyboardType = KeyboardType.Uri, imeAction = ImeAction.Next)
        Warning(f.server)
        TvTextField(f.username, { v -> vm.update { it.copy(username = v) } }, "Benutzername", field, imeAction = ImeAction.Next)
        TvTextField(
            f.password,
            { v -> vm.update { it.copy(password = v) } },
            "Neues Passwort (leer = unverändert)",
            field,
            password = true,
            keyboardType = KeyboardType.Password,
            imeAction = ImeAction.Next,
        )
    } else {
        TvTextField(
            f.m3uUrl,
            { v -> vm.update { it.copy(m3uUrl = v) } },
            "Neue M3U-URL (leer = unverändert)",
            field,
            placeholder = state.maskedM3u,
            keyboardType = KeyboardType.Uri,
            imeAction = ImeAction.Next,
        )
        Warning(f.m3uUrl)
    }
    TvTextField(f.epgUrl, { v -> vm.update { it.copy(epgUrl = v) } }, "EPG-URL (optional)", field, keyboardType = KeyboardType.Uri, imeAction = ImeAction.Next)
    Warning(f.epgUrl)
    TvTextField(
        f.autoRefreshDays,
        { v -> vm.update { it.copy(autoRefreshDays = v.filter(Char::isDigit).take(3)) } },
        "Automatisch aktualisieren alle … Tage (leer oder 0 = aus)",
        field,
        keyboardType = KeyboardType.Number,
    )
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        ChoiceChip("HTTP zulassen", f.allowInsecureHttp, icon = AppIcons.Shield, onClick = { vm.update { it.copy(allowInsecureHttp = !it.allowInsecureHttp) } })
        Text(
            "Nur aktivieren, wenn dein Anbieter HTTP braucht; Zugangsdaten können im Netzwerk mitgelesen werden.",
            color = if (f.allowInsecureHttp) AppColors.Danger else AppColors.TextSecondary,
            style = MaterialTheme.typography.bodySmall,
        )
    }
    state.error?.let { Text(it, color = AppColors.Danger, modifier = Modifier.padding(vertical = 8.dp)) }
    Row(horizontalArrangement = Arrangement.spacedBy(14.dp), modifier = Modifier.padding(top = 6.dp)) {
        ActionButton(if (state.saving) "Prüfe…" else "Prüfen & speichern", onClick = { vm.save() }, primary = true, icon = AppIcons.Check)
        if (state.canForce) ActionButton("Trotzdem speichern", onClick = { vm.save(force = true) }, icon = AppIcons.Download)
    }
}

@Composable
private fun Warning(url: String) {
    if (!isPlainHttpUrl(url)) return
    Text(PLAIN_HTTP_WARNING, color = AppColors.Danger, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(bottom = 12.dp))
}
