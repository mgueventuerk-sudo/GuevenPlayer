package com.mg.player.data.repository

import android.content.Context
import androidx.core.content.edit
import com.mg.player.core.log.AppLog
import com.mg.player.data.combined.Combined
import com.mg.player.data.db.AppDatabase
import com.mg.player.data.importer.EpgImporter
import com.mg.player.data.importer.EpgSchedule
import com.mg.player.data.mapper.toDomain
import com.mg.player.data.parser.XmltvParser
import com.mg.player.data.remote.XtreamApi
import com.mg.player.domain.model.Channel
import com.mg.player.domain.model.EpgProgram
import com.mg.player.domain.model.Playlist
import com.mg.player.domain.model.PlaylistType
import com.mg.player.domain.repository.EpgRepository
import com.mg.player.domain.repository.ImportProgress
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

class EpgRepositoryImpl(
    context: Context,
    private val db: AppDatabase,
    private val importer: EpgImporter,
    private val api: XtreamApi,
    /** EPG-Zeitverschiebung je EPG-Zugang in ms. */
    private val epgShiftMs: suspend (epgPlaylistId: Long) -> Long,
) : EpgRepository {

    /** Wann zuletzt EPG geladen wurde (je Zugang) – damit ein Zugang ohne EPG nicht bei jedem Start neu lädt. */
    private val attempts = context.applicationContext.getSharedPreferences("epg_attempts", Context.MODE_PRIVATE)

    private data class CachedShortEpg(val loadedAtMs: Long, val programs: List<EpgProgram>)

    private val shortEpgCache = ConcurrentHashMap<String, CachedShortEpg>()

    /** Höchstens vier gleichzeitige get_short_epg-Anfragen – manche Panels sperren sonst den Account. */
    private val shortEpgPermits = Semaphore(MAX_PARALLEL_SHORT_EPG)

    override suspend fun refresh(playlist: Playlist, onProgress: (ImportProgress) -> Unit) = withContext(Dispatchers.IO) {
        val entity = db.playlistDao().get(playlist.id) ?: return@withContext
        shortEpgCache.clear()
        val key = playlist.id.toString()
        attempts.edit {
            putLong(key, System.currentTimeMillis())
            putInt(FORMAT_PREFIX + key, EpgSchedule.FORMAT)
        }
        if (importer.refresh(entity, onProgress)) {
            attempts.edit { putLong(SUCCESS_PREFIX + key, System.currentTimeMillis()) }
        }
    }

    /** Aus der Datenbank; fehlt dort etwas, wird bei Xtream get_short_epg abgefragt (Stufe 1). */
    override suspend fun programs(channel: Channel, fromMs: Long, toMs: Long): List<EpgProgram> = withContext(Dispatchers.IO) {
        val shift = epgShiftMs(channel.epgPlaylistId)
        val stored = db.epgDao().programs(channel.epgPlaylistId, channel.epgKey, fromMs - shift, toMs - shift).map { it.toDomain(shift) }
        if (stored.isNotEmpty()) return@withContext stored
        shortEpg(channel)
    }

    override suspend fun liveShortEpg(channelId: String): List<EpgProgram> = withContext(Dispatchers.IO) { shortEpg(channelId) }

    private suspend fun shortEpg(channel: Channel): List<EpgProgram> = shortEpg(channel.id)

    private suspend fun shortEpg(channelId: String): List<EpgProgram> {
        val cached = shortEpgCache[channelId]
        if (cached != null && System.currentTimeMillis() - cached.loadedAtMs < CACHE_TTL_MS) return cached.programs
        if (Combined.isCombinedItem(channelId)) {
            // „Alle Zugänge“: Kurz-EPG über die Quellen (zuerst die, aus der das EPG kommt).
            val sources = db.combinedDao().sources(channelId).map { it.itemId }
            for (source in sources) shortEpg(source).takeIf { it.isNotEmpty() }?.let { return it }
            return emptyList()
        }
        val row = db.channelDao().get(channelId, System.currentTimeMillis()) ?: return emptyList()
        if (row.channel.remoteId.isBlank()) return emptyList()
        val playlist = db.playlistDao().get(row.channel.playlistId) ?: return emptyList()
        if (playlist.type != PlaylistType.XTREAM.name) return emptyList()
        val programs = try {
            val shift = epgShiftMs(playlist.id)
            // Wartende Anfragen werden beim Wegscrollen abgebrochen, bevor sie überhaupt starten.
            shortEpgPermits.withPermit {
                api.openShortEpg(api.credentials(playlist), row.channel.remoteId).use { XmltvParser.parseShortEpg(it) }
            }.map { EpgProgram(it.title, it.description, it.startMs + shift, it.endMs + shift) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AppLog.debug("EPG", "get_short_epg fehlgeschlagen: ${e.javaClass.simpleName}")
            emptyList()
        }
        shortEpgCache[channelId] = CachedShortEpg(System.currentTimeMillis(), programs)
        return programs
    }

    override suspend fun hasPrograms(playlistId: Long): Boolean = withContext(Dispatchers.IO) { db.epgDao().hasPrograms(playlistId) }

    override suspend fun shouldAutoLoad(playlistId: Long): Boolean = withContext(Dispatchers.IO) {
        // „Alle Zugänge“ hat kein eigenes EPG – es kommt aus den Original-Zugängen.
        if (Combined.isCombined(playlistId)) return@withContext false
        val key = playlistId.toString()
        EpgSchedule.shouldLoad(
            hasPrograms = db.epgDao().hasPrograms(playlistId),
            lastAttemptMs = attempts.getLong(key, 0L),
            lastSuccessMs = attempts.getLong(SUCCESS_PREFIX + key, 0L),
            nowMs = System.currentTimeMillis(),
            // Vor Version 3.2 gab es keinen Eintrag: alter Import (4 Tage, ohne Sport-Untertitel).
            attemptedFormat = attempts.getInt(FORMAT_PREFIX + key, 1),
        )
    }

    override suspend fun clear() = withContext(Dispatchers.IO) {
        shortEpgCache.clear()
        db.epgDao().deleteAll()
        db.epgDao().deleteAllActive()
    }

    private companion object {
        const val CACHE_TTL_MS = 10 * 60_000L
        const val MAX_PARALLEL_SHORT_EPG = 4
        const val SUCCESS_PREFIX = "ok_"
        const val FORMAT_PREFIX = "fmt_"
    }
}
