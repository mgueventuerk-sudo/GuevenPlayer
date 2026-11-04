package com.mg.player.ui.home

import androidx.activity.compose.ReportDrawnWhen
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.paging.LoadState
import androidx.paging.compose.collectAsLazyPagingItems
import com.mg.player.domain.model.AppSettings
import com.mg.player.domain.model.Category
import com.mg.player.domain.model.Movie
import com.mg.player.domain.model.PlayRequest
import com.mg.player.domain.model.TmdbHomeRecommendations
import com.mg.player.ui.common.LocalAppContainer
import com.mg.player.ui.common.LocalToast
import com.mg.player.ui.common.isTouch
import com.mg.player.ui.navigation.LocalNavigator
import com.mg.player.ui.navigation.Screen
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch

/** Startseite aus den vorhandenen Room-/Paging-Repositories; sie lädt nie eine vollständige Bibliothek. */
@Composable
fun StreamingHomeScreen(
    modifier: Modifier = Modifier,
    homeFocusRequester: FocusRequester? = null,
    onOpenSection: (String) -> Unit = {},
) {
    val container = LocalAppContainer.current
    val navigator = LocalNavigator.current
    val toast = LocalToast.current
    val scope = rememberCoroutineScope()
    val touch = isTouch
    val settings by container.settingsRepository.settings.collectAsStateWithLifecycle(initialValue = AppSettings())
    val playlistId = settings.activePlaylistId

    val continueMovies = remember(playlistId) { movieFeed(container, playlistId, Category.CONTINUE) }.collectAsLazyPagingItems()
    val allMovies = remember(playlistId) { movieFeed(container, playlistId, Category.ALL) }.collectAsLazyPagingItems()
    val favoriteMovies = remember(playlistId) { movieFeed(container, playlistId, Category.FAVORITES) }.collectAsLazyPagingItems()
    val continueSeries = remember(playlistId) { seriesFeed(container, playlistId, Category.CONTINUE) }.collectAsLazyPagingItems()
    val allSeries = remember(playlistId) { seriesFeed(container, playlistId, Category.ALL) }.collectAsLazyPagingItems()
    val favoriteSeries = remember(playlistId) { seriesFeed(container, playlistId, Category.FAVORITES) }.collectAsLazyPagingItems()
    val liveChannels = remember(playlistId) { channelFeed(container, playlistId) }.collectAsLazyPagingItems()

    val tmdbState by produceState<TmdbLoadState>(
        initialValue = if (settings.tmdbActive) TmdbLoadState.Loading else TmdbLoadState.Disabled,
        settings.tmdbActive,
        settings.tmdbApiKey.hashCode(),
    ) {
        if (!settings.tmdbActive) {
            value = TmdbLoadState.Disabled
        } else {
            value = TmdbLoadState.Loading
            value = try {
                TmdbLoadState.Ready(container.tmdbRepository.homeRecommendations())
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                TmdbLoadState.Error
            }
        }
    }
    val tmdbCatalog = (tmdbState as? TmdbLoadState.Ready)?.recommendations ?: TmdbHomeRecommendations()
    val newestMovies = remember(tmdbCatalog.newestMovies) { tmdbCatalog.newestMovies.map(::recommendationEntry) }
    val topRatedMovies = remember(tmdbCatalog.topRatedMovies) { tmdbCatalog.topRatedMovies.map(::recommendationEntry) }
    val newestSeries = remember(tmdbCatalog.newestSeries) { tmdbCatalog.newestSeries.map(::recommendationEntry) }
    val topRatedSeries = remember(tmdbCatalog.topRatedSeries) { tmdbCatalog.topRatedSeries.map(::recommendationEntry) }

    val featuredEntries = buildList {
        continueMovies.peekFirst()?.let { add(movieEntry(it)) }
        continueSeries.peekFirst()?.let { add(seriesEntry(it)) }
        addAll(newestMovies)
        addAll(newestSeries)
        addAll(topRatedMovies)
        addAll(topRatedSeries)
        allMovies.peekFirst()?.let { add(movieEntry(it)) }
        allSeries.peekFirst()?.let { add(seriesEntry(it)) }
        liveChannels.peekFirst()?.let { add(channelEntry(it)) }
    }.distinctBy { it.target.type to it.target.id }.take(12)
    var focusedFeatured by remember(playlistId) { mutableStateOf<HomeEntry?>(null) }
    val featured = focusedFeatured?.let { focused ->
        featuredEntries.firstOrNull { it.target.type == focused.target.type && it.target.id == focused.target.id }
    } ?: featuredEntries.firstOrNull()
    val localLoading = playlistId != null && listOf(
        continueMovies.loadState.refresh,
        continueSeries.loadState.refresh,
        allMovies.loadState.refresh,
        allSeries.loadState.refresh,
    ).any { it is LoadState.Loading }
    val loading = featured == null && (localLoading || tmdbState is TmdbLoadState.Loading)
    // TTFD: Startseite bedienbar, sobald der Hero-Titel steht (oder feststeht, dass es keinen gibt).
    ReportDrawnWhen { !loading }

    val favoriteFlow = remember(featured?.target?.id, featured?.target?.type) {
        when (val target = featured?.target) {
            is HomeTarget.RecommendationTarget, null -> flowOf(false)
            else -> container.playbackRepository.isFavorite(target.type, target.id)
        }
    }
    val isFavorite by favoriteFlow.collectAsState(initial = false)

    fun openDetails(entry: HomeEntry) {
        when (val target = entry.target) {
            is HomeTarget.MovieTarget -> navigator.push(Screen.MovieDetail(target.movie.id))
            is HomeTarget.SeriesTarget -> navigator.push(Screen.SeriesDetail(target.series.id))
            is HomeTarget.ChannelTarget -> onOpenSection(HomeViewModel.SECTION_LIVE)
            is HomeTarget.RecommendationTarget -> navigator.push(Screen.TmdbCatalogTitle(target.recommendation))
        }
    }

    fun play(entry: HomeEntry) {
        when (val target = entry.target) {
            is HomeTarget.MovieTarget -> {
                val resume = target.movie.progress?.let { progress ->
                    (progress * (target.movie.durationSec ?: 0) * 1_000).toLong()
                } ?: 0L
                navigator.push(Screen.Player(PlayRequest.Movie(target.movie.id, resume)))
            }
            // Serien starten bewusst in der Detailansicht, wo Staffel und Folge gewählt werden.
            is HomeTarget.SeriesTarget -> navigator.push(Screen.SeriesDetail(target.series.id))
            is HomeTarget.ChannelTarget -> navigator.push(Screen.Player(PlayRequest.Live(target.channel.id, Category.ALL)))
            // TMDB-Katalogeinträge sind noch keinem Stream zugeordnet. Erst auf der Titelseite
            // werden alle Playlists durchsucht und dort die abspielbaren Fassungen angeboten.
            is HomeTarget.RecommendationTarget -> navigator.push(Screen.TmdbCatalogTitle(target.recommendation))
        }
    }

    fun toggleFeaturedFavorite(entry: HomeEntry) {
        val target = entry.target
        if (target is HomeTarget.RecommendationTarget) return
        scope.launch {
            container.playbackRepository.toggleFavorite(target.type, target.id)
            toast.show(if (isFavorite) "Aus „Meine Liste“ entfernt" else "Zu „Meine Liste“ hinzugefügt")
        }
    }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = if (touch) 28.dp else 24.dp),
        verticalArrangement = Arrangement.spacedBy(if (touch) 22.dp else 14.dp),
    ) {
        item(key = "hero") {
            if (touch) {
                HeroBanner(
                    entry = featured,
                    loading = loading,
                    noPlaylist = playlistId == null,
                    favorite = isFavorite,
                    modifier = Modifier.padding(horizontal = 12.dp),
                    playRequester = homeFocusRequester,
                    onAddPlaylist = { navigator.push(Screen.AddPlaylist) },
                    onPlay = { featured?.let(::play) },
                    onDetails = { featured?.let(::openDetails) },
                    onToggleFavorite = { featured?.let(::toggleFeaturedFavorite) },
                    onOpenSection = { onOpenSection(it) },
                )
            } else {
                TvFeaturedSection(
                    entries = featuredEntries,
                    entry = featured,
                    loading = loading,
                    noPlaylist = playlistId == null,
                    favorite = isFavorite,
                    playRequester = homeFocusRequester,
                    onFocus = { focusedFeatured = it },
                    onAddPlaylist = { navigator.push(Screen.AddPlaylist) },
                    onPlay = { featured?.let(::play) },
                    onDetails = { featured?.let(::openDetails) },
                    onToggleFavorite = { featured?.let(::toggleFeaturedFavorite) },
                    onOpenSection = { onOpenSection(it) },
                )
            }
        }
        if (!settings.tmdbActive && playlistId != null) {
            item(key = "tmdb-setup") {
                TmdbSetupPrompt { onOpenSection(HomeViewModel.SECTION_SETTINGS) }
            }
        }
        item(key = "continue-movies") {
            ContentRow(
                "Weiterschauen · Filme",
                continueMovies,
                ::movieEntry,
                "Noch keine Filme angefangen",
                { onOpenSection(HomeViewModel.SECTION_MOVIES) },
                ::openDetails,
            )
        }
        item(key = "continue-series") {
            ContentRow(
                "Weiterschauen · Serien",
                continueSeries,
                ::seriesEntry,
                "Noch keine Serien angefangen",
                { onOpenSection(HomeViewModel.SECTION_SERIES) },
                ::openDetails,
            )
        }
        if (settings.tmdbActive) {
            item(key = "tmdb-new-movies") {
                TmdbContentRow("Neueste Filme", newestMovies, tmdbState, { onOpenSection(HomeViewModel.SECTION_MOVIES) }, ::openDetails, showAttribution = true)
            }
            item(key = "tmdb-top-movies") {
                TmdbContentRow("Top-bewertete Filme", topRatedMovies, tmdbState, { onOpenSection(HomeViewModel.SECTION_MOVIES) }, ::openDetails)
            }
            item(key = "tmdb-new-series") {
                TmdbContentRow("Neueste Serien", newestSeries, tmdbState, { onOpenSection(HomeViewModel.SECTION_SERIES) }, ::openDetails)
            }
            item(key = "tmdb-top-series") {
                TmdbContentRow("Top-bewertete Serien", topRatedSeries, tmdbState, { onOpenSection(HomeViewModel.SECTION_SERIES) }, ::openDetails)
            }
        }
        item(key = "movies") {
            ContentRow(
                "Filme aus deiner Bibliothek",
                allMovies,
                ::movieEntry,
                "Keine Filme vorhanden",
                { onOpenSection(HomeViewModel.SECTION_MOVIES) },
                ::openDetails,
            )
        }
        item(key = "series") {
            ContentRow(
                "Serien aus deiner Bibliothek",
                allSeries,
                ::seriesEntry,
                "Keine Serien vorhanden",
                { onOpenSection(HomeViewModel.SECTION_SERIES) },
                ::openDetails,
            )
        }
        item(key = "favorite-movies") {
            ContentRow(
                "Meine Liste · Filme",
                favoriteMovies,
                ::movieEntry,
                "Noch keine Filme gespeichert",
                { onOpenSection(HomeViewModel.SECTION_MOVIES) },
                ::openDetails,
            )
        }
        item(key = "favorite-series") {
            ContentRow(
                "Meine Liste · Serien",
                favoriteSeries,
                ::seriesEntry,
                "Noch keine Serien gespeichert",
                { onOpenSection(HomeViewModel.SECTION_SERIES) },
                ::openDetails,
            )
        }
        item(key = "live") {
            ContentRow(
                "Live-TV & aktuelles Programm",
                liveChannels,
                ::channelEntry,
                "Keine Sender vorhanden",
                { onOpenSection(HomeViewModel.SECTION_LIVE) },
                ::play,
            )
        }
    }
}
