package com.mg.player.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import coil3.SingletonImageLoader
import com.mg.player.core.device.DeviceTier
import com.mg.player.core.di.AppContainer
import com.mg.player.data.combined.Combined
import com.mg.player.data.combined.CombinedConfig
import com.mg.player.data.combined.ChannelCatalogUpdate
import com.mg.player.data.combined.CombinedStats
import com.mg.player.data.sync.SyncState
import com.mg.player.domain.model.AppSettings
import com.mg.player.domain.model.Playlist
import com.mg.player.domain.model.StationReport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SettingsViewModel(private val container: AppContainer) : ViewModel() {

    val deviceTier: DeviceTier = DeviceTier.of(container.appContext)

    val settings: StateFlow<AppSettings> = container.settingsRepository.settings
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AppSettings())

    val playlists: StateFlow<List<Playlist>> = container.playlistRepository.observePlaylists()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val sync: StateFlow<SyncState> = container.syncManager.state

    /** Was der Wiedergabe-Wächter bisher erkannt und behoben hat, kurz als Text. */
    fun supervisorStats(): String {
        val stats = container.playback.recoveryMemory.stats()
        if (stats.isEmpty()) return "Bisher keine Eingriffe nötig"
        return stats.entries.sortedByDescending { it.value.first }.joinToString(" · ") { (symptom, counts) ->
            "${symptom.label} ${counts.first}× (${counts.second} behoben)"
        }
    }

    private val _info = MutableStateFlow<String?>(null)
    val info: StateFlow<String?> = _info.asStateFlow()

    val hasPin: StateFlow<Boolean> = container.parentalControl.hasPin
    val unlocked: StateFlow<Boolean> = container.parentalControl.unlocked

    fun setPin(pin: String) {
        viewModelScope.launch {
            container.parentalControl.setPin(pin)
            _info.value = "PIN gespeichert"
        }
    }

    fun removePin() {
        viewModelScope.launch {
            container.parentalControl.removePin()
            // Ohne PIN gibt es keine App-Sperre mehr.
            container.settingsRepository.update { it.copy(appLock = false) }
            _info.value = "PIN entfernt – gesperrte Kategorien sind wieder frei zugänglich"
        }
    }

    fun lockAgain() {
        container.parentalControl.lockAgain()
        _info.value = "Gesperrte Kategorien sind wieder geschützt"
    }

    fun setTmdbKey(key: String) {
        viewModelScope.launch {
            val trimmed = key.trim()
            container.settingsRepository.update { it.copy(tmdbApiKey = trimmed) }
            val stored = container.settingsRepository.current().tmdbApiKey == trimmed
            _info.value = when {
                trimmed.isBlank() -> "TMDB-Schlüssel entfernt"
                stored -> "TMDB-Schlüssel verschlüsselt gespeichert"
                else -> "TMDB-Schlüssel konnte auf diesem Gerät nicht sicher gespeichert werden"
            }
        }
    }

    /** Bzzoiro-Schlüssel speichern und die Ligen gleich darauf umstellen (mit Rückmeldung). */
    fun setBzzoiroToken(key: String) {
        viewModelScope.launch {
            container.settingsRepository.update { it.copy(bzzoiroToken = key.trim()) }
            _info.value = if (key.isBlank()) "Bzzoiro-Schlüssel entfernt" else "Bzzoiro wird geprüft …"
            if (key.isNotBlank()) _info.value = container.sport.repository.adoptBzzoiro()
        }
    }

    fun clearTmdbCache() {
        viewModelScope.launch {
            container.tmdbRepository.clearCache()
            _info.value = "TMDB-Daten werden beim nächsten Öffnen neu geladen"
        }
    }

    /** Letztes Problem beim Abruf der Sport-Events (z. B. ungültiger Sportmonks-Schlüssel). */
    val sportProblem: StateFlow<String?> = container.sport.repository.problem
    val sportSummary: StateFlow<Pair<Int, Int>?> = container.sport.repository.summary

    /** Diagnose der Bzzoiro-Sendernamen (gefunden / nicht gefunden). */
    val sportStations: StateFlow<StationReport?> = container.sport.repository.stations

    fun followedLeagues(s: AppSettings): Int = container.sport.repository.followed(s).size

    fun update(transform: (AppSettings) -> AppSettings) {
        viewModelScope.launch { container.settingsRepository.update(transform) }
    }

    /** Wechselt den Zugang im App-Scope; [onDone] baut danach die Startseite neu auf. */
    fun switchTo(id: Long, onDone: () -> Unit) {
        container.appScope.launch {
            container.switchPlaylist(id)
            onDone()
        }
    }

    fun deletePlaylist(id: Long) {
        // App-Scope wie switchTo: Verlässt man die Seite während des Löschens (große Zugänge brauchen Sekunden), liefe es
        // sonst halb – der Zugang wäre weg, der aktive Zugang zeigte aber noch auf ihn (leere Listen).
        container.appScope.launch {
            container.playlistRepository.deletePlaylist(id)
            container.combined.onSourceChanged(id, "Zugang gelöscht")
            container.playback.streamMemory.forget(id)
            container.playback.sourceHealth.forget(id)
            val remaining = container.playlistRepository.observePlaylists().first().filter { it.id != id }
            container.settingsRepository.update { s ->
                // Auch „Alle Zugänge“ endet, wenn nur noch ein Zugang übrig ist.
                val lost = s.activePlaylistId == id || (Combined.isCombined(s.activePlaylistId) && remaining.size < 2)
                if (lost) s.copy(activePlaylistId = remaining.firstOrNull()?.id, lastChannelId = null) else s
            }
            _info.value = "Playlist gelöscht"
        }
    }

    fun setAllowInsecureHttp(id: Long, allowed: Boolean) {
        viewModelScope.launch {
            container.playlistRepository.setAllowInsecureHttp(id, allowed)
            container.streamUrlResolver.clear()
            _info.value = if (allowed) "HTTP für diesen Zugang freigegeben" else "HTTP für diesen Zugang gesperrt"
        }
    }

    fun setAutoRefreshDays(id: Long, days: Int) {
        viewModelScope.launch {
            container.playlistRepository.setAutoRefreshDays(id, days)
            _info.value = if (days > 0) "Automatische Aktualisierung: ${autoRefreshLabel(days)}" else "Automatische Aktualisierung aus"
        }
    }

    /** Einen bestimmten Zugang neu laden (aus seiner Einstellungsseite): alles oder [epgOnly]. */
    fun refreshPlaylist(id: Long, epgOnly: Boolean = false) {
        if (epgOnly) container.syncManager.refreshEpg(id) else container.syncManager.refreshAll(id)
    }

    fun setEpgShift(id: Long, hours: Int) {
        viewModelScope.launch { container.playlistRepository.setEpgShiftHours(id, hours) }
    }

    fun refreshAll() {
        val id = settings.value.activePlaylistId ?: return
        // „Alle Zugänge“: die Original-Zugänge aktualisieren, die Kombination folgt danach.
        if (Combined.isCombined(id)) return refreshEverything()
        container.syncManager.refreshAll(id)
    }

    /** Alle Zugänge samt EPG nacheinander im Hintergrund. */
    fun refreshEverything() {
        container.syncManager.refreshEverything(playlists.value.map { it.id to it.name })
    }

    /** „Alle Zugänge“: Einstellungen; Änderungen an Zugängen und Gruppen bauen die Kombination neu auf. */
    val combined: StateFlow<CombinedConfig> = container.combined.settings.config

    /** Ergebnis des letzten automatischen Aufbaus (null = in dieser Sitzung noch keiner). */
    val combinedStats: StateFlow<CombinedStats?> = container.combined.builder.stats

    fun updateCombined(rebuild: Boolean, transform: (CombinedConfig) -> CombinedConfig) {
        container.combined.settings.update(transform)
        if (rebuild) container.combined.builder.rebuildLater("Einstellung", delayMs = 500)
    }

    /** Zugang in der Rangfolge eine Stelle nach vorn. */
    fun moveAccountUp(id: Long) = updateCombined(rebuild = true) { cfg ->
        val order = cfg.included(playlists.value.map { it.id }).toMutableList().also { list ->
            playlists.value.map { it.id }.filter { it !in list }.forEach { list += it }
        }
        val i = order.indexOf(id)
        if (i > 0) order.add(i - 1, order.removeAt(i))
        cfg.copy(order = order)
    }

    /** Letzter Internetabgleich des Senderkatalogs (0 = mitgelieferter Katalog). */
    val catalogOnlineAt: Long get() = container.combined.catalogUpdate.lastUpdateMs

    /** „Neu zuordnen“: erst den Senderkatalog mit dem Internet abgleichen, dann sofort neu zusammenführen. */
    fun rebuildCombined() {
        _info.value = "Senderkatalog wird abgeglichen …"
        viewModelScope.launch {
            val outcome = container.combined.refreshCatalog(force = true)
            container.combined.builder.rebuildLater("Von Hand", delayMs = 0)
            _info.value = when (outcome) {
                is ChannelCatalogUpdate.Outcome.Updated -> "Katalog aktuell (${outcome.channels} Sender) – alle Zugänge werden neu zusammengeführt"
                is ChannelCatalogUpdate.Outcome.Failed -> "Internetabgleich nicht möglich – es wird mit dem bisherigen Katalog zusammengeführt"
                ChannelCatalogUpdate.Outcome.NotDue -> "Alle Zugänge werden neu zusammengeführt"
            }
        }
    }

    fun clearImageCache() {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                val loader = SingletonImageLoader.get(container.appContext)
                loader.memoryCache?.clear()
                loader.diskCache?.clear()
            }
            _info.value = "Bild-Cache geleert"
        }
    }

    fun clearEpgCache() {
        viewModelScope.launch {
            container.epgRepository.clear()
            _info.value = "EPG-Cache geleert – über „Nur EPG aktualisieren“ neu laden"
        }
    }

    fun clearHistory() {
        viewModelScope.launch {
            container.playbackRepository.clearHistory()
            container.channelHabits.clear()
            _info.value = "Wiedergabeverlauf gelöscht"
        }
    }

    fun consumeInfo() {
        _info.value = null
    }
}
