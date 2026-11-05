package com.mg.player.data.repository

import com.mg.player.core.log.AppLog
import com.mg.player.core.util.NameSort
import com.mg.player.data.db.AppDatabase
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Hält die vorberechnete Sender-Gruppierung (channel_flags) aktuell: nach jedem Import und jeder Änderung an
 * ausgeblendeten oder gesperrten Gruppen. Mehrere Änderungen kurz hintereinander (z. B. zehn Gruppen
 * ausblenden) führen zu höchstens einem weiteren Neuaufbau, nicht zu zehn.
 */
class ChannelGroupIndex(
    private val db: AppDatabase,
    private val scope: CoroutineScope,
    /** Gleiche Sender eines Zugangs vor dem Gruppieren zusammenfassen (nach einem Import). */
    private val unify: suspend (playlistId: Long) -> Unit = {},
    /** Sender dieses Zugangs alphabetisch statt in Anbieter-Reihenfolge (Einstellung „Reihenfolge in Gruppen“). */
    private val alphabetical: suspend (playlistId: Long) -> Boolean = { false },
) {

    private val mutex = Mutex()

    // Nicht ConcurrentHashMap.newKeySet(): das gibt es erst ab Android 7 (minSdk ist 23).
    private val dirty: MutableSet<Long> = Collections.newSetFromMap(ConcurrentHashMap())

    /** Sofort neu aufbauen (Import); [unifyFirst]: vorher gleiche Sender zusammenfassen. */
    suspend fun rebuild(playlistId: Long, unifyFirst: Boolean = false) = withContext(Dispatchers.IO) {
        mutex.withLock {
            if (unifyFirst) unify(playlistId)
            rebuildLocked(playlistId)
        }
    }

    private suspend fun rebuildLocked(playlistId: Long) {
        dirty.remove(playlistId)
        val started = System.currentTimeMillis()
        val dao = db.channelFlagsDao()
        dao.rebuild(playlistId)
        // Alphabetisch: Reihenfolge in Kotlin bilden (natürliche Sortierung ohne Länder-Vorsätze, siehe NameSort) –
        // die Listen bleiben so ein reiner Index-Zugriff auf channel_flags.sortIndex, auch beim Umschalten.
        val alpha = alphabetical(playlistId)
        if (alpha) {
            val ordered = dao.names(playlistId).sortedWith(compareBy(NameSort.comparator) { it.name }).map { it.id }
            dao.reorder(ordered)
        }
        val ms = System.currentTimeMillis() - started
        AppLog.log("Gruppen", "Sender-Gruppierung neu aufgebaut (${if (alpha) "A–Z, " else ""}$ms ms)")
    }

    /** Im Hintergrund neu aufbauen (Gruppe ein-/ausgeblendet, Länderauswahl, Kindersicherung). */
    fun rebuildLater(playlistId: Long) {
        if (!dirty.add(playlistId)) return
        scope.launch {
            try {
                if (dirty.contains(playlistId)) rebuild(playlistId)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLog.error("Gruppen", "Sender-Gruppierung konnte nicht neu aufgebaut werden", e)
            }
        }
    }

    /** Fehlt die Gruppierung (erster Start nach dem Update, abgebrochener Import), wird sie jetzt erstellt. */
    suspend fun ensure(playlistId: Long) = withContext(Dispatchers.IO) {
        val dao = db.channelFlagsDao()
        if (dao.hasFlags(playlistId)) return@withContext
        // Öffnen zwei Listen gleichzeitig (Senderliste + Vorschau), baut nur die erste auf.
        mutex.withLock {
            if (!dao.hasFlags(playlistId) && db.channelDao().count(playlistId) > 0) rebuildLocked(playlistId)
        }
    }

    suspend fun clear(playlistId: Long) = withContext(Dispatchers.IO) { db.channelFlagsDao().clear(playlistId) }
}
