package com.mg.player.ui.settings

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mg.player.core.di.AppContainer
import com.mg.player.core.log.AppLog
import com.mg.player.core.security.ShareKey
import com.mg.player.core.util.QrCodeGenerator
import com.mg.player.data.backup.BackupCrypto
import com.mg.player.data.combined.NamesExport
import com.mg.player.data.share.DeviceShareServer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class ShareMode(val title: String, val path: String) {
    BACKUP_EXPORT("Sicherung erstellen", "/backup"),
    BACKUP_IMPORT("Sicherung wiederherstellen", "/restore"),
    LOG("Fehlerprotokoll", "/log"),

    /** Gruppen- und Sendernamen, Titel und EPG-IDs aller Zugänge (ohne Zugangsdaten und Adressen) für den Testsatz. */
    NAMES("Namen für den Abgleich", "/log"),
    VPN_IMPORT("VPN einrichten", "/vpn"),
}

/**
 * Sicherung und Protokoll übers Handy. Das Kennwort der Sicherung wird nur am Fernseher eingegeben:
 * - Erstellen: erst Kennwort am TV festlegen, dann zeigt der QR-Code die Download-Seite.
 * - Wiederherstellen: Datei vom Handy hochladen, danach fragt der Fernseher nach dem Kennwort.
 */
class ShareViewModel(private val container: AppContainer, private val mode: ShareMode) : ViewModel() {

    /** Nur für „VPN einrichten“: Schlüssel steht im QR-Code (#k=…), nie im Netz. */
    private val vpnKey = if (mode == ShareMode.VPN_IMPORT) ShareKey() else null

    private val server = DeviceShareServer(
        exportBackup = { exportPassphrase?.let { container.backupManager.export(it) } },
        // Sonst nimmt auch „Protokoll teilen“ oder „Sicherung erstellen“ eine fremde Sicherung an (Token steht im Klartext im WLAN).
        receiveBackup = if (mode == ShareMode.BACKUP_IMPORT) ::receive else null,
        // Suspend-fähig, damit die Datenbankabfrage nicht mit runBlocking einen Server-Thread blockiert.
        readLog = { if (mode == ShareMode.NAMES) NamesExport.build(container.database) else AppLog.readRedacted() },
        receiveVpn = ::receiveVpn,
        allowLog = mode == ShareMode.LOG || mode == ShareMode.NAMES,
    )

    @Volatile
    private var exportPassphrase: String? = null

    private val _url = MutableStateFlow<String?>(null)
    val url: StateFlow<String?> = _url.asStateFlow()
    private val _qr = MutableStateFlow<ImageBitmap?>(null)
    val qr: StateFlow<ImageBitmap?> = _qr.asStateFlow()
    private val _status = MutableStateFlow<String?>(null)
    val status: StateFlow<String?> = _status.asStateFlow()
    private val _restored = MutableStateFlow(false)
    val restored: StateFlow<Boolean> = _restored.asStateFlow()
    private val _log = MutableStateFlow("")
    val log: StateFlow<String> = _log.asStateFlow()

    /** Erstellen: Kennwort am Fernseher festgelegt – erst dann gibt es den QR-Code. */
    private val _exportReady = MutableStateFlow(mode != ShareMode.BACKUP_EXPORT)
    val exportReady: StateFlow<Boolean> = _exportReady.asStateFlow()

    /** Wiederherstellen: verschlüsselte Datei ist angekommen und wartet auf das Kennwort. */
    private val _awaitingPassphrase = MutableStateFlow(false)
    val awaitingPassphrase: StateFlow<Boolean> = _awaitingPassphrase.asStateFlow()

    @Volatile
    private var pendingBackup: String? = null

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    init {
        viewModelScope.launch {
            val base = withContext(Dispatchers.IO) { runCatching { server.start() }.getOrNull() }
            if (base == null) {
                _status.value = "Kein Netzwerk gefunden – Fernseher mit dem WLAN verbinden."
            } else {
                val link = server.urlFor(base, mode.path) + (vpnKey?.let { "#k=${it.fragment}" } ?: "")
                _url.value = link
                _qr.value = withContext(Dispatchers.Default) { QrCodeGenerator.create(link).asImageBitmap() }
            }
            if (mode == ShareMode.LOG || mode == ShareMode.NAMES) reloadLog()
        }
    }

