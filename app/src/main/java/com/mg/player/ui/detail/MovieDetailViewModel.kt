package com.mg.player.ui.detail

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mg.player.core.di.AppContainer
import com.mg.player.domain.model.ContentType
import com.mg.player.domain.model.Movie
import com.mg.player.domain.model.PlaybackProgress
import com.mg.player.domain.model.PlayerEngineType
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class MovieDetailViewModel(private val container: AppContainer, private val movieId: String) : ViewModel() {

    private val _movie = MutableStateFlow<Movie?>(null)
    val movie: StateFlow<Movie?> = _movie.asStateFlow()

    private val _loading = MutableStateFlow(true)
    val loading: StateFlow<Boolean> = _loading.asStateFlow()

    val progress: StateFlow<PlaybackProgress?> = container.playbackRepository
        .observeProgress(PlaybackProgress.movieKey(movieId))
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val isFavorite: StateFlow<Boolean> = container.playbackRepository
        .isFavorite(ContentType.MOVIE, movieId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    private val _engine = MutableStateFlow<PlayerEngineType?>(null)
    val engine: StateFlow<PlayerEngineType?> = _engine.asStateFlow()

    val tmdb = TmdbDetailController(container, viewModelScope, ContentType.MOVIE, movieId)

    init {
        viewModelScope.launch {
            _movie.value = container.vodRepository.movie(movieId)
            _engine.value = container.settingsRepository.current().vodEngine
            _loading.value = false
            // Erst die Anbieter-Details (liefern oft die tmdb_id), dann TMDB.
            container.vodRepository.loadDetails(movieId)?.let { _movie.value = it }
            tmdb.load()
        }
    }

    fun toggleFavorite() {
        viewModelScope.launch { container.playbackRepository.toggleFavorite(ContentType.MOVIE, movieId) }
    }

    fun pickEngine(type: PlayerEngineType) {
        _engine.value = type
    }
}
