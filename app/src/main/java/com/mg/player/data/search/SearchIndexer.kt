package com.mg.player.data.search

import android.content.SharedPreferences
import androidx.core.content.edit
import com.mg.player.core.log.AppLog
import com.mg.player.data.db.AppDatabase
import com.mg.player.data.db.dao.SearchDao
import com.mg.player.data.db.entity.SearchIndexEntity
import com.mg.player.domain.model.ContentType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.yield

/**
 * Baut den Suchindex eines Zugangs neu auf (nach jeder Aktualisierung). Pro Titel werden gefaltete
 * Suchbegriffe gespeichert (siehe [SearchText]) – die Suche fragt dann den Index statt `LIKE '%…%'`
 * über drei komplette Tabellen.
 *
 * Der Aufbau läuft in Blöcken zu je [CHUNK] Einträgen statt in einer einzigen Riesen-Transaktion:
 * Andere Schreibzugriffe (Fortschritt speichern, Favorit setzen) müssen so nie lange warten.
 *
 * Ab 3.13 wird der neue Index unter einem eigenen Schlüssel („5.1730000000000“) aufgebaut, während der alte weiter
 * benutzt wird; erst am Ende wird umgeschaltet und der alte gelöscht. Vorher war die Suche während jeder
 * Aktualisierung auf die langsame Tabellensuche angewiesen. Nur ohne jeden Index weicht sie darauf aus.
 */
class SearchIndexer(private val db: AppDatabase, private val state: SharedPreferences) {

    private val mutex = Mutex()
    private val busy = MutableStateFlow<Set<Long>>(emptySet())

    /** [force]: auch neu aufbauen, wenn der Bestand unverändert aussieht (z. B. nach „Index unvollständig“). */
    suspend fun rebuild(playlistId: Long, force: Boolean = false) = withContext(Dispatchers.IO) {
        busy.update { it + playlistId }
        try {
            mutex.withLock {
                val started = System.currentTimeMillis()
                val dao = db.searchDao()
                val fingerprint = runCatching { dao.fingerprint(playlistId) }.getOrNull()
                // Unveränderter Bestand (häufig: Aktualisierung liefert dieselbe Liste): Index behalten.
                if (!force && unchanged(playlistId, fingerprint)) {
                    AppLog.log("Suche", "Suchindex unverändert – kein Neuaufbau")
                    return@withLock
                }
                val key = "$playlistId.$started"
                var total = 0
                total += index(dao, ContentType.LIVE, key) { after -> dao.channelSource(playlistId, after, CHUNK) }
                total += index(dao, ContentType.MOVIE, key) { after -> dao.movieSource(playlistId, after, CHUNK) }
                total += index(dao, ContentType.SERIES, key) { after -> dao.seriesSource(playlistId, after, CHUNK) }
                // Umschalten, dann alle anderen Stände dieses Zugangs löschen (alter Index, Reste abgebrochener Aufbauten).
                state.edit {
                    putString(activeKeyName(playlistId), key)
                    if (fingerprint != null) putString(fingerprintName(playlistId), fingerprint) else remove(fingerprintName(playlistId))
                    // Merker älterer Index-Formate aufräumen.
                    remove("complete_$playlistId")
                    remove("ready_v${FORMAT}_$playlistId")
                }
                dao.clearExcept(playlistId.toString(), "$playlistId.%", key)
                AppLog.log("Suche", "Suchindex aufgebaut: $total Einträge (${System.currentTimeMillis() - started} ms)")
            }
        } finally {
            busy.update { it - playlistId }
        }
    }

    /**
     * Aufbau nach einer Aktualisierung im Hintergrund (Plan B4, 3.25): Die Listen sind dann schon fertig, nur die
     * Suche nutzt bis zum Umschalten noch den alten Index. Schon vor dem Start als „beschäftigt“ markiert, damit
     * eine sofortige Suche über [awaitIdle] auf den neuen wartet.
     */
    fun rebuildInBackground(scope: CoroutineScope, playlistId: Long) {
        busy.update { it + playlistId }
        // Eigener IO-Thread: Der App-Scope läuft sonst auf dem Hauptthread an.
        scope.launch(Dispatchers.IO) {
            try {
                // Zugang inzwischen gelöscht: keinen Index für ihn anlegen.
                if (db.playlistDao().get(playlistId) != null) rebuild(playlistId)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLog.error("Suche", "Suchindex konnte nicht aufgebaut werden", e)
            }
        }.invokeOnCompletion { busy.update { it - playlistId } }
    }