    /** Liefert eine Fehlermeldung oder null, wenn das Kennwort übernommen wurde. */
    fun setExportPassphrase(pass: String, repeat: String): String? {
        val error = when {
            pass.length < BackupCrypto.MIN_PASSPHRASE -> "Das Kennwort braucht mindestens ${BackupCrypto.MIN_PASSPHRASE} Zeichen."
            pass != repeat -> "Die beiden Kennwörter sind nicht gleich."
            else -> null
        }
        if (error == null) {
            exportPassphrase = pass
            _exportReady.value = true
            _status.value = "Kennwort festgelegt – jetzt den Code mit dem Handy scannen."
        }
        return error
    }

    private suspend fun receive(text: String): DeviceShareServer.Reply {
        if (!BackupCrypto.isEncrypted(text)) {
            // Ältere, unverschlüsselte Sicherungen brauchen kein Kennwort.
            val error = restore(text, null)
            return if (error == null) {
                DeviceShareServer.Reply(true, "Wiederhergestellt ✓", "Der Fernseher lädt jetzt deine Playlists neu.")
            } else {
                DeviceShareServer.Reply(false, "Fehlgeschlagen", error)
            }
        }
        pendingBackup = text
        _awaitingPassphrase.value = true
        _status.value = "Sicherung empfangen – bitte hier das Kennwort eingeben."
        return DeviceShareServer.Reply(true, "Datei empfangen ✓", "Gib jetzt am Fernseher das Kennwort der Sicherung ein.")
    }

    private suspend fun receiveVpn(body: String): DeviceShareServer.Reply {
        val key = vpnKey ?: return DeviceShareServer.Reply(false, "Nicht verfügbar", "Bitte am Fernseher „VPN einrichten“ öffnen.")
        val (text, fileName) = key.open(body)
            ?: return DeviceShareServer.Reply(false, "Fehlgeschlagen", "Die Daten kamen unvollständig an – bitte den QR-Code neu scannen.")
        return importVpn(text, fileName)
    }

    /** .conf vom Handy, aus einer Datei oder der Zwischenablage übernehmen. */
    fun importVpnText(text: String, fileName: String?) {
        viewModelScope.launch { importVpn(text, fileName) }
    }

    private suspend fun importVpn(text: String, fileName: String?): DeviceShareServer.Reply {
        val result = withContext(Dispatchers.Default) {
            container.vpnImporter.import(text, fileName).onSuccess {
                // Surfshark-Datei: Schlüssel gilt für alle Surfshark-Server – danach „Surfshark-Server wählen“ per Klick.
                container.surfshark.allowAdopt()
                container.surfshark.refresh()
            }
        }
        return result.fold(
            onSuccess = { profile ->
                _status.value = "Standort „${profile.name}“ gespeichert – VPN wird verbunden. Bestätige ggf. die VPN-Erlaubnis."
                _restored.value = true
                DeviceShareServer.Reply(true, "VPN eingerichtet ✓", "„${profile.name}“ ist gespeichert. Bestätige am Fernseher die VPN-Erlaubnis mit OK.")
            },
            onFailure = { error ->
                val message = error.message ?: "Unbekannter Fehler"
                _status.value = "VPN einrichten fehlgeschlagen: $message"
                DeviceShareServer.Reply(false, "Fehlgeschlagen", message)
            },
        )
    }

    /** Wiederherstellen mit dem am Fernseher eingegebenen Kennwort. */
    fun restorePending(passphrase: String) {
        val backup = pendingBackup ?: return
        if (_busy.value) return
        viewModelScope.launch {
            _busy.value = true
            val error = restore(backup, passphrase)
            if (error == null) {
                pendingBackup = null
                _awaitingPassphrase.value = false
            }
            _busy.value = false
        }
    }

    private suspend fun restore(json: String, passphrase: String?): String? = try {
        val active = container.backupManager.restore(json, passphrase)
        container.streamUrlResolver.clear()
        active?.let { container.syncManager.refreshAll(it) }
        _status.value = "Sicherung wiederhergestellt – deine Playlists werden jetzt neu geladen."
        _restored.value = true
        null
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        val message = e.message ?: "Unbekannter Fehler"
        _status.value = "Wiederherstellen fehlgeschlagen: $message"
        message
    }

    fun reloadLog() {
        viewModelScope.launch {
            _log.value = withContext(Dispatchers.IO) {
                if (mode == ShareMode.NAMES) {
                    NamesExport.build(container.database).lines().take(PREVIEW_LINES).joinToString("\n")
                } else {
                    AppLog.readRedacted().lines().filter { it.isNotBlank() }.takeLast(PREVIEW_LINES).reversed().joinToString("\n")
                }
            }
        }
    }

    fun clearLog() {
        AppLog.clear()
        _log.value = ""
    }

    override fun onCleared() {
        exportPassphrase = null
        pendingBackup = null
        server.stop()
    }
}

private const val PREVIEW_LINES = 250
