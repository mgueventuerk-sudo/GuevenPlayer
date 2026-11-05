package com.mg.player.ui.settings

import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mg.player.BuildConfig
import com.mg.player.data.update.AppUpdate
import com.mg.player.data.update.UpdateStatus
import com.mg.player.ui.common.LocalAppContainer
import com.mg.player.ui.theme.AppIcons
import kotlinx.coroutines.launch

/**
 * Bereich „App-Updates“ (ab 3.51): GitHub-Zugriffsschlüssel für das private Repo und „Nach Update suchen“ bzw.
 * „Update installieren“. Der Schlüssel braucht nur Leserechte („Contents: Read-only“) auf dieses eine Repo.
 */
internal fun LazyListScope.updateItems(c: SettingsContext) {
    settingsGroup(
        "updates",
        "App-Updates",
        footer = "GitHub → Settings → Developer settings → Fine-grained tokens: nur Repo „Player“, Rechte „Contents: Read-only“. " +
            "Er wird nur verschlüsselt gespeichert und nicht ins Backup übernommen.",
    ) {
        row("update-token", contentType = "text-field") {
            SecretKeyField(c.s.updateToken, "GitHub-Zugriffsschlüssel") { key -> c.vm.update { it.copy(updateToken = key.trim()) } }
        }
        row("update-action") { UpdateActionRow(c.s.updateToken) }
    }
}

@Composable
private fun UpdateActionRow(token: String) {
    val updater = LocalAppContainer.current.appUpdater
    val status by updater.status.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val (label, description) = updateTexts(status)
    ActionRow(
        label,
        {
            val s = status
            val update = when (s) {
                is UpdateStatus.Available -> s.update
                is UpdateStatus.ReadyToInstall -> s.update
                is UpdateStatus.Failed -> s.retry
                else -> null
            }
            when {
                s is UpdateStatus.Downloading || s == UpdateStatus.Checking -> Unit
                update != null -> scope.launch { updater.downloadAndInstall(token, update) }
                else -> scope.launch { updater.check(token) }
            }
        },
        description,
        icon = if (status is UpdateStatus.Available) AppIcons.Download else AppIcons.Refresh,
        chevron = false,
    )
}

/** Beschriftung und Beschreibung der Zeile je nach Stand. */
internal fun updateTexts(status: UpdateStatus): Pair<String, String> {
    val installed = "Installiert: ${BuildConfig.VERSION_NAME}"
    return when (status) {
        UpdateStatus.Idle -> "Nach Update suchen" to installed
        UpdateStatus.Checking -> "Suche …" to installed
        UpdateStatus.UpToDate -> "Nach Update suchen" to "Aktuell · $installed"
        is UpdateStatus.Available -> "Update installieren" to "Version ${status.update.versionName} verfügbar${size(status.update)}"
        is UpdateStatus.Downloading -> "Wird geladen … ${status.percent} %" to "Version ${status.update.versionName}"
        is UpdateStatus.ReadyToInstall -> "Installer erneut öffnen" to "Version ${status.update.versionName} ist geladen"
        is UpdateStatus.Failed -> (if (status.retry != null) "Update installieren" else "Erneut versuchen") to status.message
    }
}

private fun size(update: AppUpdate): String = if (update.sizeBytes > 0) " (${update.sizeBytes / BYTES_PER_MB} MB)" else ""

private const val BYTES_PER_MB = 1_048_576L
