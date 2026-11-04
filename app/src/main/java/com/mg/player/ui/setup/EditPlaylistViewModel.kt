package com.mg.player.ui.setup

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mg.player.core.di.AppContainer
import com.mg.player.core.log.AppLog
import com.mg.player.core.util.QrCodeGenerator
import com.mg.player.core.util.runCatchingCancellable
import com.mg.player.data.qr.QrSetupServer
import com.mg.player.domain.model.AutoRefreshSchedule
import com.mg.player.domain.model.Playlist
import com.mg.player.domain.model.PlaylistType
import com.mg.player.domain.repository.PlaylistEdit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Eingaben beim Bearbeiten. [password] und [m3uUrl] leer = unverändert – die gespeicherten Werte werden nie angezeigt. */
@Immutable
data class EditForm(
    val name: String = "",
    val server: String = "",
    val username: String = "",
    val password: String = "",
    val m3uUrl: String = "",
    val epgUrl: String = "",
    val allowInsecureHttp: Boolean = false,
    val autoRefreshDays: String = "",
)

@Immutable
data class EditUiState(
    val loaded: Boolean = false,
    val type: PlaylistType = PlaylistType.XTREAM,
    val form: EditForm = EditForm(),
    /** Gespeicherte M3U-Adresse mit verdeckten Werten (nur zur Orientierung). */
    val maskedM3u: String = "",
    val saving: Boolean = false,
    val error: String? = null,
    /** Die Prüfung ist gescheitert (z. B. Server gerade aus) – „Trotzdem speichern“ anbieten. */
    val canForce: Boolean = false,
    val done: Boolean = false,
    val qrUrl: String? = null,
    val qrImage: ImageBitmap? = null,
)

/**
 * Zugang bearbeiten (Plan 3.27): Daten ändern, ohne den Zugang zu löschen – die ID bleibt, damit auch Favoriten, Verlauf,
 * Sperren, Länderauswahl und Intervall. Neue Zugangsdaten werden vor dem Speichern geprüft; danach lädt die App bei
 * geänderten Zugangsdaten alles neu (Inhalte + EPG), bei geänderter EPG-Adresse nur das EPG.
 */
class EditPlaylistViewModel(private val container: AppContainer, private val playlistId: Long) : ViewModel() {

    private val qr: QrSetupServer = container.createQrServer()
    private val _state = MutableStateFlow(EditUiState())
    val state: StateFlow<EditUiState> = _state.asStateFlow()

    /** Gespeicherter Stand (entschlüsselt) – nur im Speicher für die Prüfung, nie in der Oberfläche. */
    private var original: Playlist? = null

    init {
        viewModelScope.launch {
            val p = container.playlistRepository.getPlaylist(playlistId)
            if (p == null) {
                _state.update { it.copy(loaded = true, error = "Zugang nicht gefunden") }
                return@launch
            }
            original = p
            _state.update {
                it.copy(
                    loaded = true,
                    type = p.type,
                    maskedM3u = maskSecrets(p.m3uUrl),
                    form = EditForm(
                        name = p.name,
                        server = p.serverUrl,
                        username = p.username,
                        epgUrl = p.customEpgUrl,
                        allowInsecureHttp = p.allowInsecureHttp,
                        autoRefreshDays = p.autoRefreshDays.takeIf(AutoRefreshSchedule::isEnabled)?.toString().orEmpty(),
                    ),
                )
            }
        }
        viewModelScope.launch {
            val url = withContext(Dispatchers.IO) { runCatching { qr.start() }.getOrNull() }
            val image = url?.let { withContext(Dispatchers.Default) { QrCodeGenerator.create(it).asImageBitmap() } }
            _state.update { it.copy(qrUrl = url, qrImage = image) }
        }
        viewModelScope.launch {
            // Vom Handy: nur ins Formular übernehmen, gespeichert wird erst nach Prüfung mit „Speichern“.
            qr.submissions.collect { input ->
                if (input.type != _state.value.type) {
                    _state.update { it.copy(error = "Die Art des Zugangs (Xtream/M3U) lässt sich nicht ändern – dafür einen neuen Zugang anlegen.") }
                    return@collect
                }
                update {
                    it.copy(
                        name = input.name.ifBlank { it.name },
                        server = input.serverUrl,
                        username = input.username,
                        password = input.password,
                        m3uUrl = input.m3uUrl,
                        epgUrl = input.epgUrl,
                    )
                }
            }
        }
    }

    fun update(transform: (EditForm) -> EditForm) {
        _state.update { it.copy(form = transform(it.form), error = null, canForce = false) }
    }

