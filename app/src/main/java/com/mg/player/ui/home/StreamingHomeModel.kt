package com.mg.player.ui.home

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.paging.PagingData
import com.mg.player.domain.model.Category
import com.mg.player.domain.model.Channel
import com.mg.player.domain.model.ContentType
import com.mg.player.domain.model.Movie
import com.mg.player.domain.model.Series
import com.mg.player.domain.model.TmdbHomeRecommendations
import com.mg.player.domain.model.TmdbRecommendation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

internal fun movieFeed(container: com.mg.player.core.di.AppContainer, playlistId: Long?, category: String): Flow<PagingData<Movie>> =
    playlistId?.let { container.vodRepository.movies(it, category) } ?: flowOf(PagingData.empty())

internal fun seriesFeed(container: com.mg.player.core.di.AppContainer, playlistId: Long?, category: String): Flow<PagingData<Series>> =
    playlistId?.let { container.seriesRepository.series(it, category) } ?: flowOf(PagingData.empty())

internal fun channelFeed(container: com.mg.player.core.di.AppContainer, playlistId: Long?): Flow<PagingData<Channel>> =
    playlistId?.let { container.liveRepository.channels(it, Category.ALL) } ?: flowOf(PagingData.empty())

internal sealed interface HomeTarget {
    val id: String
    val type: ContentType
    data class MovieTarget(val movie: Movie) : HomeTarget {
        override val id get() = movie.id
        override val type = ContentType.MOVIE
    }
    data class SeriesTarget(val series: Series) : HomeTarget {
        override val id get() = series.id
        override val type = ContentType.SERIES
    }
    data class ChannelTarget(val channel: Channel) : HomeTarget {
        override val id get() = channel.id
        override val type = ContentType.LIVE
    }
    data class RecommendationTarget(val recommendation: TmdbRecommendation) : HomeTarget {
        override val id get() = "tmdb:${recommendation.type}:${recommendation.tmdbId}"
        override val type get() = recommendation.type
    }
}

internal sealed interface TmdbLoadState {
    data object Disabled : TmdbLoadState
    data object Loading : TmdbLoadState
    data object Error : TmdbLoadState
    data class Ready(val recommendations: TmdbHomeRecommendations) : TmdbLoadState
}

internal data class HomeEntry(
    val id: String,
    val title: String,
    val imageUrl: String?,
    val posterUrl: String?,
    val subtitle: String?,
    val description: String?,
    val target: HomeTarget,
)

internal fun movieEntry(movie: Movie) = HomeEntry(
    id = movie.id,
    title = movie.name,
    imageUrl = movie.backdropUrl ?: movie.posterUrl,
    posterUrl = movie.posterUrl ?: movie.backdropUrl,
    subtitle = listOfNotNull(movie.year, movie.rating?.takeIf { it > 0f }?.let { "★ %.1f".format(it) }).joinToString(" · ").ifBlank { null },
    description = movie.plot,
    target = HomeTarget.MovieTarget(movie),
)

internal fun seriesEntry(series: Series) = HomeEntry(
    id = series.id,
    title = series.name,
    imageUrl = series.backdropUrl ?: series.posterUrl,
    posterUrl = series.posterUrl ?: series.backdropUrl,
    subtitle = listOfNotNull(series.year, series.rating?.takeIf { it > 0f }?.let { "★ %.1f".format(it) }).joinToString(" · ").ifBlank { null },
    description = series.plot,
    target = HomeTarget.SeriesTarget(series),
)

internal fun channelEntry(channel: Channel) = HomeEntry(
    channel.id,
    channel.name,
    channel.logoUrl,
    channel.logoUrl,
    channel.nowTitle?.let { "Jetzt: $it" } ?: "Programm gerade nicht verfügbar",
    channel.nowTitle,
    HomeTarget.ChannelTarget(channel),
)

internal fun recommendationEntry(item: TmdbRecommendation) = HomeEntry(
    id = "tmdb:${item.type}:${item.tmdbId}",
    title = item.title,
    imageUrl = item.backdropUrl ?: item.posterUrl,
    posterUrl = item.posterUrl ?: item.backdropUrl,
    subtitle = listOfNotNull(item.year, item.rating?.let { "★ %.1f · TMDB".format(it) }).joinToString(" · ").ifBlank { "TMDB" },
    description = item.overview,
    target = HomeTarget.RecommendationTarget(item),
)
