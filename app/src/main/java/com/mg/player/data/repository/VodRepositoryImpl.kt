package com.mg.player.data.repository

import androidx.paging.PagingData
import androidx.paging.map
import com.mg.player.core.log.AppLog
import com.mg.player.data.combined.Combined
import com.mg.player.data.db.AppDatabase
import com.mg.player.data.mapper.toDomain
import com.mg.player.data.parser.XtreamDetailParser
import com.mg.player.data.remote.XtreamApi
import com.mg.player.domain.model.Category
import com.mg.player.domain.model.ContentType
import com.mg.player.domain.model.Movie
import com.mg.player.domain.model.PlaylistType
import com.mg.player.domain.repository.VodRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

class VodRepositoryImpl(
    private val db: AppDatabase,
    private val api: XtreamApi,
    /** true, solange eine Aktualisierung läuft – Import-Blöcke werden dann gesammelt übernommen. */
    private val syncRunning: StateFlow<Boolean>,
    /** Filme dieses Zugangs alphabetisch (Einstellung „Reihenfolge in Gruppen“). */
    private val alphabetical: suspend (Long) -> Boolean = { false },
) : VodRepository {

    override fun categories(playlistId: Long): Flow<List<Category>> = db.categoryDao().observeVisible(playlistId, ContentType.MOVIE.name).map { list ->
        listOf(
            Category(Category.ALL, "Alle Filme", ContentType.MOVIE),
            Category(Category.CONTINUE, "Weiterschauen", ContentType.MOVIE),
            Category(Category.FAVORITES, "Favoriten", ContentType.MOVIE),
        ) + list.map { it.toDomain() }
    }

    override fun movies(playlistId: Long, categoryId: String): Flow<PagingData<Movie>> {
        val dao = db.vodDao()
        return GatedPagingSource.pagingFlow(db, TABLES, IMPORT_TABLES, syncRunning) { limit, offset ->
            val alpha = alphabetical(playlistId)
            when (categoryId) {
                Category.ALL -> if (alpha) dao.pageAllAlpha(playlistId, limit, offset) else dao.pageAll(playlistId, limit, offset)
                Category.FAVORITES -> dao.pageFavorites(playlistId, limit, offset)
                Category.CONTINUE -> dao.pageContinue(playlistId, limit, offset)
                else -> if (alpha) dao.pageByCategoryAlpha(playlistId, categoryId, limit, offset) else dao.pageByCategory(playlistId, categoryId, limit, offset)
            }
        }.map { paging -> paging.map { it.toDomain() } }
    }

    override suspend fun movie(id: String): Movie? = withContext(Dispatchers.IO) { db.vodDao().get(id)?.toDomain() }

    override suspend fun loadDetails(id: String): Movie? = withContext(Dispatchers.IO) {
        if (Combined.isCombinedItem(id)) return@withContext combinedDetails(id)
        val entity = db.vodDao().get(id) ?: return@withContext null
        if (entity.detailsLoaded || entity.remoteId.isBlank()) return@withContext entity.toDomain()
        val playlist = db.playlistDao().get(entity.playlistId)
        if (playlist == null || playlist.type != PlaylistType.XTREAM.name) return@withContext entity.toDomain()
        try {
            val info = api.openVodInfo(api.credentials(playlist), entity.remoteId).use { XtreamDetailParser.parseVodInfo(it) }
            if (info != null) {
                db.vodDao().updateDetails(
                    id = id,
                    plot = info.plot,
                    backdropUrl = info.backdropUrl,
                    durationSec = info.durationSec,
                    year = info.year,
                    rating = info.rating?.takeIf { it > 0f },
                    posterUrl = info.posterUrl,
                    tmdbId = info.tmdbId,
                )
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AppLog.debug("Filme", "get_vod_info fehlgeschlagen (${e.javaClass.simpleName})")
        }
        db.vodDao().get(id)?.toDomain()
    }

    /** „Alle Zugänge“: Details der ersten Quelle mit Beschreibung übernehmen (geladen wird beim Original). */
    private suspend fun combinedDetails(id: String): Movie? {
        val combined = db.vodDao().get(id) ?: return null
        if (combined.detailsLoaded) return combined.toDomain()
        val details = db.combinedDao().sources(id).firstNotNullOfOrNull { source ->
            loadDetails(source.itemId)
            db.vodDao().get(source.itemId)?.takeIf { !it.plot.isNullOrBlank() || it.durationSec != null }
        }
        // Vorhandene Werte der kombinierten Zeile bleiben (COALESCE in der Abfrage).
        if (details != null) {
            with(details) { db.vodDao().updateDetails(id, plot, backdropUrl, durationSec, year, rating, posterUrl, tmdbId) }
        }
        return db.vodDao().get(id)?.toDomain()
    }

    private companion object {
        val TABLES = arrayOf("movies", "tmdb_cache", "progress", "favorites", "category_prefs")
        val IMPORT_TABLES = setOf("movies")
    }
}
