package com.mg.player.domain.repository

import androidx.paging.PagingData
import com.mg.player.domain.model.AppSettings
import com.mg.player.domain.model.Category
import com.mg.player.domain.model.Channel
import com.mg.player.domain.model.ContentType
import com.mg.player.domain.model.EpgProgram
import com.mg.player.domain.model.Episode
import com.mg.player.domain.model.IntroProfile
import com.mg.player.domain.model.Movie
import com.mg.player.domain.model.PlaybackProgress
import com.mg.player.domain.model.Playlist
import com.mg.player.domain.model.PlaylistType
import com.mg.player.domain.model.SearchHit
import com.mg.player.domain.model.SearchPages
import com.mg.player.domain.model.Series
import com.mg.player.domain.model.TmdbCandidate
import com.mg.player.domain.model.TmdbHomeRecommendations
import com.mg.player.domain.model.TmdbInfo
import com.mg.player.domain.model.TmdbLibraryMatch
import com.mg.player.domain.model.TmdbRecommendation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

data class PlaylistInput(
    val name: String,
    val type: PlaylistType,
    val serverUrl: String = "",
    val username: String = "",
    val password: String = "",
    val m3uUrl: String = "",
    val epgUrl: String = "",
    val allowInsecureHttp: Boolean = false,
)

/**
 * Geänderte Daten eines bestehenden Zugangs. [password] und [m3uUrl] leer = unverändert (sie werden nie angezeigt).
 * Die Art (Xtream/M3U) bleibt fest.
 */
data class PlaylistEdit(
    val name: String,
    val serverUrl: String = "",
    val username: String = "",
    val password: String = "",
    val m3uUrl: String = "",
    val epgUrl: String = "",
    val allowInsecureHttp: Boolean = false,
    val autoRefreshDays: Int = 0,
)

/** Was eine Bearbeitung geändert hat – entscheidet, ob neu geladen werden muss. */
data class PlaylistEditResult(val credentialsChanged: Boolean, val epgChanged: Boolean)

/** Fortschritt einer Aktualisierung. [warning]: Hinweis, der nach dem Ende stehen bleiben soll. */
data class ImportProgress(val message: String, val count: Int = 0, val warning: String? = null)

interface PlaylistRepository {
    fun observePlaylists(): Flow<List<Playlist>>
    suspend fun getPlaylist(id: Long): Playlist?
    suspend fun addPlaylist(input: PlaylistInput): Long
    suspend fun deletePlaylist(id: Long)
    suspend fun setAllowInsecureHttp(id: Long, allowed: Boolean)

    /** Zugang bearbeiten; die ID (und damit Favoriten, Verlauf, Sperren) bleibt. Wirft, wenn es ihn nicht gibt. */
    suspend fun updatePlaylist(id: Long, edit: PlaylistEdit): PlaylistEditResult

    /** Automatische Komplett-Aktualisierung alle [days] Tage (0 = aus, höchstens AutoRefreshSchedule.MAX_DAYS). */
    suspend fun setAutoRefreshDays(id: Long, days: Int)

    /** EPG-Zeitverschiebung dieses Zugangs in Stunden (auf ±[com.mg.player.domain.model.EPG_SHIFT_MAX_HOURS] begrenzt). */
    suspend fun setEpgShiftHours(id: Long, hours: Int)

    /** Lädt Sender, Filme und Serien neu. Wirft bei Netzwerk- oder Login-Fehlern eine Exception. */
    suspend fun refreshContent(playlistId: Long, onProgress: (ImportProgress) -> Unit)
}

interface LiveRepository {
    fun categories(playlistId: Long): Flow<List<Category>>

    /** [startAt] = Position, ab der die Liste zuerst geladen wird (z. B. zurück zum zuletzt gesehenen Sender). */
    fun channels(playlistId: Long, categoryId: String, startAt: Int = 0): Flow<PagingData<Channel>>
    suspend fun channel(id: String): Channel?
    suspend fun channelIds(playlistId: Long, categoryId: String): List<String>

    suspend fun channelPosition(playlistId: Long, categoryId: String, channelId: String): Int =
        channelIds(playlistId, categoryId).indexOf(channelId)

    /** Alle Stream-Varianten eines Senders, beste Qualität zuerst; mindestens der Sender selbst. */
    suspend fun variants(channel: Channel): List<Channel>
}

interface VodRepository {
    fun categories(playlistId: Long): Flow<List<Category>>
    fun movies(playlistId: Long, categoryId: String): Flow<PagingData<Movie>>
    suspend fun movie(id: String): Movie?

    /** Holt Plot, Backdrop und Laufzeit (Xtream get_vod_info) und cached sie. */
    suspend fun loadDetails(id: String): Movie?
}

interface SeriesRepository {
    fun categories(playlistId: Long): Flow<List<Category>>
    fun series(playlistId: Long, categoryId: String): Flow<PagingData<Series>>
    suspend fun seriesById(id: String): Series?

