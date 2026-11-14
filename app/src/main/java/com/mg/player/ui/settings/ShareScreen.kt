package com.mg.player.ui.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.mg.player.data.backup.BackupCrypto
import com.mg.player.ui.common.ActionButton
import com.mg.player.ui.common.FocusSurface
import com.mg.player.ui.common.LocalNetworkPermissionEffect
import com.mg.player.ui.common.SecureContent
import com.mg.player.ui.common.TvTextField
import com.mg.player.ui.common.appViewModel
import com.mg.player.ui.common.glass
import com.mg.player.ui.common.isTouch
import com.mg.player.ui.common.logReport
import com.mg.player.ui.common.shareText
import com.mg.player.ui.navigation.LocalNavigator
import com.mg.player.ui.navigation.Screen
import com.mg.player.ui.theme.AppShapes
import com.mg.player.ui.theme.AnimatedIcon
import com.mg.player.ui.theme.AppColors
import com.mg.player.ui.theme.AppIcons
import com.mg.player.ui.theme.Dimens
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Sicherung und Fehlerprotokoll übers Handy: QR-Code scannen, Datei laden oder hochladen. */
@Composable
fun ShareScreen(mode: ShareMode) {
    // Sicherung: Kennwort und QR-Code nicht als Bildschirmfoto (das Fehlerprotokoll darf man fotografieren).
    if (mode != ShareMode.LOG) SecureContent()
    val touch = isTouch
    val vm = appViewModel(key = mode.name) { ShareViewModel(it, mode, touch) }
    val navigator = LocalNavigator.current
    val url by vm.url.collectAsStateWithLifecycle()
    val status by vm.status.collectAsStateWithLifecycle()
    val restored by vm.restored.collectAsStateWithLifecycle()
    val exportReady by vm.exportReady.collectAsStateWithLifecycle()
    val awaitingPassphrase by vm.awaitingPassphrase.collectAsStateWithLifecycle()
    LocalNetworkPermissionEffect(needed = true, deniedHint = "Ohne „Geräte in der Nähe“ erreicht das Handy den Fernseher nicht.")

    val info: @Composable () -> Unit = {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(52.dp).glass(CircleShape, raised = true), contentAlignment = Alignment.Center) {
                AnimatedIcon(
                    when (mode) {
                        ShareMode.BACKUP_EXPORT -> AppIcons.Upload
                        ShareMode.BACKUP_IMPORT -> AppIcons.Download
                        ShareMode.LOG, ShareMode.NAMES -> AppIcons.Document
                        ShareMode.VPN_IMPORT -> AppIcons.Shield
                    },
                    tint = AppColors.AccentText,
                    active = true,
                    size = 26.dp,
                )
            }
            Spacer(Modifier.width(16.dp))
            Text(mode.title, style = MaterialTheme.typography.headlineMedium)
        }
        Text(
            description(mode, exportReady, touch),
            style = MaterialTheme.typography.bodyLarge,
            color = AppColors.TextSecondary,
            modifier = Modifier.padding(top = 8.dp, bottom = 20.dp),
        )
        if (exportReady) {
            if (touch && (mode == ShareMode.BACKUP_EXPORT || mode == ShareMode.BACKUP_IMPORT)) {
                NativeBackupAction(mode, vm)
            } else {
                QrBox(vm)
                url?.let { Text(it.substringBefore("?"), modifier = Modifier.padding(top = 12.dp), style = MaterialTheme.typography.titleSmall) }
            }
        }
        // VPN: Rückmeldung rechts beim Formular (links ist unter dem QR-Code am Fernseher kein Platz).
        if (mode != ShareMode.VPN_IMPORT) {
            status?.let { Text(it, color = AppColors.AccentText, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 12.dp)) }
        }
        if (restored && mode != ShareMode.VPN_IMPORT) {
            ActionButton("Zur Startseite", onClick = {
                navigator.resetTo(Screen.Home)
            }, primary = true, icon = AppIcons.LiveTv, modifier = Modifier.padding(top = 16.dp))
        }
    }
    val form: @Composable () -> Unit = {
        when {
            mode == ShareMode.LOG || mode == ShareMode.NAMES -> LogPanel(vm)

            mode == ShareMode.BACKUP_EXPORT && !exportReady -> ExportPassphraseForm(vm)

            mode == ShareMode.BACKUP_IMPORT && awaitingPassphrase -> ImportPassphraseForm(vm)

            mode == ShareMode.VPN_IMPORT -> VpnImportForm(
                onImport = vm::importVpnText,
                status = status,
                done = restored,
                onDone = { navigator.pop() },
            )
        }
    }
    if (touch) {
        // Am Handy untereinander und scrollbar – die Tastatur braucht Platz.
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Column { info() }
            form()
        }
        return
    }
    Row(Modifier.fillMaxSize().padding(48.dp), horizontalArrangement = Arrangement.spacedBy(40.dp)) {
        Column(Modifier.weight(0.9f).fillMaxHeight(), verticalArrangement = Arrangement.Center) { info() }
        Column(Modifier.weight(1.2f).fillMaxHeight(), verticalArrangement = Arrangement.Center) { form() }
    }
}

