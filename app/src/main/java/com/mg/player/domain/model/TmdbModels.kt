package com.mg.player.domain.model

import androidx.compose.runtime.Immutable

@Immutable
data class CastMember(val name: String, val character: String?, val photoUrl: String?)

/** Zusatzinfos aus The Movie Database (TMDB) für einen Film oder eine Serie. */
@Immutable
data class TmdbInfo(
    val tmdbId: Int,
    val title: String?,
    val overview: String?,
    val rating: Float?,
    val voteCount: Int,
    val genres: List<String>,
    val runtimeMin: Int?,
    val year: String?,
    val certification: String?,
    val trailerKey: String?,
    val posterUrl: String?,
    val backdropUrl: String?,
    val cast: List<CastMember>,
    val similarTitles: List<String>,
) {
    val trailerUrl: String? get() = trailerKey?.let { "https://www.youtube.com/watch?v=$it" }
}

/** Treffer der manuellen Suche ("Falscher Titel? Anders zuordnen"). */
@Immutable
data class TmdbCandidate(val id: Int, val title: String, val year: String?, val posterUrl: String?, val overview: String?)

enum class TmdbHomeFeed { NEWEST, TOP_RATED }

/** Katalogtitel von TMDB; eine lokale IPTV-Fassung wird erst beim Öffnen des Titels gesucht. */
@Immutable
data class TmdbRecommendation(
    val tmdbId: Int,
    val type: ContentType,
    val title: String,
    val year: String?,
    val rating: Float?,
    val voteCount: Int,
    val overview: String?,
    val posterUrl: String?,
    val backdropUrl: String?,
    val originalTitle: String? = null,
    val genres: List<String> = emptyList(),
)

/** Eine gefundene, direkt abspielbare Fassung in einem bestimmten IPTV-Zugang. */
@Immutable
data class TmdbLibraryMatch(
    val playlistId: Long,
    val playlistName: String,
    val itemId: String,
    val title: String,
    val imageUrl: String?,
    val year: String?,
    val badges: List<String>,
    /** Ähnlicher Treffer durch eine abweichende Anbieter-Schreibweise; vor dem Start sichtbar gekennzeichnet. */
    val isApproximate: Boolean = false,
)

@Immutable
data class TmdbHomeRecommendations(
    val newestMovies: List<TmdbRecommendation> = emptyList(),
    val topRatedMovies: List<TmdbRecommendation> = emptyList(),
    val newestSeries: List<TmdbRecommendation> = emptyList(),
    val topRatedSeries: List<TmdbRecommendation> = emptyList(),
)
