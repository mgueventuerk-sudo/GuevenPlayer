package com.mg.player.data.sport.proof

import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.isActive

/**
 * Laufende Blicke der Bildbeweis-Suche je Zugang (Regel „Freie Playlist“ 4). Ein Blick hält die Verbindung eines Zugangs
 * bis etwa 18 Sekunden. Viele Zugänge erlauben nur eine – startet man in dieser Zeit selbst einen Sender darüber, muss der
 * Blick sofort weichen. Bis 3.60 sperrte die Freigabe nur künftige Runden; der laufende Blick lief weiter, und das
 * Zugangs-Tor meldete den eigenen Blick als „Zugang belegt“.
 */
class ActiveProbes {
    private val running = ConcurrentHashMap<Long, Job>()

    fun isRunning(playlistId: Long): Boolean = running[playlistId]?.isActive == true

    /** Laufenden Blick über [playlistId] beenden; true, wenn einer lief. */
    fun stop(playlistId: Long): Boolean = running[playlistId]?.takeIf { it.isActive }?.let { it.cancel(); true } == true

    /**
     * [block] als Blick über [playlistId] ausführen. Wird er über [stop] beendet, kommt null zurück – der Aufrufer (die
     * Suchschleife) läuft weiter. Wird der Aufrufer selbst abgebrochen, bricht auch dieser Aufruf ab.
     */
    suspend fun <T> run(playlistId: Long, block: suspend () -> T): T? = coroutineScope {
        val look = async { block() }
        running[playlistId] = look
        try {
            look.await()
        } catch (e: CancellationException) {
            if (look.isCancelled && isActive) null else throw e
        } finally {
            running.remove(playlistId, look)
        }
    }
}
