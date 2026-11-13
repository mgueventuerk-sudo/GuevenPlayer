package com.mg.player.data.tmdb

import com.mg.player.core.log.AppLog
import com.mg.player.core.util.MediaTitle
import com.mg.player.core.util.runCatchingCancellable
import com.mg.player.data.db.AppDatabase
import com.mg.player.data.db.entity.TmdbCacheEntity
import com.mg.player.data.search.SearchText
import com.mg.player.domain.model.CastMember
import com.mg.player.domain.model.ContentType
import com.mg.player.domain.model.PlaylistHits
import com.mg.player.domain.model.SearchHit
import com.mg.player.domain.model.TmdbCandidate
import com.mg.player.domain.model.TmdbHomeFeed
import com.mg.player.domain.model.TmdbHomeRecommendations
import com.mg.player.domain.model.TmdbInfo
import com.mg.player.domain.model.TmdbLibraryMatch
import com.mg.player.domain.model.TmdbRecommendation
import com.mg.player.domain.repository.SearchRepository
import com.mg.player.domain.repository.SettingsRepository
import com.mg.player.domain.repository.TmdbRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Ordnet Filme/Serien einem TMDB-Eintrag zu – über die tmdb_id des Anbieters oder per Titelsuche –
 * und speichert das Ergebnis lokal. Abgefragt wird nur, wenn eine Detailseite geöffnet wird.
 */
