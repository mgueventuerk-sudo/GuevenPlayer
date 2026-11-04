package com.mg.player.ui.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.PagingData
import androidx.paging.cachedIn
import com.mg.player.core.di.AppContainer
import com.mg.player.domain.model.Category
import com.mg.player.domain.model.ContentType
import com.mg.player.domain.model.Movie
import com.mg.player.domain.model.Series
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Gemeinsame Logik für Film- und Serien-Mediathek: Kategorien + Paging-Grid. */
abstract class LibraryViewModel<T : Any>(private val appContainer: AppContainer) : ViewModel() {

    protected val playlistId: StateFlow<Long?> = appContainer.settingsRepository.settings
        .map { it.activePlaylistId }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    protected abstract val contentType: ContentType
    protected abstract fun categoriesFor(playlistId: Long): Flow<List<Category>>
    protected abstract fun itemsFor(playlistId: Long, categoryId: String): Flow<PagingData<T>>

    private val _selectedCategoryId = MutableStateFlow(Category.ALL)
    val selectedCategoryId: StateFlow<String> = _selectedCategoryId.asStateFlow()
    private var focusJob: Job? = null

    /** Zuletzt geöffnetes Element – für die Fokus-Wiederherstellung nach "Zurück". */
    var restoreKey: String? = null

    val categories: StateFlow<List<Category>> by lazy {
        playlistId
            .flatMapLatest { id -> if (id == null) flowOf(emptyList()) else categoriesFor(id) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    }

    val items: Flow<PagingData<T>> by lazy {
        combine(playlistId.filterNotNull(), _selectedCategoryId) { id, cat -> id to cat }
            .distinctUntilChanged()
            .flatMapLatest { (id, cat) -> itemsFor(id, cat) }
            .cachedIn(viewModelScope)
    }

    fun onCategoryFocused(category: Category) {
        if (!appContainer.parentalControl.isAccessible(category)) return
        focusJob?.cancel()
        focusJob = viewModelScope.launch {
            delay(300)
            _selectedCategoryId.value = category.id
        }
    }

    fun onCategoryClicked(category: Category) {
        focusJob?.cancel()
        _selectedCategoryId.value = category.id
    }

    /** OK gedrückt halten auf einem Poster: Favorit an/aus, [onResult] meldet den neuen Zustand. */
    fun toggleFavorite(itemId: String, onResult: (Boolean) -> Unit) {
        viewModelScope.launch {
            val repo = appContainer.playbackRepository
            repo.toggleFavorite(contentType, itemId)
            onResult(repo.isFavorite(contentType, itemId).first())
        }
    }
}

class MoviesViewModel(private val container: AppContainer) : LibraryViewModel<Movie>(container) {
    override val contentType = ContentType.MOVIE
    override fun categoriesFor(playlistId: Long) = container.vodRepository.categories(playlistId)
    override fun itemsFor(playlistId: Long, categoryId: String) = container.vodRepository.movies(playlistId, categoryId)
}

class SeriesViewModel(private val container: AppContainer) : LibraryViewModel<Series>(container) {
    override val contentType = ContentType.SERIES
    override fun categoriesFor(playlistId: Long) = container.seriesRepository.categories(playlistId)
    override fun itemsFor(playlistId: Long, categoryId: String) = container.seriesRepository.series(playlistId, categoryId)
}
