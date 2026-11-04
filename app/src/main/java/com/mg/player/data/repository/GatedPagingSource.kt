package com.mg.player.data.repository

import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.PagingSource
import androidx.paging.PagingState
import androidx.room.InvalidationTracker
import com.mg.player.data.db.AppDatabase
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Seitenweises Laden (LIMIT/OFFSET) für Film- und Serien-Raster mit eigenem Datenbank-Beobachter.
 *
 * Die von Room erzeugte Paging-Quelle lädt bei JEDEM Schreibzugriff auf ihre Tabellen neu – beim Import
 * (Blöcke zu 1.000 Titeln) also dutzendfach hintereinander, jedes Mal mit Zählabfrage über die ganze
 * Tabelle. Das Raster springt und ruckelt dann. Hier entscheidet der Aufrufer: Import-Blöcke werden
 * gesammelt und einmal nach der Aktualisierung übernommen; Favoriten oder Fortschritt sofort.
 */
class GatedPagingSource<T : Any>(
    private val db: AppDatabase,
    tables: Array<String>,
    private val onTablesChanged: (source: GatedPagingSource<T>, tables: Set<String>) -> Unit,
    private val query: suspend (limit: Int, offset: Int) -> List<T>,
) : PagingSource<Int, T>() {

    private val registered = AtomicBoolean(false)
    private val observer = object : InvalidationTracker.Observer(tables) {
        override fun onInvalidated(tables: Set<String>) = onTablesChanged(this@GatedPagingSource, tables)
    }

    init {
        registerInvalidatedCallback {
            if (registered.get()) db.invalidationTracker.removeObserver(observer)
        }
    }

    override suspend fun load(params: LoadParams<Int>): LoadResult<Int, T> = withContext(Dispatchers.IO) {
        if (registered.compareAndSet(false, true)) db.invalidationTracker.addObserver(observer)
        val key = params.key ?: 0
        val (offset, limit) = when (params) {
            is LoadParams.Prepend -> (key - params.loadSize).coerceAtLeast(0).let { it to (key - it) }
            else -> key to params.loadSize
        }
        if (limit <= 0) return@withContext LoadResult.Page(emptyList(), prevKey = null, nextKey = null)
        val rows = try {
            query(limit, offset)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return@withContext LoadResult.Error(e)
        }
        if (invalid) return@withContext LoadResult.Invalid()
        LoadResult.Page(
            data = rows,
            prevKey = if (offset > 0) offset else null,
            nextKey = if (rows.size < limit) null else offset + rows.size,
        )
    }

    override fun getRefreshKey(state: PagingState<Int, T>): Int? =
        state.anchorPosition?.let { anchor -> (anchor - state.config.initialLoadSize / 2).coerceAtLeast(0) }

    companion object {
        /**
         * Paging-Flow mit gesammelter Invalidierung: Änderungen an [importTables] während [syncRunning]
         * werden erst nach der Aktualisierung übernommen, alle anderen sofort.
         */
        fun <T : Any> pagingFlow(
            db: AppDatabase,
            tables: Array<String>,
            importTables: Set<String>,
            syncRunning: StateFlow<Boolean>,
            query: suspend (limit: Int, offset: Int) -> List<T>,
        ): Flow<PagingData<T>> = flow {
            val latest = AtomicReference<GatedPagingSource<T>?>(null)
            val pending = AtomicBoolean(false)
            val onChanged: (GatedPagingSource<T>, Set<String>) -> Unit = { source, changed ->
                if (syncRunning.value && changed.any { it in importTables }) pending.set(true) else source.invalidate()
            }
            val pager = Pager(PagingConfig(pageSize = 60, prefetchDistance = 36, enablePlaceholders = false)) {
                GatedPagingSource(db, tables, onChanged, query).also { latest.set(it) }
            }
            coroutineScope {
                launch {
                    syncRunning.collect { running ->
                        if (!running && pending.getAndSet(false)) latest.get()?.invalidate()
                    }
                }
                try {
                    emitAll(pager.flow)
                } finally {
                    // Raster verlassen: Beobachter sofort abmelden.
                    latest.get()?.invalidate()
                }
            }
        }
    }
}
