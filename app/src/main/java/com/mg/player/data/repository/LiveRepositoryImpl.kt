package com.mg.player.data.repository

import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.map
import com.mg.player.data.combined.Combined
import com.mg.player.data.db.AppDatabase
import com.mg.player.data.db.entity.ChannelWithNow
import com.mg.player.data.mapper.toDomain
import com.mg.player.domain.model.Category
import com.mg.player.domain.model.Channel
import com.mg.player.domain.model.ContentType
import com.mg.player.domain.repository.LiveRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

class LiveRepositoryImpl(
    private val db: AppDatabase,
    /** EPG-Zeitverschiebung je EPG-Zugang in ms. */
    private val epgShiftMs: suspend (epgPlaylistId: Long) -> Long,
    /** true, solange Inhalte oder EPG aktualisiert werden. */
    private val syncRunning: StateFlow<Boolean>,
    private val groupIndex: ChannelGroupIndex,
    private val habits: ChannelHabits? = null,
) : LiveRepository {

    private fun isSyncing(): Boolean = syncRunning.value

    override fun categories(playlistId: Long): Flow<List<Category>> = db.categoryDao().observeVisible(playlistId, ContentType.LIVE.name).map { list ->
        listOf(
            Category(Category.ALL, "Alle Sender", ContentType.LIVE),
            Category(Category.FAVORITES, "Favoriten", ContentType.LIVE),
            Category(Category.RECENT, "Zuletzt gesehen", ContentType.LIVE),
            Category(Category.FREQUENT, "Oft gesehen", ContentType.LIVE),
        ) + list.map { it.toDomain() }
    }

    /**
     * Sender inkl. laufender Sendung. Alle zwei Minuten wird die Quelle invalidiert:
     * Paging lädt dann um die aktuelle Scroll-Position herum neu – die Liste springt nicht nach oben.
     *
     * Während einer Aktualisierung (Import in 1.000er-Blöcken, EPG-Austausch) wird nicht bei jedem Block
     * neu geladen, sondern einmal danach – sonst lädt und flackert die Liste dutzendfach.
     */
    override fun channels(playlistId: Long, categoryId: String, startAt: Int): Flow<PagingData<Channel>> = flow {
        // Erster Start nach dem Update oder abgebrochener Import: Gruppierung jetzt erstellen.
        groupIndex.ensure(playlistId)
        val latest = AtomicReference<ChannelPagingSource?>(null)
        // Der Room-Beobachter meldet sich auf einem Hintergrund-Thread.
        val pending = AtomicBoolean(false)
        val onChanged: (ChannelPagingSource, Set<String>) -> Unit = { source, tables ->
            // Favorit gesetzt oder Gruppe ausgeblendet: sofort zeigen. Nur Import-Blöcke werden gesammelt.
            val fromImport = tables.any { it in ChannelPagingSource.IMPORT_TABLES }
            if (fromImport && isSyncing()) pending.set(true) else source.invalidate()
        }
        val pager = Pager(PagingConfig(pageSize = 60, prefetchDistance = 30, enablePlaceholders = false), initialKey = startAt.takeIf { it > 0 }) {
            val dao = db.channelDao()
            ChannelPagingSource(db, playlistId, System.currentTimeMillis(), epgShiftMs, onChanged) { limit, offset ->
                when (categoryId) {
                    Category.ALL -> dao.pageAll(playlistId, limit, offset)
                    Category.FAVORITES -> dao.pageFavorites(playlistId, limit, offset)
                    Category.RECENT -> dao.pageRecent(playlistId, limit, offset)
                    Category.FREQUENT -> frequent(playlistId).drop(offset).take(limit)
                    else -> dao.pageByCategory(playlistId, categoryId, limit, offset)
                }
            }.also { latest.set(it) }
        }
        coroutineScope {
            launch {
                while (true) {
                    delay(REFRESH_INTERVAL_MS)
                    if (!isSyncing()) latest.get()?.invalidate()
                }
            }
            launch {
                syncRunning.collect { running ->
                    if (!running && pending.getAndSet(false)) {
                        latest.get()?.invalidate()
                    }
                }
            }
            try {
                emitAll(pager.flow.map { paging -> paging.map { it.value.toDomain(0L).copy(listPosition = it.index) } })
            } finally {
                // Liste verlassen (z. B. andere Kategorie): letzte Quelle invalidieren, damit sie ihren
                // Datenbank-Beobachter sofort abmeldet und nicht bis zum nächsten Schreibzugriff hängen bleibt.
                latest.get()?.invalidate()
            }
        }
    }

    override suspend fun variants(channel: Channel): List<Channel> = withContext(Dispatchers.IO) {
        if (Combined.isCombinedItem(channel.id)) return@withContext combinedSources(channel)
        if (channel.groupKey.isBlank()) return@withContext listOf(channel)
        val all = db.channelDao().variants(channel.playlistId, channel.groupKey).map { entity ->
            // EPG-Infos der Hauptvariante übernehmen – es ist ja derselbe Sender.
            entity.toDomain().copy(nowTitle = channel.nowTitle, nowStartMs = channel.nowStartMs, nowEndMs = channel.nowEndMs)
        }
        all.ifEmpty { listOf(channel) }
    }

    /** „Alle Zugänge“: alle Originale aus allen Zugängen; nie aus inzwischen gesperrten oder ausgeblendeten Gruppen. */
    private suspend fun combinedSources(channel: Channel): List<Channel> {
        val ids = db.combinedDao().sources(channel.id).map { it.itemId }
        val restricted = db.categoryPrefDao().all().filter { it.hidden || it.locked }.map { it.categoryId }.toHashSet()
        val entities = ids.chunked(MAX_SQL_ARGS).flatMap { db.channelDao().entities(it) }.associateBy { it.id }
        return ids.mapNotNull { entities[it] }.filter { it.categoryId !in restricted }.map { entity ->
            entity.toDomain().copy(nowTitle = channel.nowTitle, nowStartMs = channel.nowStartMs, nowEndMs = channel.nowEndMs)
        }
    }

    override suspend fun channel(id: String): Channel? = withContext(Dispatchers.IO) {
        val entity = db.channelDao().entities(listOf(id)).firstOrNull() ?: return@withContext null
        val shift = epgShiftMs(entity.epgPlaylistId.takeIf { it != 0L } ?: entity.playlistId)
        db.channelDao().get(id, System.currentTimeMillis() - shift)?.toDomain(shift)
    }

    override suspend fun channelIds(playlistId: Long, categoryId: String): List<String> = withContext(Dispatchers.IO) {
        groupIndex.ensure(playlistId)
        val dao = db.channelDao()
        when (categoryId) {
            Category.ALL -> dao.idsAll(playlistId)
            Category.FAVORITES -> dao.idsFavorites(playlistId)
            Category.RECENT -> dao.idsRecent(playlistId)
            Category.FREQUENT -> frequent(playlistId).map { it.channel.id }
            else -> dao.idsByCategory(playlistId, categoryId)
        }
    }

    /** „Oft gesehen“: Reihenfolge aus den Sehgewohnheiten, nur Sender dieses Zugangs und keine gesperrten. */
    private suspend fun frequent(playlistId: Long): List<ChannelWithNow> {
        val ids = habits?.top().orEmpty()
        if (ids.isEmpty()) return emptyList()
        val order = ids.withIndex().associate { it.value to it.index }
        // Je Sender einmal (häufigste Quelle zuerst) – nicht jede Qualität einzeln.
        return db.channelDao().byIds(playlistId, ids).sortedBy { order[it.channel.id] ?: Int.MAX_VALUE }
            .distinctBy { it.channel.groupKey.ifEmpty { it.channel.id } }
    }

    private companion object {
        const val REFRESH_INTERVAL_MS = 120_000L
        const val MAX_SQL_ARGS = 900
    }
}