    /** Wartet höchstens [timeoutMs], bis kein Aufbau für diesen Zugang mehr läuft. */
    suspend fun awaitIdle(playlistId: Long, timeoutMs: Long) {
        withTimeoutOrNull(timeoutMs) { busy.first { playlistId !in it } }
    }

    private suspend fun index(dao: SearchDao, type: ContentType, playlistKey: String, source: suspend (afterRowId: Long) -> List<SearchDao.IndexSource>): Int {
        var after = Long.MIN_VALUE
        var count = 0
        while (true) {
            val rows = source(after)
            if (rows.isEmpty()) break
            dao.insert(rows.map { SearchIndexEntity(SearchText.indexTerms(it.name), it.id, type.name, playlistKey) })
            count += rows.size
            after = rows.last().rowId
            // Andere Datenbankzugriffe zwischen den Blöcken drankommen lassen.
            yield()
        }
        return count
    }

    /**
     * Einzelne Sender neu indizieren (Umbenennung bzw. neue Sender aus der Kontrolle vor Anstoß), ohne den ganzen Index
     * neu aufzubauen. Fehlt der Index oder wird er gerade aufgebaut, nichts tun – der Aufbau liest die Sender ohnehin neu.
     */
    suspend fun updateChannels(playlistId: Long, channels: List<Pair<String, String>>) = withContext(Dispatchers.IO) {
        if (channels.isEmpty() || !hasIndex(playlistId)) return@withContext
        mutex.withLock {
            val dao = db.searchDao()
            val key = activeKey(playlistId) ?: return@withLock
            channels.map { it.first }.chunked(MAX_SQL_ARGS).forEach { dao.deleteItems(key, it) }
            dao.insert(channels.map { (id, name) -> SearchIndexEntity(SearchText.indexTerms(name), id, ContentType.LIVE.name, key) })
            // Der Index weicht jetzt vom gespeicherten Fingerabdruck ab – den nächsten Aufbau nicht überspringen.
            state.edit { remove(fingerprintName(playlistId)) }
        }
    }

    suspend fun clear(playlistId: Long) = withContext(Dispatchers.IO) {
        mutex.withLock {
            db.searchDao().clearExcept(playlistId.toString(), "$playlistId.%", "")
            state.edit {
                remove(activeKeyName(playlistId))
                remove(fingerprintName(playlistId))
            }
        }
    }

    /** Läuft für diesen Zugang gerade ein Aufbau? Dann keinen zweiten anstoßen. */
    fun isBusy(playlistId: Long): Boolean = playlistId in busy.value

    /**
     * Schlüssel des vollständigen Index im aktuellen Format (null = keiner). Ein Index älterer Versionen (ohne
     * gefaltete Begriffe) zählt nicht – er wird beim nächsten Start bzw. bei der ersten Suche neu aufgebaut.
     * Ein Index aus 3.12 (Schlüssel = Zugangsnummer, Merker „ready“) gilt weiter, bis der neue fertig ist.
     */
    fun activeKey(playlistId: Long): String? = state.getString(activeKeyName(playlistId), null)
        ?: playlistId.toString().takeIf { state.getBoolean("ready_v${FORMAT}_$playlistId", false) }

    /** Vollständiger Index vorhanden? Auch während ein neuer aufgebaut wird (dann gilt noch der alte). */
    fun hasIndex(playlistId: Long): Boolean = activeKey(playlistId) != null

    /** Für die Fehlersuche: Einträge dieses Zugangs im Index. */
    suspend fun indexedCount(playlistId: Long): Int = withContext(Dispatchers.IO) {
        activeKey(playlistId)?.let { db.searchDao().indexedCount(it) } ?: 0
    }

    private fun activeKeyName(playlistId: Long) = "key_v${FORMAT}_$playlistId"

    private fun fingerprintName(playlistId: Long) = "fp_v${FORMAT}_$playlistId"

    /** Gleicher Bestand wie beim letzten Aufbau und ein vollständiger Index vorhanden? */
    private fun unchanged(playlistId: Long, fingerprint: String?): Boolean =
        fingerprint != null && hasIndex(playlistId) && fingerprint == state.getString(fingerprintName(playlistId), null)

    private companion object {
        const val CHUNK = 2_000

        // SQLite vor Android 11 erlaubt höchstens 999 Platzhalter je Abfrage.
        private const val MAX_SQL_ARGS = 900

        /** Bei jeder Änderung an [SearchText.indexTerms] erhöhen: Dann werden alle Indizes neu aufgebaut. */
        const val FORMAT = 2
    }
}
