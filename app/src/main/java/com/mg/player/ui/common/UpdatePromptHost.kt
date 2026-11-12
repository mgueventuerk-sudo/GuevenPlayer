package com.mg.player.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.mg.player.core.di.AppContainer
import com.mg.player.data.update.AppUpdate
import com.mg.player.data.update.AppUpdater
import com.mg.player.data.update.UpdateStatus
import com.mg.player.ui.theme.AppColors
import com.mg.player.ui.theme.AppIcons
import com.mg.player.ui.theme.AppShapes
import com.mg.player.ui.theme.StaticIcon
import kotlinx.coroutines.launch

/**
 * Hinweis beim Start (ab 3.51): Mit eingetragenem GitHub-Schlüssel sieht die App höchstens alle 6 Stunden nach einem
 * Update. Gibt es eins, fragt sie einmal: „Installieren“ lädt die APK und öffnet den Android-Installer.
 *
 * Ab 3.58: Danach bleibt an genau dieser Stelle eine kleine Karte mit Fortschritt stehen (Prozent, dann „geladen“),
 * statt zu verschwinden – vorher stand im Dialog nur noch „Fortschritt unter Einstellungen“, ohne dass dort je
 * jemand nachsah. Die Karte blockiert die Oberfläche nicht (anders als der Installations-Dialog) und lässt sich
 * über „Ausblenden“ wegklicken, ohne den Download selbst abzubrechen.
 */
@Composable
fun UpdatePromptHost(container: AppContainer) {
    val updater = container.appUpdater
    val status by updater.status.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    LaunchedEffect(Unit) {
        updater.checkIfDue(container.settingsRepository.current().updateToken)
    }
    when (val s = status) {
        is UpdateStatus.Available -> UpdateAvailableDialog(container, updater, s.update)

        is UpdateStatus.Downloading -> UpdateProgressCard(updater, s.update.versionCode, "Wird geladen … ${s.percent} %", s.percent / PERCENT_MAX)

        is UpdateStatus.ReadyToInstall -> UpdateProgressCard(updater, s.update.versionCode, "Update ist geladen", fraction = 1f) {
            ActionButton(
                "Installer öffnen",
                onClick = { scope.launch { updater.startDownload(container.settingsRepository.current().updateToken, s.update) } },
                compact = true,
            )
        }

        else -> Unit
    }
}

@Composable
private fun UpdateAvailableDialog(container: AppContainer, updater: AppUpdater, update: AppUpdate) {
    val scope = rememberCoroutineScope()
    // Im Updater gemerkt: Der Hinweis verlässt die Komposition während der Wiedergabe – ein lokaler Zustand ginge verloren.
    var dismissed by remember { mutableStateOf(updater.dismissedVersion) }
    if (dismissed == update.versionCode) return
    ConfirmDialog(
        title = "Update verfügbar",
        confirmText = "Installieren",
        dismissText = "Später",
        onConfirm = {
            updater.dismissedVersion = update.versionCode
            dismissed = update.versionCode
            scope.launch { updater.startDownload(container.settingsRepository.current().updateToken, update) }
        },
        onDismiss = {
            updater.dismissedVersion = update.versionCode
            dismissed = update.versionCode
        },
        message = buildString {
            append("Version ${update.versionName} ist da.")
            update.notes?.let { append("\n\n").append(it) }
            append(
                "\n\nDie App lädt sie herunter und öffnet den Installer – dort einmal „Installieren“ bestätigen. " +
                    "Der Fortschritt bleibt danach an dieser Stelle sichtbar.",
            )
        },
    )
}

/**
 * Nicht blockierende Karte: Fortschritt eines laufenden oder abgeschlossenen Downloads. [versionCode] bestimmt,
 * wofür „Ausblenden“ gilt – ein neuer Download-Versuch (startDownload()) setzt es zurück und zeigt die Karte wieder.
 */
@Composable
private fun UpdateProgressCard(updater: AppUpdater, versionCode: Int, text: String, fraction: Float, action: (@Composable () -> Unit)? = null) {
    var hiddenFor by remember { mutableStateOf(updater.hiddenProgressFor) }
    if (hiddenFor == versionCode) return
    Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.BottomCenter) {
        Row(
            Modifier
                .widthIn(max = 420.dp)
                .glass(AppShapes.Panel, fill = AppColors.Panel)
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(text, style = MaterialTheme.typography.bodyMedium, maxLines = 1)
                if (fraction < 1f) {
                    Spacer(Modifier.height(8.dp))
                    Box(Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)).background(AppColors.ProgressTrack)) {
                        Box(Modifier.fillMaxWidth(fraction).height(4.dp).clip(RoundedCornerShape(2.dp)).background(AppColors.Accent))
                    }
                }
            }
            if (action != null) {
                Spacer(Modifier.width(12.dp))
                action()
            }
            Spacer(Modifier.width(12.dp))
            Box(
                Modifier.size(32.dp).glass(CircleShape).clickable(onClickLabel = "Ausblenden") {
                    updater.hiddenProgressFor = versionCode
                    hiddenFor = versionCode
                },
                contentAlignment = Alignment.Center,
            ) {
                StaticIcon(AppIcons.Close, tint = AppColors.TextSecondary, size = 16.dp, contentDescription = "Ausblenden")
            }
        }
    }
}

private const val PERCENT_MAX = 100f