private fun description(mode: ShareMode, exportReady: Boolean, touch: Boolean): String = when (mode) {
    ShareMode.BACKUP_EXPORT -> if (exportReady) {
        if (touch) {
            "Wähle, wo die verschlüsselte Sicherung gespeichert wird."
        } else {
            "Scanne den Code mit dem Handy und lade die verschlüsselte Sicherung herunter."
        }
    } else {
        "Die Sicherung enthält deine Zugangsdaten. Lege hier auf diesem Gerät ein Kennwort fest – es verlässt das Gerät " +
            "nie. Ohne dieses Kennwort lässt sich die Sicherung nicht wiederherstellen."
    }

    ShareMode.BACKUP_IMPORT -> if (touch) {
        "Wähle die Sicherungsdatei auf diesem Gerät aus. Das Kennwort gibst du danach ein. Playlists und " +
            "Einstellungen auf diesem Gerät werden dabei ersetzt."
    } else {
        "Scanne den Code mit dem Handy und wähle dort die Sicherungsdatei aus. Das Kennwort gibst du " +
            "danach hier ein. Playlists und Einstellungen auf diesem Gerät werden dabei ersetzt."
    }

    ShareMode.LOG -> "Scanne den Code, um das Protokoll auf dem Handy zu öffnen und weiterzuschicken."
    ShareMode.NAMES -> "Scanne den Code: Gruppen- und Sendernamen, Titel und EPG-IDs aller Zugänge – ohne Zugangsdaten und Adressen."

    ShareMode.VPN_IMPORT ->
        "Scanne den Code mit dem Handy und wähle dort die WireGuard-Datei (.conf) aus deinem VPN-Konto – " +
            "oder füge ihren Inhalt ein. Danach läuft nur GuevenPlayer durchs VPN, andere Apps bleiben unberührt."
}

/**
 * Am Handy: echter „Speichern unter“- bzw. „Öffnen“-Dialog statt QR-Code - das Gerät ist schon da,
 * ein zweites zum Scannen wird nicht gebraucht.
 */
@Composable
private fun NativeBackupAction(mode: ShareMode, vm: ShareViewModel) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var fileStatus by remember { mutableStateOf<String?>(null) }
    if (mode == ShareMode.BACKUP_EXPORT) {
        val createDoc = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
            if (uri == null) return@rememberLauncherForActivityResult
            scope.launch {
                val text = vm.exportText()
                fileStatus = if (text == null) {
                    "Sicherung konnte nicht erstellt werden."
                } else {
                    // Dateizugriff nicht im Hauptthread (Labor-Build: StrictMode beendet sonst die App).
                    val saved = withContext(Dispatchers.IO) {
                        runCatching { context.contentResolver.openOutputStream(uri)?.use { it.write(text.toByteArray(Charsets.UTF_8)) } }.isSuccess
                    }
                    if (saved) "Sicherung gespeichert ✓" else "Speichern fehlgeschlagen."
                }
            }
        }
        ActionButton("Datei speichern", onClick = { createDoc.launch(BACKUP_FILE_NAME) }, primary = true, icon = AppIcons.Upload)
    } else {
        val openDoc = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri == null) return@rememberLauncherForActivityResult
            scope.launch {
                val text = withContext(Dispatchers.IO) {
                    runCatching { context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() } }.getOrNull()
                }
                if (text.isNullOrBlank()) fileStatus = "Datei konnte nicht gelesen werden." else vm.loadPickedBackup(text)
            }
        }
        ActionButton("Datei auswählen", onClick = { openDoc.launch(arrayOf("*/*")) }, primary = true, icon = AppIcons.Download)
    }
    fileStatus?.let { Text(it, color = AppColors.AccentText, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 12.dp)) }
}

private const val BACKUP_FILE_NAME = "guevenplayer-sicherung.json"