class TmdbRepositoryImpl(
    private val db: AppDatabase,
    private val api: TmdbApi,
    private val settings: SettingsRepository,
    private val search: SearchRepository,
) : TmdbRepository {

    private data class HomeCacheKey(val keyFingerprint: Int)
    private data class HomeCache(val key: HomeCacheKey, val savedAtMs: Long, val value: TmdbHomeRecommendations)

    private val homeMutex = Mutex()
    @Volatile private var homeCache: HomeCache? = null

    private fun key(type: ContentType, itemId: String) = (if (type == ContentType.SERIES) "series:" else "movie:") + itemId
    private fun kind(type: ContentType) = if (type == ContentType.SERIES) TmdbApi.Kind.TV else TmdbApi.Kind.MOVIE

    override fun observe(type: ContentType, itemId: String): Flow<TmdbInfo?> = db.tmdbDao().observe(key(type, itemId)).map { it?.toInfo() }

    override suspend fun homeRecommendations(): TmdbHomeRecommendations = withContext(Dispatchers.IO) {
        val current = settings.current()
        val apiKey = current.takeIf { it.tmdbActive }?.tmdbApiKey?.trim() ?: return@withContext TmdbHomeRecommendations()
        val cacheKey = HomeCacheKey(apiKey.hashCode())
        val now = System.currentTimeMillis()
        homeCache?.takeIf { it.key == cacheKey && now - it.savedAtMs < HOME_CACHE_TTL_MS }?.let { return@withContext it.value }

        homeMutex.withLock {
            val refreshedAt = System.currentTimeMillis()
            homeCache?.takeIf { it.key == cacheKey && refreshedAt - it.savedAtMs < HOME_CACHE_TTL_MS }?.let { return@withLock it.value }
            val feeds = coroutineScope {
                listOf(
                    async { homeFeed(apiKey, ContentType.MOVIE, TmdbHomeFeed.NEWEST) },
                    async { homeFeed(apiKey, ContentType.MOVIE, TmdbHomeFeed.TOP_RATED) },
                    async { homeFeed(apiKey, ContentType.SERIES, TmdbHomeFeed.NEWEST) },
                    async { homeFeed(apiKey, ContentType.SERIES, TmdbHomeFeed.TOP_RATED) },
                ).map { it.await() }
            }
            val preferred = preferredGenresFromFavorites()
            TmdbHomeRecommendations(
                newestMovies = GenreRanking.rank(feeds[0], preferred) { it.genres },
                topRatedMovies = GenreRanking.rank(feeds[1], preferred) { it.genres },
                newestSeries = GenreRanking.rank(feeds[2], preferred) { it.genres },
                topRatedSeries = GenreRanking.rank(feeds[3], preferred) { it.genres },
            ).also { result ->
                homeCache = HomeCache(cacheKey, System.currentTimeMillis(), result)
            }
        }
    }

    /** Häufigste Genres der eigenen Favoriten, soweit ihr TMDB-Eintrag schon zwischengespeichert ist. */
    private suspend fun preferredGenresFromFavorites(): Set<String> {
        val genresPerTitle = db.favoriteDao().all()
            .filter { it.type == ContentType.MOVIE.name || it.type == ContentType.SERIES.name }
            .mapNotNull { fav ->
                val type = if (fav.type == ContentType.SERIES.name) ContentType.SERIES else ContentType.MOVIE
                db.tmdbDao().get(key(type, fav.itemId))?.genres?.split(SEP)?.filter { it.isNotBlank() }
            }
        return GenreRanking.preferredGenres(genresPerTitle)
    }

    private suspend fun homeFeed(
        apiKey: String,
        type: ContentType,
        feed: TmdbHomeFeed,
    ): List<TmdbRecommendation> {
        return api.homeCatalog(apiKey, kind(type), feed)
            .asSequence()
            .filter { it.id > 0 && TitleCleaner.normalize(it.title).length >= 2 }
            .distinctBy { it.id }
            .take(HOME_ROW_LIMIT)
            .map { candidate ->
            TmdbRecommendation(
                tmdbId = candidate.id,
                type = type,
                title = candidate.title,
                originalTitle = candidate.originalTitle,
                year = candidate.year,
                rating = candidate.rating,
                voteCount = candidate.voteCount,
                overview = candidate.overview,
                posterUrl = candidate.posterPath?.let(TmdbApi::posterUrl),
                backdropUrl = candidate.backdropPath?.let(TmdbApi::backdropUrl),
                genres = TmdbGenreNames.of(candidate.genreIds),
            )
        }.toList()
    }

    /** Treffer der eigenen Suche → Kopie des TMDB-Titels (gleicher Typ, passendes Jahr, Titel gleich oder ähnlich). */
    private fun libraryMatch(recommendation: TmdbRecommendation, page: PlaylistHits, hit: SearchHit): TmdbLibraryMatch? {
        if (hit.type != recommendation.type) return null
        val localYear = hit.subtitle?.take(YEAR_DIGITS)?.takeIf { it.length == YEAR_DIGITS && it.all(Char::isDigit) }
        if (recommendation.year != null && localYear != null && recommendation.year != localYear) return null
        val titleMatch = TmdbTitleMatcher.compare(listOf(recommendation.title) + listOfNotNull(recommendation.originalTitle), hit.title)
            ?: return null
        return TmdbLibraryMatch(
            playlistId = page.playlistId,
            playlistName = page.name,
            itemId = hit.id,
            title = hit.title,
            imageUrl = hit.imageUrl,
            year = localYear ?: recommendation.year,
            badges = hit.badges,
            isApproximate = titleMatch == TmdbTitleMatcher.Match.APPROXIMATE,
        )
    }

    override suspend fun findPlayableCopies(recommendation: TmdbRecommendation): List<TmdbLibraryMatch> = withContext(Dispatchers.IO) {
        val titles = listOfNotNull(recommendation.title, recommendation.originalTitle)
            .map { TitleCleaner.clean(it).title }
            .filter { it.length >= 2 }
            .distinctBy { SearchText.comparable(it) }
        if (titles.isEmpty()) return@withContext emptyList()

        val activePlaylistId = settings.current().activePlaylistId
        val pagesByPlaylist = LinkedHashMap<Long, PlaylistHits>()
        suspend fun collect(query: String) {
            search.searchPages(query, activePlaylistId).playlists.forEach { page ->
                val previous = pagesByPlaylist[page.playlistId]
                pagesByPlaylist[page.playlistId] = page.copy(
                    hits = (previous?.hits.orEmpty() + page.hits).distinctBy { it.id },
                )
            }
        }
        titles.forEach { collect(it) }

        fun matches(): List<TmdbLibraryMatch> = pagesByPlaylist.values.asSequence()
            .flatMap { page -> page.hits.asSequence().map { page to it } }
            .mapNotNull { (page, hit) -> libraryMatch(recommendation, page, hit) }
            .distinctBy { it.playlistId to it.itemId }
            .sortedWith(compareBy<TmdbLibraryMatch> { it.isApproximate }.thenBy { if (it.playlistId == activePlaylistId) 0 else 1 })
            .toList()

        titles.forEach { title ->
            val playlistIdsWithoutMatch = pagesByPlaylist.keys - matches().map { it.playlistId }.toSet()
            if (playlistIdsWithoutMatch.isEmpty()) return@withContext matches()
            fallbackQueries(title).forEach { query ->
                collect(query)
                val foundPlaylistIds = matches().mapTo(HashSet()) { it.playlistId }
                if (pagesByPlaylist.keys.all { it in foundPlaylistIds }) return@withContext matches()
            }
        }
        matches()
    }

    /** Broadens search only when a playlist has no candidate, keeping large libraries on the indexed path. */
    private fun fallbackQueries(title: String): List<String> {
        val n = FALLBACK_PIECE
        val distinctive = SearchText.words(title).filter { it.length >= n }.maxByOrNull(String::length) ?: return emptyList()
        if (distinctive.length <= n) return listOf(distinctive)
        val middleStart = ((distinctive.length - n) / 2).coerceAtLeast(0)
        return listOf(distinctive.take(n), distinctive.substring(middleStart, middleStart + n), distinctive.takeLast(n)).distinct()
    }

    override suspend fun refresh(type: ContentType, itemId: String, force: Boolean): String? = withContext(Dispatchers.IO) {
        val apiKey = activeKey() ?: return@withContext null
        val cacheKey = key(type, itemId)
        val cached = db.tmdbDao().get(cacheKey)
        val now = System.currentTimeMillis()
        if (!force && cached != null) {
            val maxAge = if (cached.notFound) NOT_FOUND_TTL else CACHE_TTL
            if (cached.manual || now - cached.fetchedAtMs < maxAge) return@withContext null
        }
        try {
            val (name, providerId) = itemInfo(type, itemId) ?: return@withContext null
            val tmdbId = cached?.takeIf { it.manual }?.tmdbId ?: providerId ?: findByTitle(apiKey, type, name)
            if (tmdbId == null) {
                db.tmdbDao().upsert(emptyEntry(cacheKey, type, now))
                return@withContext null
            }
            store(apiKey, type, cacheKey, tmdbId, manual = cached?.manual == true)
            null
        } catch (e: TmdbApi.InvalidKeyException) {
            "Der TMDB-API-Schlüssel ist ungültig. Bitte in den Einstellungen prüfen."
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AppLog.debug("TMDB", "TMDB-Abfrage fehlgeschlagen (${e.javaClass.simpleName})")
            AppLog.log("TMDB", "Abfrage fehlgeschlagen: ${e.message}")
            null
        }
    }

    override suspend fun search(type: ContentType, query: String): List<TmdbCandidate> = withContext(Dispatchers.IO) {
        val apiKey = activeKey() ?: return@withContext emptyList()
        val cleaned = TitleCleaner.clean(query)
        runCatchingCancellable { api.search(apiKey, kind(type), cleaned.title, null) }.getOrDefault(emptyList()).take(12).map {
            TmdbCandidate(it.id, it.title, it.year, it.posterPath?.let(TmdbApi::posterUrl), it.overview)
        }
    }

    override suspend fun assign(type: ContentType, itemId: String, tmdbId: Int): String? = withContext(Dispatchers.IO) {
        val apiKey = activeKey() ?: return@withContext "Kein TMDB-API-Schlüssel hinterlegt"
        try {
            store(apiKey, type, key(type, itemId), tmdbId, manual = true)
            null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            "Zuordnung fehlgeschlagen: ${e.message}"
        }
    }

    /** "Ähnliche Titel" von TMDB, die es auch in der eigenen Playlist gibt. */
    override suspend fun similarInLibrary(type: ContentType, info: TmdbInfo, excludeId: String): List<SearchHit> = withContext(Dispatchers.IO) {
        val playlistId = settings.current().activePlaylistId ?: return@withContext emptyList()
        val hits = LinkedHashMap<String, SearchHit>()
        val hitType = if (type == ContentType.SERIES) ContentType.SERIES else ContentType.MOVIE
        for (title in info.similarTitles) {
            if (hits.size >= 10) break
            if (title.length < 3) continue
            val found = if (type == ContentType.SERIES) {
                db.seriesDao().search(playlistId, title, 1).firstOrNull()?.let { Triple(it.id, it.name, it.posterUrl) }
            } else {
                db.vodDao().search(playlistId, title, 1).firstOrNull()?.let { Triple(it.id, it.name, it.posterUrl) }
            } ?: continue
            val (id, name, poster) = found
            if (id == excludeId) continue
            // Wie in der Suche: Anbieter-Kürzel ("DE | ", "4K" …) entfernen, nur der eigentliche Titel.
            hits.getOrPut(id) { SearchHit(id, hitType, MediaTitle.parse(name).title, poster) }
        }
        hits.values.toList()
    }

    override suspend fun clearCache() = withContext(Dispatchers.IO) {
        db.tmdbDao().deleteAll()
        homeMutex.withLock { homeCache = null }
    }

    private suspend fun activeKey(): String? = settings.current().takeIf { it.tmdbActive }?.tmdbApiKey?.trim()

    private suspend fun itemInfo(type: ContentType, itemId: String): Pair<String, Int?>? = if (type == ContentType.SERIES) {
        db.seriesDao().get(itemId)?.let { it.name to it.tmdbId?.toIntOrNull() }
    } else {
        db.vodDao().get(itemId)?.let { it.name to it.tmdbId?.toIntOrNull() }
    }

    private fun findByTitle(apiKey: String, type: ContentType, rawName: String): Int? {
        val cleaned = TitleCleaner.clean(rawName)
        var results = api.search(apiKey, kind(type), cleaned.title, cleaned.year)
        if (results.isEmpty() && cleaned.year != null) results = api.search(apiKey, kind(type), cleaned.title, null)
        if (results.isEmpty()) return null
        val wanted = TitleCleaner.normalize(cleaned.title)
        val exact = results.filter { TitleCleaner.normalize(it.title) == wanted }
        return (exact.firstOrNull { it.year == cleaned.year } ?: exact.firstOrNull() ?: results.first()).id
    }

    private suspend fun store(apiKey: String, type: ContentType, cacheKey: String, tmdbId: Int, manual: Boolean) {
        val d = api.details(apiKey, kind(type), tmdbId)
        db.tmdbDao().upsert(
            TmdbCacheEntity(
                itemKey = cacheKey,
                tmdbId = tmdbId,
                mediaType = kind(type).path,
                title = d.title,
                overview = d.overview,
                rating = d.rating,
                voteCount = d.voteCount,
                genres = d.genres.joinToString(SEP),
                runtimeMin = d.runtimeMin,
                year = d.year,
                certification = d.certification,
                trailerKey = d.trailerKey,
                posterPath = d.posterPath,
                backdropPath = d.backdropPath,
                cast = d.cast.joinToString("\n") { listOf(it.name, it.character.orEmpty(), it.photoUrl.orEmpty()).joinToString("\t") },
                similar = d.similar.joinToString("\n"),
                fetchedAtMs = System.currentTimeMillis(),
                notFound = false,
                manual = manual,
            ),
        )
    }

    private fun emptyEntry(cacheKey: String, type: ContentType, now: Long) = TmdbCacheEntity(
        itemKey = cacheKey, tmdbId = null, mediaType = kind(type).path, title = null, overview = null, rating = null,
        voteCount = 0, genres = "", runtimeMin = null, year = null, certification = null, trailerKey = null,
        posterPath = null, backdropPath = null, cast = "", similar = "", fetchedAtMs = now, notFound = true, manual = false,
    )

    private fun TmdbCacheEntity.toInfo(): TmdbInfo? {
        val id = tmdbId ?: return null
        if (notFound) return null
        return TmdbInfo(
            tmdbId = id,
            title = title,
            overview = overview,
            rating = rating,
            voteCount = voteCount,
            genres = genres.split(SEP).filter { it.isNotBlank() },
            runtimeMin = runtimeMin,
            year = year,
            certification = certification,
            trailerKey = trailerKey,
            posterUrl = posterPath?.let(TmdbApi::posterUrl),
            backdropUrl = backdropPath?.let(TmdbApi::backdropUrl),
            cast = cast.lines().filter { it.isNotBlank() }.map { line ->
                val parts = line.split('\t')
                CastMember(parts[0], parts.getOrNull(1)?.takeIf { it.isNotBlank() }, parts.getOrNull(2)?.takeIf { it.isNotBlank() })
            },
            similarTitles = similar.lines().filter { it.isNotBlank() },
        )
    }

    private companion object {
        const val YEAR_DIGITS = 4

        /** Länge der Wortstücke für die Ersatzsuche (Anfang, Mitte, Ende eines markanten Worts). */
        const val FALLBACK_PIECE = 5
        const val SEP = "|"
        const val CACHE_TTL = 30L * 24 * 3_600_000
        const val NOT_FOUND_TTL = 7L * 24 * 3_600_000
        const val HOME_CACHE_TTL_MS = 60L * 60 * 1_000
        const val HOME_CANDIDATE_LIMIT = 30
        const val HOME_ROW_LIMIT = 12
    }
}
