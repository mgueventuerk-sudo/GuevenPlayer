package com.mg.player.ui.detail

import com.mg.player.core.di.AppContainer
import com.mg.player.domain.model.ContentType
import com.mg.player.domain.model.SearchHit
import com.mg.player.domain.model.TmdbCandidate
import com.mg.player.domain.model.TmdbInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Gemeinsame TMDB-Logik für Film- und Serien-Detailseiten. */
class TmdbDetailController(container: AppContainer, private val scope: CoroutineScope, private val type: ContentType, private val itemId: String) {
    private val repo = container.tmdbRepository

    val enabled: StateFlow<Boolean> = container.settingsRepository.settings
        .map { it.tmdbActive }
        .stateIn(scope, SharingStarted.Eagerly, false)

    val info: StateFlow<TmdbInfo?> = repo.observe(type, itemId).stateIn(scope, SharingStarted.Eagerly, null)

    private val _similar = MutableStateFlow<List<SearchHit>>(emptyList())
    val similar: StateFlow<List<SearchHit>> = _similar.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    /** null = Neuzuordnung geschlossen. */
    private val _candidates = MutableStateFlow<List<TmdbCandidate>?>(null)
    val candidates: StateFlow<List<TmdbCandidate>?> = _candidates.asStateFlow()

    private val _searching = MutableStateFlow(false)
    val searching: StateFlow<Boolean> = _searching.asStateFlow()

    init {
        scope.launch {
            info.filterNotNull().distinctUntilChangedBy { it.tmdbId }.collect { current ->
                _similar.value = repo.similarInLibrary(type, current, itemId)
            }
        }
    }

    fun load() {
        scope.launch { _message.value = repo.refresh(type, itemId) }
    }

    fun openRematch(query: String) {
        _candidates.value = emptyList()
        search(query)
    }

    fun search(query: String) {
        if (query.isBlank()) return
        scope.launch {
            _searching.value = true
            _candidates.value = repo.search(type, query)
            _searching.value = false
        }
    }

    fun assign(candidate: TmdbCandidate) {
        scope.launch {
            _message.value = repo.assign(type, itemId, candidate.id)
            _candidates.value = null
        }
    }

    fun closeRematch() {
        _candidates.value = null
    }
}
