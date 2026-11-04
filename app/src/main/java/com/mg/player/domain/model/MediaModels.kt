package com.mg.player.domain.model

import androidx.compose.runtime.Immutable

enum class PlaylistType { XTREAM, M3U }

enum class ContentType { LIVE, MOVIE, SERIES }

/** Grenze der EPG-Zeitverschiebung je Zugang in Stunden (beide Richtungen). */
const val EPG_SHIFT_MAX_HOURS = 12

@Immutable
data class Playlist(
    val id: Long,
    val name: String,
    val type: PlaylistType,
    val serverUrl: String,
    val username: String,
    val password: String,
    val m3uUrl: String,
    val customEpgUrl: String,
    val headerEpgUrl: String,
    val lastUpdatedMs: Long,
    val allowInsecureHttp: Boolean = false,
    /** Automatische Komplett-Aktualisierung alle so viele Tage seit [lastUpdatedMs]; 0 = aus. */
    val autoRefreshDays: Int = 0,
    /** Korrektur falscher Sendezeiten dieses Anbieters in Stunden. */
    val epgShiftHours: Int = 0,
)

@Immutable
data class Category(
    val id: String,
    val name: String,
    val type: ContentType,
    val locked: Boolean = false,
    val pinned: Boolean = false,
    val hidden: Boolean = false,
) {
    val isVirtual: Boolean get() = id.startsWith("__")

    companion object {
        const val ALL = "__all__"
        const val FAVORITES = "__fav__"
        const val CONTINUE = "__continue__"
        const val RECENT = "__recent__"

        /** Sender, die zu dieser Tageszeit oft laufen (Sehgewohnheiten). */
        const val FREQUENT = "__frequent__"
    }
}

@Immutable
data class Channel(
    val id: String,
    val name: String,
    val logoUrl: String?,
    val streamUrl: String,
    val categoryId: String,
    val epgKey: String,
    val nowTitle: String?,
    val nowStartMs: Long?,
    val nowEndMs: Long?,
    val catchupDays: Int = 0,
    val playlistId: Long = 0,
    /** Gleicher Sender in anderer Qualität hat denselben Schlüssel (leer = keine Gruppierung). */
    val groupKey: String = "",
    /** Qualitätsangabe aus dem Anbieternamen; kein Messwert der decodierten Auflösung. */
    val quality: String? = null,
    /** Wie viele Stream-Varianten unter dieser Kachel zusammengefasst sind. */
    val variantCount: Int = 1,
    /** Position in der angezeigten Senderliste (0 = erster), -1 = unbekannt. */
    val listPosition: Int = -1,
    /** Zugang, aus dem das EPG kommt (bei „Alle Zugänge“ der Original-Zugang; sonst [playlistId]). */
    val epgPlaylistId: Long = playlistId,
    /** Name genau wie vom Anbieter geliefert („DE - PROSIEBEN UHD“) – für die Quellen-Liste. */
    val originalName: String = name,
    /** Alle Qualitätsangaben aus dem Anbieternamen (4K, HEVC, RAW …). */
    val qualityBadges: List<String> = emptyList(),
) {
    val hasArchive: Boolean get() = catchupDays > 0

    /** Angezeigte Nummer (1 = erster); ohne bekannte Position die Stelle in den geladenen Zeilen. */
    fun listNumber(loadedIndex: Int): Int = if (listPosition >= 0) listPosition + 1 else loadedIndex + 1
}

@Immutable
data class Movie(
    val id: String,
    val name: String,
    val posterUrl: String?,
    val rating: Float?,
    val year: String?,
    val streamUrl: String,
    val plot: String?,
    val backdropUrl: String?,
    val durationSec: Int?,
    val remoteId: String,
    /** Herausgelöste Qualitätsmerkmale, z. B. ["4K", "HEVC"]. */
    val badges: List<String> = emptyList(),
    /** Fortschritt 0..1 für den Balken auf dem Poster (null = nicht angefangen oder fertig). */
    val progress: Float? = null,
)

@Immutable
data class Series(
    val id: String,
    val name: String,
    val posterUrl: String?,
    val rating: Float?,
    val year: String?,
    val plot: String?,
    val backdropUrl: String?,
    val remoteId: String,
    val badges: List<String> = emptyList(),
)

@Immutable
data class Episode(
    val id: String,
    val seriesId: String,
    val season: Int,
    val episode: Int,
    val title: String,
    val streamUrl: String,
    val imageUrl: String?,
    val plot: String?,
    val durationSec: Int?,
) {
    val code: String get() = "S%02dE%02d".format(season, episode)
}

@Immutable
data class EpgProgram(val title: String, val description: String?, val startMs: Long, val endMs: Long)

/** Kompakter Listeneintrag für Suchergebnisse über alle Bereiche. */
@Immutable
data class SearchHit(
    val id: String,
    val type: ContentType,
    val title: String,
    val imageUrl: String?,
    /** Qualitäts-Abzeichen aus dem Anbieter-Titel, z. B. "4K". */
    val badges: List<String> = emptyList(),
    /** Zweite Zeile, z. B. Jahr oder "3 Quellen". */
    val subtitle: String? = null,
    val rating: Float? = null,
    /** Zugang des Treffers – gesetzt bei der Suche über alle Zugänge. */
    val playlistId: Long = 0,
    /** Name dieses Zugangs (nur bei mehreren Zugängen). */
    val source: String? = null,
)

/** Treffer einer einzelnen Playlist (eine Seite der Suche). */
@Immutable
data class PlaylistHits(val playlistId: Long, val name: String, val hits: List<SearchHit>)

/** Ergebnis der Suche: „Alle Playlists“ zusammengeführt plus eine Seite je Playlist (auch ohne Treffer). */
@Immutable
data class SearchPages(val all: List<SearchHit>, val playlists: List<PlaylistHits>)
