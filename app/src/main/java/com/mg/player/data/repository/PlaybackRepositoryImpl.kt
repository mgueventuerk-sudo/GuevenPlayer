package com.mg.player.data.repository

import com.mg.player.core.log.AppLog
import com.mg.player.core.util.runCatchingCancellable
import com.mg.player.data.db.AppDatabase
import com.mg.player.data.db.entity.FavoriteEntity
import com.mg.player.data.db.entity.IntroProfileEntity
import com.mg.player.data.db.entity.ProgressEntity
import com.mg.player.data.mapper.toDomain
import com.mg.player.domain.model.ContentType
import com.mg.player.domain.model.IntroProfile
import com.mg.player.domain.model.PlaybackProgress
import com.mg.player.domain.repository.PlaybackRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Fortschritt, Verlauf und Favoriten. Filme, Serien und Folgen, die es in mehreren Zugängen gibt, teilen sich
 * Position, „gesehen“ und Favorit: Gespeichert wird auch beim Gegenstück ([CounterpartFinder]) – im Hintergrund,
 * damit das Speichern im Player nie auf eine Suche oder einen Netzabruf wartet.
 */
class PlaybackRepositoryImpl(
    private val db: AppDatabase,
    private val counterparts: CounterpartFinder? = null,
    private val scope: CoroutineScope? = null,
    /** „Ohne Verlauf“: weder Fortschritt noch „Zuletzt gesehen“ speichern. */
    private val noHistory: suspend () -> Boolean = { false },
) : PlaybackRepository {

    override suspend fun progress(key: String): PlaybackProgress? = withContext(Dispatchers.IO) {
        db.progressDao().get(key)?.toDomain()
    }

    override fun observeProgress(key: String): Flow<PlaybackProgress?> = db.progressDao().observe(key).map { it?.toDomain() }

    /** Map: Episoden-ID -> Fortschritt. */
    override fun observeSeriesProgress(seriesId: String): Flow<Map<String, PlaybackProgress>> = db.progressDao().observeSeries(seriesId).map { list ->
        list.associate { it.key.removePrefix("episode:") to it.toDomain() }
    }

    override suspend fun lastWatchedInSeries(seriesId: String): PlaybackProgress? = withContext(Dispatchers.IO) {
        db.progressDao().lastInSeries(seriesId)?.toDomain()
    }

    override suspend fun saveProgress(key: String, positionMs: Long, durationMs: Long, seriesId: String?) = withContext(Dispatchers.IO) {
        if (noHistory()) return@withContext
        // Ab 96 % gilt ein Titel als gesehen.
        val watched = durationMs > 0 && positionMs >= durationMs * 0.96
        val entity = ProgressEntity(key, positionMs, durationMs, watched, System.currentTimeMillis(), seriesId)
        db.progressDao().upsert(entity)
        shareProgress(entity)
    }

    override suspend fun markWatched(key: String, durationMs: Long, seriesId: String?) = withContext(Dispatchers.IO) {
        if (noHistory()) return@withContext
        val entity = ProgressEntity(key, durationMs, durationMs, true, System.currentTimeMillis(), seriesId)
        db.progressDao().upsert(entity)
        shareProgress(entity)
    }

    /** Fortschritt auch beim selben Film/derselben Folge in den anderen Zugängen speichern. */
    private fun shareProgress(entity: ProgressEntity) {
        val finder = counterparts ?: return
        val prefix = when {
            entity.key.startsWith(MOVIE) -> MOVIE
            entity.key.startsWith(EPISODE) -> EPISODE
            else -> return
        }
        scope?.launch(Dispatchers.IO) {
            runCatchingCancellable {
                finder.forItem(entity.key.removePrefix(prefix)).forEach { other ->
                    val seriesId = if (prefix == EPISODE) db.seriesDao().episode(other.itemId)?.seriesId else null
                    db.progressDao().upsert(entity.copy(key = prefix + other.itemId, seriesId = seriesId))
                }
            }.onFailure { AppLog.log("Fortschritt", "Gegenstück nicht aktualisiert: ${it.javaClass.simpleName}") }
        }
    }

    override suspend fun markChannelWatched(channelId: String) = withContext(Dispatchers.IO) {
        if (noHistory()) return@withContext
        db.progressDao().upsert(
            ProgressEntity(PlaybackProgress.channelKey(channelId), 0, 0, false, System.currentTimeMillis(), null),
        )
    }

    override suspend fun introProfile(seriesId: String): IntroProfile? = withContext(Dispatchers.IO) {
        db.introProfileDao().get(seriesId)?.toDomain()
    }

    override suspend fun saveIntroProfile(profile: IntroProfile) = withContext(Dispatchers.IO) {
        db.introProfileDao().upsert(IntroProfileEntity(profile.seriesId, profile.introStartMs, profile.introEndMs))
    }

    override fun isFavorite(type: ContentType, itemId: String): Flow<Boolean> = db.favoriteDao().observeExists(type.name, itemId)

    override suspend fun toggleFavorite(type: ContentType, itemId: String) = withContext(Dispatchers.IO) {
        val dao = db.favoriteDao()
        val nowFavorite = !dao.exists(type.name, itemId)
        setFavorite(type, itemId, nowFavorite)
        // Derselbe Inhalt in den anderen Zugängen bekommt denselben Stand.
        val finder = counterparts ?: return@withContext
        scope?.launch(Dispatchers.IO) {
            runCatchingCancellable { finder.forItem(itemId).forEach { setFavorite(type, it.itemId, nowFavorite) } }
        }
        Unit
    }

    private suspend fun setFavorite(type: ContentType, itemId: String, favorite: Boolean) {
        val dao = db.favoriteDao()
        if (!favorite) {
            dao.delete(type.name, itemId)
        } else if (!dao.exists(type.name, itemId)) {
            dao.insert(FavoriteEntity("${type.name}:$itemId", type.name, itemId, System.currentTimeMillis()))
        }
    }

    override suspend fun clearHistory() = withContext(Dispatchers.IO) { db.progressDao().deleteAll() }

    private companion object {
        const val MOVIE = "movie:"
        const val EPISODE = "episode:"
    }
}