@Composable
private fun QrBox(vm: ShareViewModel) {
    val qr by vm.qr.collectAsStateWithLifecycle()
    Box(
        Modifier.size(240.dp).clip(AppShapes.Panel).background(Color.White).padding(14.dp),
        contentAlignment = Alignment.Center,
    ) {
        val image = qr
        if (image != null) Image(image, contentDescription = "QR-Code", modifier = Modifier.fillMaxSize()) else Text("…", color = Color.Black)
    }
}

@Composable
private fun ExportPassphraseForm(vm: ShareViewModel) {
    var pass by remember { mutableStateOf("") }
    var repeat by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    val submit = { error = vm.setExportPassphrase(pass, repeat) }
    Column(Modifier.widthIn(max = 520.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        TvTextField(pass, { pass = it }, label = "Kennwort (mindestens ${BackupCrypto.MIN_PASSPHRASE} Zeichen)", password = true, imeAction = ImeAction.Next)
        if (pass.isNotEmpty()) {
            val (label, color) = when (passphraseStrength(pass)) {
                2 -> "Kennwort: stark" to AppColors.Success
                1 -> "Kennwort: mittel – länger oder mit Zahlen/Zeichen ist sicherer" to AppColors.Rating
                else -> "Kennwort: schwach – mindestens 8 Zeichen mit Zahlen empfohlen" to AppColors.Danger
            }
            Text(label, color = color, style = MaterialTheme.typography.bodySmall)
        }
        TvTextField(repeat, { repeat = it }, label = "Kennwort wiederholen", password = true, onSubmit = submit)
        error?.let { Text(it, color = AppColors.Danger, style = MaterialTheme.typography.bodyMedium) }
        ActionButton("Kennwort festlegen", onClick = submit, primary = true, icon = AppIcons.Lock)
    }
}

@Composable
private fun ImportPassphraseForm(vm: ShareViewModel) {
    val busy by vm.busy.collectAsStateWithLifecycle()
    var pass by remember { mutableStateOf("") }
    val submit = { if (pass.isNotEmpty()) vm.restorePending(pass) }
    Column(Modifier.widthIn(max = 520.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        TvTextField(pass, { pass = it }, label = "Kennwort der Sicherung", password = true, onSubmit = submit)
        ActionButton(if (busy) "Wird wiederhergestellt…" else "Wiederherstellen", onClick = submit, primary = true, icon = AppIcons.Download)
    }
}

@Composable
private fun LogPanel(vm: ShareViewModel) {
    val log by vm.log.collectAsStateWithLifecycle()
    val touch = isTouch
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    // Am Handy liegt das Panel in einer scrollbaren Seite: feste Höhe, sonst scrollt hier ein Bereich im anderen
    // (das bringt Compose zum Absturz).
    Column(if (touch) Modifier else Modifier.fillMaxHeight()) {
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            if (touch) {
                ActionButton("Teilen", onClick = {
                    scope.launch { context.shareText("GuevenPlayer – Fehlerprotokoll", logReport()) }
                }, icon = AppIcons.Upload, primary = true)
            }
            ActionButton("Aktualisieren", onClick = vm::reloadLog, icon = AppIcons.Refresh)
            ActionButton("Protokoll leeren", onClick = vm::clearLog, icon = AppIcons.Trash)
        }
        Spacer(Modifier.height(12.dp))
        Text("Neueste Einträge zuerst", style = MaterialTheme.typography.labelMedium, color = AppColors.TextMuted)
        Spacer(Modifier.height(6.dp))
        FocusSurface(onClick = {}, modifier = if (touch) Modifier.heightIn(max = 460.dp) else Modifier, focusedScale = 1f, glow = false, sheen = false) {
            Text(
                log.ifBlank { "Das Protokoll ist leer." },
                modifier = Modifier.padding(14.dp).verticalScroll(rememberScrollState()),
                fontFamily = FontFamily.Monospace,
                fontSize = 12.sp,
                color = AppColors.TextSecondary,
            )
        }
    }
}

/** 0 = schwach, 1 = mittel, 2 = stark (Länge und Zeichenarten). */
internal fun passphraseStrength(pass: String): Int {
    val kinds = listOf(pass.any { it.isLowerCase() }, pass.any { it.isUpperCase() }, pass.any { it.isDigit() }, pass.any { !it.isLetterOrDigit() })
        .count { it }
    return when {
        pass.length >= 12 && kinds >= 3 -> 2
        pass.length >= 8 && kinds >= 2 -> 1
        else -> 0
    }
}
