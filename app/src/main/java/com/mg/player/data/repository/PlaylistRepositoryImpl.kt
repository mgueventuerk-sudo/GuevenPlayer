package com.mg.player.data.repository

import android.content.Context
import android.content.SharedPreferences
import android.os.SystemClock
import androidx.core.content.edit
import com.mg.player.core.log.AppLog
import com.mg.player.core.security.SecretCipher
import com.mg.player.core.util.UrlUtils
import com.mg.player.data.combined.Combined
import com.mg.player.data.db.AppDatabase
import com.mg.player.data.db.entity.PlaylistEntity
import com.mg.player.data.importer.ImportClock
import com.mg.player.data.importer.ImportGuard
import com.mg.player.data.importer.M3uImporter
import com.mg.player.data.importer.XtreamImporter
import com.mg.player.data.mapper.toDomain
import com.mg.player.data.search.SearchIndexer
import com.mg.player.domain.model.AutoRefreshSchedule
import com.mg.player.domain.model.ContentType
import com.mg.player.domain.model.EPG_SHIFT_MAX_HOURS
import com.mg.player.domain.model.Playlist
import com.mg.player.domain.model.PlaylistType
import com.mg.player.domain.repository.ImportProgress
import com.mg.player.domain.repository.PlaylistEdit
import com.mg.player.domain.repository.PlaylistEditResult
import com.mg.player.domain.repository.PlaylistInput
import com.mg.player.domain.repository.PlaylistRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.io.IOException