    fun save(force: Boolean = false) {
        val s = _state.value
        val old = original ?: return
        if (s.saving) return
        val f = s.form
        validate(s.type, f)?.let { msg -> return _state.update { it.copy(error = msg) } }
        viewModelScope.launch {
            _state.update { it.copy(saving = true, error = null, canForce = false) }
            val problem = if (force) null else verifyIfChanged(s.type, f, old)
            if (problem != null) {
                _state.update { it.copy(saving = false, error = "$problem. Die bisherigen Daten bleiben gespeichert.", canForce = true) }
                return@launch
            }
            val edit = PlaylistEdit(f.name, f.server, f.username, f.password, f.m3uUrl, f.epgUrl, f.allowInsecureHttp, f.autoRefreshDays.toIntOrNull() ?: 0)
            val result = runCatchingCancellable { container.playlistRepository.updatePlaylist(playlistId, edit) }.getOrElse { e ->
                _state.update { it.copy(saving = false, error = e.message ?: "Speichern fehlgeschlagen") }
                return@launch
            }
            afterSave(result.credentialsChanged, result.epgChanged)
            _state.update { it.copy(saving = false, done = true) }
        }
    }

    /** Nur geänderte Zugangsdaten prüfen (eine reine Namensänderung braucht keinen Server). */
    private suspend fun verifyIfChanged(type: PlaylistType, f: EditForm, old: Playlist): String? {
        val password = f.password.trim().ifEmpty { old.password }
        val m3u = f.m3uUrl.trim().ifEmpty { old.m3uUrl }
        val http = f.allowInsecureHttp != old.allowInsecureHttp
        val sameLogin = f.server.trim() == old.serverUrl && f.username.trim() == old.username && password == old.password
        return when (type) {
            PlaylistType.XTREAM ->
                if (sameLogin && !http) null else container.accountVerifier.verifyXtream(f.server, f.username, password, f.allowInsecureHttp)
            PlaylistType.M3U -> if (m3u == old.m3uUrl && !http) null else container.accountVerifier.verifyM3u(m3u, f.allowInsecureHttp)
        }
    }

    /** Zwischengespeichertes verwerfen; bei neuen Zugangsdaten alles, bei neuer EPG-Adresse nur das EPG neu laden. */
    private fun afterSave(credentialsChanged: Boolean, epgChanged: Boolean) {
        container.streamUrlResolver.clear()
        container.appScope.launch { runCatchingCancellable { container.connectionMonitor.refresh(force = true) } }
        when {
            credentialsChanged -> container.syncManager.refreshAll(playlistId)
            epgChanged -> container.syncManager.refreshEpg(playlistId)
        }
        val reload = if (credentialsChanged) "alles" else if (epgChanged) "EPG" else "nein"
        AppLog.log("Zugänge", "Zugang bearbeitet (neu laden: $reload)")
    }

    override fun onCleared() {
        qr.stop()
    }

    companion object {
        internal fun validate(type: PlaylistType, f: EditForm): String? {
            val days = f.autoRefreshDays.trim()
            return when {
                type == PlaylistType.XTREAM && (f.server.isBlank() || f.username.isBlank()) -> "Server-URL und Benutzername dürfen nicht leer sein."
                days.isNotEmpty() && (days.toIntOrNull() ?: -1) !in 0..AutoRefreshSchedule.MAX_DAYS ->
                    "Automatisch aktualisieren: 0 bis ${AutoRefreshSchedule.MAX_DAYS} Tage."
                listOf(f.server, f.m3uUrl, f.epgUrl).any { it.trim().startsWith("http://", ignoreCase = true) } && !f.allowInsecureHttp ->
                    "Dieser Zugang verwendet unverschlüsseltes HTTP. Aktiviere die Freigabe bewusst, um fortzufahren."
                else -> null
            }
        }

        /**
         * Adresse ohne Geheimnisse: nur Schema und Server bleiben lesbar, der Pfad (kann Zugangsdaten enthalten) wird zu
         * „…“, alle Werte hinter „?“ werden verdeckt – „https://anbieter.tv/…?username=•••&password=•••“.
         */
        internal fun maskSecrets(url: String): String {
            if (url.isBlank()) return ""
            val beforeQuery = url.substringBefore('?')
            val scheme = beforeQuery.substringBefore("://", "")
            val rest = beforeQuery.substringAfter("://")
            val host = rest.substringBefore('/').substringAfter('@')
            val base = (if (scheme.isEmpty()) host else "$scheme://$host") + if ('/' in rest) "/…" else ""
            val query = url.substringAfter('?', "")
            val masked = query.split('&').filter { it.isNotEmpty() }.joinToString("&") { "${it.substringBefore('=')}=•••" }
            return if (masked.isEmpty()) base else "$base?$masked"
        }
    }
}
