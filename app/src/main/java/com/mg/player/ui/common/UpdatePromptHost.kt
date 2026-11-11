package com.mg.player.ui.common

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mg.player.core.di.AppContainer
import com.mg.player.data.update.UpdateStatus
import kotlinx.coroutines.launch

/**
 * Hinweis beim Start (ab 3.51): Mit eingetragenem GitHub-Schlüssel sieht die App höchstens alle 6 Stunden nach einem
 * Update. Gibt es eins, fragt sie einmal: „Installieren“ lädt die APK und öffnet den Android-Installer.
 */
@Composable
fun UpdatePromptHost(container: AppContainer) {
    val updater = container.appUpdater
    val status by updater.status.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    LaunchedEffect(Unit) {
        updater.checkIfDue(container.settingsRepository.current().updateToken)
    }
    val update = (status as? UpdateStatus.Available)?.update ?: return
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
            scope.launch { updater.downloadAndInstall(container.settingsRepository.current().updateToken, update) }
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
                    "Fortschritt unter Einstellungen → System → App-Updates.",
            )
        },
    )
}