    /** Holt Staffeln & Episoden (Xtream get_series_info) und speichert sie in Room. */
    suspend fun loadEpisodes(seriesId: String): List<Episode>
    fun observeEpisodes(seriesId: String): Flow<List<Episode>>
    suspend fun episode(id: String): Episode?
    suspend fun episodes(seriesId: String): List<Episode>
}

/** Persönliche Kategorie-Einstellungen (ausblenden, sperren, anheften). */
interface CategoryRepository {
    fun observeAll(playlistId: Long, type: ContentType): Flow<List<Category>>
    suspend fun save(playlistId: Long, category: Category)

    /** Eigene Reihenfolge speichern: [orderedIds] von oben nach unten (alle Gruppen einer Art). */
    suspend fun saveOrder(playlistId: Long, orderedIds: List<String>)

    /** Zurück zur Reihenfolge des Anbieters. */
    suspend fun resetOrder(playlistId: Long, ids: List<String>)

    /** Ist die Gruppe durch die Kindersicherung gesperrt? (Virtuelle Listen wie "Favoriten" nie.) */
    suspend fun isLocked(categoryId: String): Boolean
}

/** TMDB-Zusatzinfos: Bewertung, Genres, FSK, Besetzung, Trailer, ähnliche Titel. */
interface TmdbRepository {
    fun observe(type: ContentType, itemId: String): Flow<TmdbInfo?>

    /** Aktuelle und am besten bewertete Filme/Serien direkt aus TMDB, unabhängig von lokalen Playlists. */
    suspend fun homeRecommendations(): TmdbHomeRecommendations

    /** Sucht nach dem Öffnen eines TMDB-Titels in allen Playlists nach exakt passenden, abspielbaren Fassungen. */
    suspend fun findPlayableCopies(recommendation: TmdbRecommendation): List<TmdbLibraryMatch>

    /** Lädt die Daten, falls sie fehlen oder veraltet sind. Liefert eine Fehlermeldung oder null. */
    suspend fun refresh(type: ContentType, itemId: String, force: Boolean = false): String?
    suspend fun search(type: ContentType, query: String): List<TmdbCandidate>
    suspend fun assign(type: ContentType, itemId: String, tmdbId: Int): String?
    suspend fun similarInLibrary(type: ContentType, info: TmdbInfo, excludeId: String): List<SearchHit>
    suspend fun clearCache()
}

interface SearchRepository {
    suspend fun search(playlistId: Long, query: String): List<SearchHit>

    /** Sucht in allen Zugängen; gleiche Titel stehen nebeneinander, der beste (Qualität, aktiver Zugang) zuerst. */
    suspend fun searchAll(query: String, activePlaylistId: Long?): List<SearchHit>

    /** Wie [searchAll], zusätzlich die Treffer je Playlist für die Seiten der Suche. */
    suspend fun searchPages(query: String, activePlaylistId: Long?): SearchPages

    /** Teilergebnisse bis alle Zugänge durchsucht sind. */
    fun observeSearchPages(query: String, activePlaylistId: Long?) = flow {
        emit(searchPages(query, activePlaylistId))
    }

    /** Suchindex im Hintergrund aufbauen, falls er fehlt oder ein älteres Format hat. */
    suspend fun prepare(playlistId: Long)
}

interface EpgRepository {
    suspend fun refresh(playlist: Playlist, onProgress: (ImportProgress) -> Unit)
    suspend fun programs(channel: Channel, fromMs: Long, toMs: Long): List<EpgProgram>
    suspend fun clear()

    /** Gibt es für diesen Zugang schon gespeichertes EPG? */
    suspend fun hasPrograms(playlistId: Long): Boolean

    /** EPG fehlt (letzter Versuch > 12 h) oder ist älter als 24 h: von selbst nachladen. */
    suspend fun shouldAutoLoad(playlistId: Long): Boolean

    /** Was der Anbieter gerade für diesen Sender meldet (Xtream get_short_epg; leer bei M3U oder Fehler). */
    suspend fun liveShortEpg(channelId: String): List<EpgProgram> = emptyList()
}

interface PlaybackRepository {
    suspend fun progress(key: String): PlaybackProgress?
    fun observeProgress(key: String): Flow<PlaybackProgress?>
    fun observeSeriesProgress(seriesId: String): Flow<Map<String, PlaybackProgress>>
    suspend fun lastWatchedInSeries(seriesId: String): PlaybackProgress?
    suspend fun saveProgress(key: String, positionMs: Long, durationMs: Long, seriesId: String?)
    suspend fun markWatched(key: String, durationMs: Long, seriesId: String?)

    /** Merkt den Sender für die Kategorie "Zuletzt gesehen". */
    suspend fun markChannelWatched(channelId: String)
    suspend fun introProfile(seriesId: String): IntroProfile?
    suspend fun saveIntroProfile(profile: IntroProfile)
    fun isFavorite(type: ContentType, itemId: String): Flow<Boolean>
    suspend fun toggleFavorite(type: ContentType, itemId: String)
    suspend fun clearHistory()
}

interface SettingsRepository {
    val settings: Flow<AppSettings>
    suspend fun current(): AppSettings
    suspend fun update(transform: (AppSettings) -> AppSettings)
}
