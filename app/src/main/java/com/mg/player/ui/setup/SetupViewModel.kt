package com.mg.player.ui.setup

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mg.player.core.di.AppContainer
import com.mg.player.core.security.SecretStorageUnavailable
import com.mg.player.core.util.QrCodeGenerator
import com.mg.player.data.qr.QrSetupServer
import com.mg.player.domain.model.PlaylistType
import com.mg.player.domain.repository.PlaylistInput
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class SetupPhase { FORM, IMPORTING, DONE, ERROR }

@Immutable
data class SetupForm(
    val type: PlaylistType = PlaylistType.XTREAM,
    val name: String = "",
    val server: String = "",
    val username: String = "",
    val password: String = "",
    val m3uUrl: String = "",
    val epgUrl: String = "",
    val allowInsecureHttp: Boolean = false,
) {
    val isValid: Boolean
        get() = when (type) {
            PlaylistType.XTREAM -> server.isNotBlank() && username.isNotBlank() && password.isNotBlank()
            PlaylistType.M3U -> m3uUrl.isNotBlank()
        }

    fun toInput() = PlaylistInput(name, type, server, username, password, m3uUrl, epgUrl, allowInsecureHttp)
}

@Immutable
data class SetupUiState(
    val phase: SetupPhase = SetupPhase.FORM,
    val qrUrl: String? = null,
    val qrImage: ImageBitmap? = null,
    val form: SetupForm = SetupForm(),
    val progressText: String = "",
    val progressCount: Int = 0,
    val error: String? = null,
)

class SetupViewModel(private val container: AppContainer) : ViewModel() {

    private val server: QrSetupServer = container.createQrServer()
    private val _state = MutableStateFlow(SetupUiState())
    val state: StateFlow<SetupUiState> = _state.asStateFlow()
    private var lastPlaylistId: Long? = null

    /** Aktiver Zugang vor dem Einrichten – wird wiederhergestellt, wenn das Einrichten abgebrochen wird. */
    private var previousActiveId: Long? = null
    private var previousActiveKnown = false
    private var importJob: Job? = null

    init {
        viewModelScope.launch {
            val url = withContext(Dispatchers.IO) { runCatching { server.start() }.getOrNull() }
            val image = url?.let { withContext(Dispatchers.Default) { QrCodeGenerator.create(it).asImageBitmap() } }
            _state.update { it.copy(qrUrl = url, qrImage = image) }
        }
        viewModelScope.launch {
            server.submissions.collect { input ->
                _state.update {
                    it.copy(
                        form = SetupForm(input.type, input.name, input.serverUrl, input.username, input.password, input.m3uUrl, input.epgUrl),
                    )
                }
                submit(input)
            }
        }
    }

    fun updateForm(transform: (SetupForm) -> SetupForm) {
        _state.update { it.copy(form = transform(it.form)) }
    }

    fun submitForm() {
        val form = _state.value.form
        if (!form.isValid) {
            _state.update { it.copy(error = "Bitte alle Pflichtfelder ausfüllen.") }
            return
        }
        submit(form.toInput())
    }

    private fun submit(input: PlaylistInput) {
        if (_state.value.phase == SetupPhase.IMPORTING) return
        val hasHttpEndpoint = listOf(input.serverUrl, input.m3uUrl, input.epgUrl)
            .any { it.trim().startsWith("http://", ignoreCase = true) }
        if (hasHttpEndpoint && !input.allowInsecureHttp) {
            _state.update {
                it.copy(
                    phase = SetupPhase.FORM,
                    error = "Dieser Zugang verwendet unverschlüsseltes HTTP. Aktiviere die Freigabe bewusst, um fortzufahren.",
                )
            }
            return
        }
        viewModelScope.launch {
            _state.update { it.copy(phase = SetupPhase.IMPORTING, error = null, progressText = "Speichere Zugangsdaten…") }
            if (!previousActiveKnown) {
                previousActiveId = container.settingsRepository.current().activePlaylistId
                previousActiveKnown = true
            }
            lastPlaylistId?.let { container.playlistRepository.deletePlaylist(it) }
            lastPlaylistId = null
            val id = try {
                container.addPlaylist(input)
            } catch (e: SecretStorageUnavailable) {
                _state.update { it.copy(phase = SetupPhase.ERROR, error = e.message) }
                return@launch
            }
            lastPlaylistId = id
            runImport(id)
        }
    }

    fun retry() {
        // Noch kein Zugang gespeichert (z. B. Verschlüsselung scheiterte): komplett neu versuchen.
        val id = lastPlaylistId ?: return submitForm()
        viewModelScope.launch { runImport(id) }
    }

    fun backToForm() {
        _state.update { it.copy(phase = SetupPhase.FORM) }
    }

    private suspend fun runImport(id: Long) {
        _state.update { it.copy(phase = SetupPhase.IMPORTING, error = null) }
        val sync = container.syncManager
        // Erst nur die Inhalte: Das EPG lädt nach der Länderauswahl – nur für die gewählten Länder.
        val job = sync.refreshContent(id).also { importJob = it }
        val progressJob = viewModelScope.launch {
            sync.state.collect { s -> _state.update { it.copy(progressText = s.message, progressCount = s.count) } }
        }
        job.join()
        progressJob.cancel()
        val result = sync.state.first()
        _state.update {
            if (result.error == null) {
                it.copy(phase = SetupPhase.DONE)
            } else {
                it.copy(phase = SetupPhase.ERROR, error = result.error)
            }
        }
    }

    override fun onCleared() {
        server.stop()
        // Abgebrochen (Zurück) oder fehlgeschlagen: den halb eingerichteten Zugang nicht liegen lassen – sonst wäre
        // er aktiv und die App zeigte leere Listen. Der vorher aktive Zugang kommt zurück.
        val unfinished = lastPlaylistId?.takeIf { _state.value.phase != SetupPhase.DONE } ?: return
        val restore = previousActiveId
        val running = importJob
        container.appScope.launch {
            // Läuft der Import noch, erst sein Ende abwarten – sonst schriebe er in einen gelöschten Zugang.
            running?.join()
            container.playlistRepository.deletePlaylist(unfinished)
            container.settingsRepository.update { s ->
                if (s.activePlaylistId == unfinished) s.copy(activePlaylistId = restore) else s
            }
        }
    }
}
