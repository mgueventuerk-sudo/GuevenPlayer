package com.mg.player.ui.settings

import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.mg.player.ui.common.ActionButton
import com.mg.player.ui.common.isTouch
import com.mg.player.ui.theme.AppColors
import com.mg.player.ui.theme.AppIcons
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val MAX_CONF_BYTES = 16_000

/**
 * VPN einrichten: Am Fernseher genügt der QR-Code (Upload vom Handy). Am Handy zusätzlich direkt eine Datei
 * wählen oder aus der Zwischenablage einfügen – der Inhalt (mit privatem Schlüssel) erscheint dabei nie auf dem Bildschirm.
 */
@Composable
internal fun VpnImportForm(onImport: (text: String, fileName: String?) -> Unit, status: String?, done: Boolean, onDone: () -> Unit) {
    val context = LocalContext.current
    var hint by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            // Datei und Name über den Dateianbieter lesen – nicht im Hauptthread (Labor-Build: StrictMode beendet sonst die App).
            val (text, name) = withContext(Dispatchers.IO) { readSmallText(context, uri) to displayName(context, uri) }
            if (text == null) hint = "Die Datei ließ sich nicht lesen." else onImport(text, name)
        }
    }
    Column(Modifier.widthIn(max = 560.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("So geht's mit Surfshark", style = MaterialTheme.typography.titleMedium)
        listOf(
            "1. Im Surfshark-Konto: VPN → Manuelle Einrichtung → Router/Desktop → WireGuard.",
            "2. „Schlüsselpaar erzeugen“ – nur für GuevenPlayer verwenden.",
            "3. Standort wählen (z. B. Deutschland – Frankfurt) und die .conf-Datei herunterladen.",
            "4. Hier übertragen und die VPN-Erlaubnis einmal mit OK bestätigen.",
        ).forEach { Text(it, style = MaterialTheme.typography.bodyMedium, color = AppColors.TextSecondary) }
        Text(
            "Weitere Standorte (z. B. Niederlande) lassen sich genauso hinzufügen und später umschalten.",
            style = MaterialTheme.typography.bodySmall,
            color = AppColors.TextMuted,
        )
        if (isTouch) {
            ActionButton("Datei wählen (.conf)", onClick = { picker.launch(arrayOf("*/*")) }, primary = true, icon = AppIcons.Document)
            ActionButton(
                "Aus Zwischenablage einfügen",
                onClick = {
                    val text = clipboardText(context)
                    if (text.isNullOrBlank()) hint = "Die Zwischenablage ist leer." else onImport(text, null)
                },
                icon = AppIcons.Download,
            )
        }
        hint?.let { Text(it, color = AppColors.Danger, style = MaterialTheme.typography.bodyMedium) }
        status?.let { Text(it, color = AppColors.AccentText, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodyLarge) }
        if (done) ActionButton("Fertig", onClick = onDone, primary = true, icon = AppIcons.Check)
    }
}

private fun readSmallText(context: Context, uri: Uri): String? = runCatching {
    context.contentResolver.openInputStream(uri)?.use { input ->
        // Höchstens 16 KB lesen – eine falsch gewählte große Datei soll nicht den Speicher füllen.
        val buffer = ByteArray(MAX_CONF_BYTES + 1)
        var total = 0
        while (total < buffer.size) {
            val read = input.read(buffer, total, buffer.size - total)
            if (read < 0) break
            total += read
        }
        if (total > MAX_CONF_BYTES) null else String(buffer, 0, total, Charsets.UTF_8)
    }
}.getOrNull()

private fun displayName(context: Context, uri: Uri): String? = runCatching {
    context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
        if (cursor.moveToFirst()) cursor.getString(0) else null
    }
}.getOrNull()

private fun clipboardText(context: Context): String? = runCatching {
    val clip = context.getSystemService(ClipboardManager::class.java)?.primaryClip ?: return@runCatching null
    if (clip.itemCount == 0) null else clip.getItemAt(0).coerceToText(context)?.toString()
}.getOrNull()
