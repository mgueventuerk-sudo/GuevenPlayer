package com.mg.player.ui.detail

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mg.player.core.di.AppContainer
import com.mg.player.domain.model.ContentType
import com.mg.player.domain.model.Episode
import com.mg.player.domain.model.PlaybackProgress
import com.mg.player.domain.model.PlayerEngineType
import com.mg.player.domain.model.Series
import com.mg.player.domain.usecase.ResolveNextEpisodeUseCase
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@Immutable
data class ContinueTarget(val episode: Episode, val positionMs: Long, val label: String)

class SeriesDetailViewModel(private val container: AppContainer, private val seriesId: String) : ViewModel() {

    private val _series = MutableStateFlow<Series?>(null)
    val series: StateFlow<Series?> = _series.asStateFlow()

    private val _loading = MutableStateFlow(true)
    val loading: StateFlow<Boolean> = _loading.asStateFlow()

    val episodes: StateFlow<List<Episode>> = container.seriesRepository.observeEpisodes(seriesId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val progress: StateFlow<Map<String, PlaybackProgress>> = container.playbackRepository.observeSeriesProgress(seriesId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    /** Vom Nutzer gewählte Staffel (null = automatisch). */
    private val userSeason = MutableStateFlow<Int?>(null)

    val continueTarget: StateFlow<ContinueTarget?> = combine(episodes, progress) { list, map -> resolveContinue(list, map) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /**
     * Gewählte Staffel – standardmäßig die der „Weiterschauen“-Folge. Abgeleitet statt einmalig gesetzt: Kamen die
     * Folgen vor dem Fortschritt an, blieb vorher Staffel 1 stehen, obwohl in Staffel 3 weitergeschaut wird.
     */
    val selectedSeason: StateFlow<Int?> = combine(userSeason, continueTarget, episodes) { user, target, list ->
        pickSeason(user, target, list)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val isFavorite: StateFlow<Boolean> = container.playbackRepository.isFavorite(ContentType.SERIES, seriesId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    private val _engine = MutableStateFlow<PlayerEngineType?>(null)
    val engine: StateFlow<PlayerEngineType?> = _engine.asStateFlow()

    val tmdb = TmdbDetailController(container, viewModelScope, ContentType.SERIES, seriesId)

    init {
        viewModelScope.launch {
            _series.value = container.seriesRepository.seriesById(seriesId)
            _engine.value = container.settingsRepository.current().vodEngine
            container.seriesRepository.loadEpisodes(seriesId)
            _series.value = container.seriesRepository.seriesById(seriesId)
            _loading.value = false
            tmdb.load()
        }
    }

    private fun resolveContinue(list: List<Episode>, map: Map<String, PlaybackProgress>): ContinueTarget? {
        if (list.isEmpty()) return null
        val last = map.values.maxByOrNull { it.updatedAtMs }
        val lastEpisode = last?.let { p -> list.firstOrNull { PlaybackProgress.episodeKey(it.id) == p.contentKey } }
        if (last == null || lastEpisode == null) {
            val first = list.first()
            return ContinueTarget(first, 0, "Abspielen ${first.code}")
        }
        if (!last.watched) {
            return ContinueTarget(lastEpisode, last.positionMs, "Weiterschauen bei ${lastEpisode.code}")
        }
        val next = ResolveNextEpisodeUseCase.next(list, lastEpisode) ?: return ContinueTarget(lastEpisode, 0, "Erneut ansehen ${lastEpisode.code}")
        return ContinueTarget(next, 0, "Weiterschauen bei ${next.code}")
    }

    fun selectSeason(season: Int) {
        userSeason.value = season
    }

    fun toggleFavorite() {
        viewModelScope.launch { container.playbackRepository.toggleFavorite(ContentType.SERIES, seriesId) }
    }

    fun pickEngine(type: PlayerEngineType) {
        _engine.value = type
    }
}

/** Staffel-Auswahl: eigene Wahl (falls es sie noch gibt), sonst die der nächsten Folge, sonst die erste. */
internal fun pickSeason(user: Int?, target: ContinueTarget?, episodes: List<Episode>): Int? = user?.takeIf { season -> episodes.any { it.season == season } }
    ?: target?.episode?.season
    ?: episodes.firstOrNull()?.season