class PlaylistRepositoryImpl(
    context: Context,
    private val db: AppDatabase,
    private val xtreamImporter: XtreamImporter,
    private val m3uImporter: M3uImporter,
    private val searchIndex: SearchIndexer,
    private val groupIndex: ChannelGroupIndex,
    private val background: CoroutineScope,
) : PlaylistRepository {

    private val guard = ImportGuard(PrefsSuspicionStore(context.getSharedPreferences("import_guard", Context.MODE_PRIVATE)))

    override fun observePlaylists(): Flow<List<Playlist>> = db.playlistDao().observeAll().map { list -> list.map { it.toDomain() } }

    override suspend fun getPlaylist(id: Long): Playlist? = withContext(Dispatchers.IO) {
        if (!Combined.isCombined(id)) return@withContext db.playlistDao().get(id)?.toDomain()
        // „Alle Zugänge“ gibt es ab zwei Zugängen (ohne eigene Zeile in `playlists`).
        val all = db.playlistDao().all()
        if (all.size < 2) null else Combined.playlist(all.maxOf { it.lastUpdatedMs })
    }

    override suspend fun addPlaylist(input: PlaylistInput): Long = withContext(Dispatchers.IO) {
        val entity = when (input.type) {
            PlaylistType.XTREAM -> PlaylistEntity(
                name = input.name.ifBlank { "Xtream" },
                type = PlaylistType.XTREAM.name,
                serverUrl = UrlUtils.normalizeBaseUrl(input.serverUrl),
                username = input.username.trim(),
                password = SecretCipher.encrypt(input.password.trim()),
                m3uUrl = "",
                customEpgUrl = input.epgUrl.trim(),
                headerEpgUrl = "",
                lastUpdatedMs = 0,
                allowInsecureHttp = input.allowInsecureHttp,
            )

            PlaylistType.M3U -> PlaylistEntity(
                name = input.name.ifBlank { "M3U-Playlist" },
                type = PlaylistType.M3U.name,
                serverUrl = "",
                username = "",
                password = "",
                m3uUrl = SecretCipher.encrypt(input.m3uUrl.trim()),
                customEpgUrl = input.epgUrl.trim(),
                headerEpgUrl = "",
                lastUpdatedMs = 0,
                allowInsecureHttp = input.allowInsecureHttp,
            )
        }
        db.playlistDao().insert(entity)
    }

    override suspend fun deletePlaylist(id: Long) = withContext(Dispatchers.IO) {
        clearContent(id)
        db.playlistDao().delete(id)
    }

    override suspend fun setAllowInsecureHttp(id: Long, allowed: Boolean) = withContext(Dispatchers.IO) {
        db.playlistDao().setAllowInsecureHttp(id, allowed)
    }

    override suspend fun updatePlaylist(id: Long, edit: PlaylistEdit): PlaylistEditResult = withContext(Dispatchers.IO) {
        val old = db.playlistDao().get(id) ?: throw IOException("Zugang nicht gefunden")
        val xtream = old.type == PlaylistType.XTREAM.name
        val server = if (xtream) UrlUtils.normalizeBaseUrl(edit.serverUrl) else old.serverUrl
        val username = if (xtream) edit.username.trim() else old.username
        // Leer = unverändert. Nur bei echter Änderung neu verschlüsseln (alte Werte werden nie im Klartext gehalten).
        val newPassword = edit.password.trim().takeIf { xtream && it.isNotEmpty() && it != SecretCipher.decrypt(old.password) }
        val newM3u = edit.m3uUrl.trim().takeIf { !xtream && it.isNotEmpty() && it != SecretCipher.decrypt(old.m3uUrl) }
        val credentialsChanged = server != old.serverUrl || username != old.username || newPassword != null || newM3u != null
        val epg = edit.epgUrl.trim()
        db.playlistDao().update(
            old.copy(
                name = edit.name.trim().ifBlank { old.name },
                serverUrl = server,
                username = username,
                password = newPassword?.let(SecretCipher::encrypt) ?: old.password,
                m3uUrl = newM3u?.let(SecretCipher::encrypt) ?: old.m3uUrl,
                customEpgUrl = epg,
                allowInsecureHttp = edit.allowInsecureHttp,
                autoRefreshDays = edit.autoRefreshDays.coerceIn(0, AutoRefreshSchedule.MAX_DAYS),
            ),
        )
        PlaylistEditResult(credentialsChanged, epg != old.customEpgUrl)
    }

    override suspend fun setAutoRefreshDays(id: Long, days: Int) = withContext(Dispatchers.IO) {
        db.playlistDao().setAutoRefreshDays(id, days.coerceIn(0, AutoRefreshSchedule.MAX_DAYS))
    }

    override suspend fun setEpgShiftHours(id: Long, hours: Int) = withContext(Dispatchers.IO) {
        db.playlistDao().setEpgShiftHours(id, hours.coerceIn(-EPG_SHIFT_MAX_HOURS, EPG_SHIFT_MAX_HOURS))
    }

    /**
     * Neue Einträge bekommen eine neue Generation; veraltete werden erst nach erfolgreichem
     * Import gelöscht. So bleiben die Listen während der Aktualisierung vollständig sichtbar.
     *
     * Vor dem Aufräumen prüft der [ImportGuard] je Bereich, ob das Ergebnis plausibel ist. Eine leere
     * Antwort oder ein Einbruch auf unter 20 % löscht nichts – die bisherigen Einträge bleiben stehen.
     */
    override suspend fun refreshContent(playlistId: Long, onProgress: (ImportProgress) -> Unit) = withContext(Dispatchers.IO) {
        val playlist = db.playlistDao().get(playlistId) ?: throw IOException("Playlist nicht gefunden")
        val before = Counts(db.channelDao().count(playlistId), db.vodDao().count(playlistId), db.seriesDao().count(playlistId))
        val generation = System.currentTimeMillis()
        val started = SystemClock.elapsedRealtime()
        ImportClock.reset()
        when (playlist.type) {
            PlaylistType.XTREAM.name -> xtreamImporter.import(playlist, generation, onProgress)
            else -> m3uImporter.import(playlist, generation, onProgress)
        }
        val loaded = SystemClock.elapsedRealtime()
        val fresh = Counts(
            db.channelDao().countGeneration(playlistId, generation),
            db.vodDao().countGeneration(playlistId, generation),
            db.seriesDao().countGeneration(playlistId, generation),
        )
        if (fresh.total == 0) {
            throw IOException(
                if (before.total > 0) {
                    "Der Anbieter hat keine Inhalte geliefert (leere Antwort oder Fehlerseite). Deine bisherigen Listen bleiben erhalten."
                } else {
                    "Die Playlist ist leer oder keine gültige M3U/Xtream-Antwort."
                },
            )
        }
        val kept = ArrayList<String>()
        for (type in ContentType.entries) {
            val verdict = guard.judge(playlistId, type, before[type], fresh[type])
            if (verdict == ImportGuard.Verdict.ACCEPT) {
                deleteStale(playlistId, type, generation)
            } else {
                kept += "${ImportGuard.label(type)}: ${fresh[type]} statt ${before[type]}"
            }
        }
        val cleaned = SystemClock.elapsedRealtime()
        groupIndex.rebuild(playlistId, unifyFirst = true)
        val grouped = SystemClock.elapsedRealtime()
        // Plan B4 (3.25): Der Suchindex brauchte im Labor 20 von 120 s. Er wird jetzt im Hintergrund aufgebaut – die
        // Aktualisierung ist ohne ihn fertig, die Suche nutzt bis dahin den alten Index (Dauer: „Suchindex aufgebaut“).
        searchIndex.rebuildInBackground(background, playlistId)
        // Plan B4: Phasen fürs Protokoll – wo bleibt die Zeit auf dem Gerät (Laden/Aufbereiten, Schreiben, Nacharbeit)?
        AppLog.log(
            "Messung",
            "Import-Phasen: laden ${loaded - started} ms (davon schreiben ${ImportClock.writeMs.get()} ms), " +
                "aufräumen ${cleaned - loaded} ms, Gruppen ${grouped - cleaned} ms, Suchindex im Hintergrund",
        )
        db.playlistDao().updateLastUpdated(playlistId, System.currentTimeMillis())
        if (kept.isNotEmpty()) {
            val warning = "Ungewöhnlich wenige Einträge (${kept.joinToString(", ")}) – bisherige Liste bleibt erhalten"
            onProgress(ImportProgress(warning, warning = warning))
        }
    }

    private suspend fun deleteStale(playlistId: Long, type: ContentType, generation: Long) {
        db.categoryDao().deleteStaleOfType(playlistId, type.name, generation)
        when (type) {
            ContentType.LIVE -> db.channelDao().deleteStale(playlistId, generation)

            ContentType.MOVIE -> db.vodDao().deleteStale(playlistId, generation)

            ContentType.SERIES -> {
                db.seriesDao().deleteStale(playlistId, generation)
                db.seriesDao().deleteStaleEpisodes(playlistId, generation)
            }
        }
    }

    private class Counts(val live: Int, val movies: Int, val series: Int) {
        val total: Int get() = live + movies + series

        operator fun get(type: ContentType): Int = when (type) {
            ContentType.LIVE -> live
            ContentType.MOVIE -> movies
            ContentType.SERIES -> series
        }
    }

    private suspend fun clearContent(playlistId: Long) {
        db.categoryDao().deleteForPlaylist(playlistId)
        db.channelDao().deleteForPlaylist(playlistId)
        db.vodDao().deleteForPlaylist(playlistId)
        db.seriesDao().deleteForPlaylist(playlistId)
        db.seriesDao().deleteEpisodesForPlaylist(playlistId)
        db.categoryPrefDao().deleteForPlaylist(playlistId)
        db.epgDao().deleteForPlaylist(playlistId)
        db.epgDao().deleteActive(playlistId)
        groupIndex.clear(playlistId)
        searchIndex.clear(playlistId)
    }
}

/** Verdachtsfälle des [ImportGuard] dauerhaft speichern (überlebt App-Neustarts). */
private class PrefsSuspicionStore(private val prefs: SharedPreferences) : ImportGuard.SuspicionStore {
    override fun get(key: String): Int? = if (prefs.contains(key)) prefs.getInt(key, 0) else null
    override fun set(key: String, value: Int) = prefs.edit { putInt(key, value) }
    override fun remove(key: String) = prefs.edit { remove(key) }
}
