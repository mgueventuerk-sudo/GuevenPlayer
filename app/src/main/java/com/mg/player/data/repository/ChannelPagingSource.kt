package com.mg.player.data.repository

import androidx.paging.PagingSource
import androidx.paging.PagingState
import androidx.room.InvalidationTracker
import com.mg.player.data.db.AppDatabase
import com.mg.player.data.db.entity.ChannelWithNow
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Seitenweises Laden der Senderliste (LIMIT/OFFSET) mit gebündelter "läuft gerade"-Abfrage:
 * eine einzige EPG-Abfrage pro Seite statt drei Unterabfragen pro Sender über die ganze Tabelle.
 * Ohne Zählabfrage (keine Platzhalter) – die kostete bei Room zusätzlich einen kompletten Durchlauf.
 *
 * Ändern sich die beobachteten Tabellen, meldet die Quelle das über [onTablesChanged]; der Aufrufer
 * entscheidet, ob sofort neu geladen wird oder erst nach einer laufenden Aktualisierung.
 */
class ChannelPagingSource(
    private val db: AppDatabase,
    private val playlistId: Long,
    private val nowMs: Long,
    /** EPG-Zeitverschiebung je EPG-Zugang in ms (die Zeiten in der Datenbank sind die des Anbieters). */
    private val shiftFor: suspend (epgPlaylistId: Long) -> Long,
    private val onTablesChanged: (source: ChannelPagingSource, tables: Set<String>) -> Unit,
    private val query: suspend (limit: Int, offset: Int) -> List<ChannelWithNow>,
) : PagingSource<Int, IndexedValue<ChannelWithNow>>() {

    private val registered = AtomicBoolean(false)
    private val observer = object : InvalidationTracker.Observer(arrayOf(*TABLES)) {
        override fun onInvalidated(tables: Set<String>) = onTablesChanged(this@ChannelPagingSource, tables)
    }

    init {
        registerInvalidatedCallback {
            // Abmelden schreibt in die Datenbank (Trigger) – nicht auf dem Hauptthread, von dem Paging das oft aufruft
            // (StrictMode „DiskWriteViolation in ChannelPagingSource“, Labor 3.23).
            if (registered.get()) db.queryExecutor.execute { db.invalidationTracker.removeObserver(observer) }
        }
    }

    override suspend fun load(params: LoadParams<Int>): LoadResult<Int, IndexedValue<ChannelWithNow>> = withContext(Dispatchers.IO) {
        if (registered.compareAndSet(false, true)) db.invalidationTracker.addObserver(observer)
        val key = params.key ?: 0
        // Schlüssel: Append = Startposition der nächsten Seite; Prepend = Position, vor der geladen wird.
        val (offset, limit) = when (params) {
            is LoadParams.Prepend -> (key - params.loadSize).coerceAtLeast(0).let { it to (key - it) }
            else -> key to params.loadSize
        }
        if (limit <= 0) return@withContext LoadResult.Page(emptyList(), prevKey = null, nextKey = null)
        val rows = try {
            withNowPlaying(query(limit, offset))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return@withContext LoadResult.Error(e)
        }
        if (invalid) return@withContext LoadResult.Invalid()
        LoadResult.Page(
            // Mit Position in der ganzen Liste: Die Nummer stimmt auch, wenn die Liste mitten drin beginnt.
            data = rows.mapIndexed { i, row -> IndexedValue(offset + i, row) },
            prevKey = if (offset > 0) offset else null,
            nextKey = if (rows.size < limit) null else offset + rows.size,
        )
    }

    /** Absolute Position (nicht der Index in den geladenen Seiten – die Liste kann mitten drin beginnen). */
    override fun getRefreshKey(state: PagingState<Int, IndexedValue<ChannelWithNow>>): Int? =
        state.anchorPosition?.let { state.closestItemToPosition(it)?.index }
            ?.let { (it - state.config.initialLoadSize / 2).coerceAtLeast(0) }

    private suspend fun withNowPlaying(rows: List<ChannelWithNow>): List<ChannelWithNow> {
        if (rows.isEmpty()) return rows
        // Je EPG-Zugang eine Abfrage („Alle Zugänge“: das EPG liegt bei den Original-Zugängen).
        fun epgOf(row: ChannelWithNow) = row.channel.epgPlaylistId.takeIf { it != 0L } ?: playlistId
        val shifts = HashMap<Long, Long>()
        val now = rows.filter { it.channel.epgKey.isNotBlank() }.groupBy(::epgOf).flatMap { (pid, group) ->
            val shift = shiftFor(pid).also { shifts[pid] = it }
            group.map { it.channel.epgKey }.distinct().chunked(MAX_SQL_ARGS)
                .flatMap { chunk -> db.epgDao().nowPlaying(pid, chunk, nowMs - shift) }
                .groupBy { it.channelKey }
                .map { (key, programs) -> (pid to key) to programs.maxBy { it.startMs } }
        }.toMap()
        // Zeiten schon hier verschoben – je Zeile kann ein anderer Zugang das EPG liefern.
        return rows.map { row ->
            val pid = epgOf(row)
            val program = now[pid to row.channel.epgKey] ?: return@map row
            val shift = shifts[pid] ?: 0L
            row.copy(nowTitle = program.title, nowStart = program.startMs + shift, nowEnd = program.endMs + shift)
        }
    }

    companion object {
        /** Tabellen, die eine Aktualisierung blockweise beschreibt – Nutzer-Tabellen (Favoriten …) zählen nicht dazu. */
        val IMPORT_TABLES = setOf("channels", "channel_flags", "epg_programs")
        private val TABLES = arrayOf(*IMPORT_TABLES.toTypedArray(), "category_prefs", "favorites", "progress")
        private const val MAX_SQL_ARGS = 900
    }
}
