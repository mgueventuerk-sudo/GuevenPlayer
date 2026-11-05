package com.mg.player.data.repository

import androidx.paging.PagingData
import androidx.paging.map
import com.mg.player.core.log.AppLog
import com.mg.player.data.combined.Combined
import com.mg.player.data.combined.CombinedVod
import com.mg.player.data.db.AppDatabase
import com.mg.player.data.db.entity.EpisodeEntity
import com.mg.player.data.mapper.toDomain
import com.mg.player.data.parser.XtreamDetailParser
import com.mg.player.data.remote.XtreamApi
import com.mg.player.domain.model.Category
import com.mg.player.domain.model.ContentType
import com.mg.player.domain.model.Episode
import com.mg.player.domain.model.PlaylistType
import com.mg.player.domain.model.Series
import com.mg.player.domain.repository.SeriesRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

class SeriesRepositoryImpl(
    private val db: AppDatabase,
    private val api: XtreamApi,
    /** true, solange eine Aktualisierung läuft – Import-Blöcke werden dann gesammelt übernommen. */
    private val syncRunning: StateFlow<Boolean>,
    /** Rang eines Zugangs in „Alle Zugänge“ (vordere Zugänge liefern Titel und Bilder der Folgen). */
    private val accountRank: (Long) -> Int = { 0 },
    /** Serien dieses Zugangs alphabetisch (Einstellung „Reihenfolge in Gruppen“). */
    private val alphabetical: suspend (Long) -> Boolean = { false },
) : SeriesRepository {

    override fun categories(playlistId: Long): Flow<List<Category>> = db.categoryDao().observeVisible(playlistId, ContentType.SERIES.name).map { list ->
        listOf(
            Category(Category.ALL, "Alle Serien", ContentType.SERIES),
            Category(Category.CONTINUE, "Weiterschauen", ContentType.SERIES),
            Category(Category.FAVORITES, "Favoriten", ContentType.SERIES),
        ) + list.map { it.toDomain() }
    }

    override fun series(playlistId: Long, categoryId: String): Flow<PagingData<Series>> {
        val dao = db.seriesDao()
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

    override suspend fun seriesById(id: String): Series? = withContext(Dispatchers.IO) { db.seriesDao().get(id)?.toDomain() }

    /** Xtream: frisch über get_series_info laden und cachen. M3U: Episoden liegen bereits in Room. */
    override suspend fun loadEpisodes(seriesId: String): List<Episode> = withContext(Dispatchers.IO) {
        if (Combined.isCombinedItem(seriesId)) {
            // „Alle Zugänge“: Folgen aller Quellen laden und je Staffel + Folge zusammenführen (ergänzt fehlende Staffeln).
            CombinedVod.buildEpisodes(db, seriesId, accountRank) { source ->
                loadEpisodes(source)
                db.seriesDao().episodes(source)
            }
            return@withContext db.seriesDao().episodes(seriesId).map { it.toDomain() }
        }
        val dao = db.seriesDao()
        val series = dao.get(seriesId) ?: return@withContext emptyList()
        val playlist = db.playlistDao().get(series.playlistId)
        if (series.remoteId.isNotBlank() && playlist != null && playlist.type == PlaylistType.XTREAM.name) {
            try {
                val creds = api.credentials(playlist)
                val info = api.openSeriesInfo(creds, series.remoteId).use { XtreamDetailParser.parseSeriesInfo(it) }
                if (info.episodes.isNotEmpty()) {
                    val entities = info.episodes.map { ep ->
                        EpisodeEntity(
                            id = "${series.playlistId}:E:${ep.id}",
                            seriesId = seriesId,
                            playlistId = series.playlistId,
                            season = ep.season,
                            episode = ep.episode,
                            title = ep.title,
                            streamUrl = api.episodePlaceholder(series.playlistId, ep.id, ep.extension),
                            imageUrl = ep.imageUrl,
                            plot = ep.plot,
                            durationSec = ep.durationSec,
                        )
                    }
                    dao.replaceEpisodes(seriesId, entities)
                }
                dao.updateInfo(seriesId, info.plot, info.backdropUrl, info.tmdbId)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLog.debug("Serien", "get_series_info fehlgeschlagen (${e.javaClass.simpleName})")
            }
        }
        dao.episodes(seriesId).map { it.toDomain() }
    }

    override fun observeEpisodes(seriesId: String): Flow<List<Episode>> = db.seriesDao().observeEpisodes(seriesId).map { list -> list.map { it.toDomain() } }

    override suspend fun episode(id: String): Episode? = withContext(Dispatchers.IO) { db.seriesDao().episode(id)?.toDomain() }

    override suspend fun episodes(seriesId: String): List<Episode> = withContext(Dispatchers.IO) {
        db.seriesDao().episodes(seriesId).map { it.toDomain() }
    }

    private companion object {
        val TABLES = arrayOf("series", "tmdb_cache", "progress", "favorites", "category_prefs")
        val IMPORT_TABLES = setOf("series")
    }
}
