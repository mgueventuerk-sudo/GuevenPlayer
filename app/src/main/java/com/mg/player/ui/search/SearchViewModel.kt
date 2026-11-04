package com.mg.player.ui.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mg.player.core.di.AppContainer
import com.mg.player.domain.model.ContentType
import com.mg.player.domain.model.Playlist
import com.mg.player.domain.model.SearchPages
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Suche über alle Playlists. Gesucht wird einmal; die Treffer werden auf Seiten verteilt:
 * „Alle Playlists“ (zusammengeführt) und eine Seite je Playlist. Beim Wechsel der Seite wird nichts neu geladen.
 */
@OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
class SearchViewModel(private val container: AppContainer) : ViewModel() {

    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()

    /** Bereichs-Filter: null = Alles. */
    private val _filter = MutableStateFlow<ContentType?>(null)
    val filter: StateFlow<ContentType?> = _filter.asStateFlow()

    val playlists: StateFlow<List<Playlist>> = container.playlistRepository.observePlaylists()
        .map { list -> list.sortedBy { it.id } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** In dieser Sitzung gewählte Seite (sofort wirksam, unabhängig vom asynchronen Speichern). [NOT_CHOSEN] = noch keine. */
    private val chosen = MutableStateFlow(NOT_CHOSEN)

    /** Gewählte Seite: null = „Alle Playlists“. Gemerkt über App-Neustarts; eine gelöschte Playlist fällt auf „Alle“ zurück. */
    val page: StateFlow<Long?> = combine(container.settingsRepository.settings, playlists, chosen) { s, list, pick ->
        val id = if (pick == NOT_CHOSEN) s.searchPagePlaylistId else pick.takeIf { it != ALL }
        id?.takeIf { wanted -> list.any { it.id == wanted } }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /** null = noch kein Suchbegriff (weniger als zwei Zeichen). */
    val results: StateFlow<SearchPages?> = _query
        .debounce(350)
        .mapLatest { q ->
            if (q.trim().length < 2) {
                null
            } else {
                val active = container.settingsRepository.current().activePlaylistId
                container.searchRepository.searchPages(q, active)
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun onQueryChange(value: String) {
        _query.value = value
    }

    fun setFilter(type: ContentType?) {
        _filter.value = type
    }

    fun selectPage(playlistId: Long?) {
        if (page.value == playlistId && chosen.value != NOT_CHOSEN) return
        chosen.value = playlistId ?: ALL
        viewModelScope.launch { container.settingsRepository.update { it.copy(searchPagePlaylistId = playlistId) } }
    }

    /** Nächste/vorige Seite (Kanal +/− am Fernseher). */
    fun stepPage(delta: Int) {
        val ids = listOf<Long?>(null) + playlists.value.map { it.id }
        val index = ids.indexOf(page.value).coerceAtLeast(0)
        selectPage(ids[Math.floorMod(index + delta, ids.size)])
    }

    private companion object {
        const val NOT_CHOSEN = -2L
        const val ALL = -1L
    }
}
