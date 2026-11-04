package com.mg.player.data.sync

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.mg.player.core.PlayerApp
import com.mg.player.core.log.AppLog
import com.mg.player.domain.model.AutoRefreshSchedule
import com.mg.player.domain.model.Playlist
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * Automatische Komplett-Aktualisierung (Inhalte + EPG) je Zugang nach dem eingestellten Intervall in Tagen.
 * Der Countdown steckt in [AutoRefreshSchedule] (gespeicherte Uhrzeit der letzten Aktualisierung), läuft also auch
 * weiter, während App oder Gerät aus sind. Geprüft wird an drei Stellen, alle über [runDue]:
 * - bei offener App: Wächter, der genau zum Ablauf des Countdowns aufwacht ([watch]);
 * - bei geschlossener App: einmalige Hintergrundarbeit zum Ablaufzeitpunkt und stündlich als Sicherheitsnetz
 *   ([AutoRefreshWork]);
 * - nach einem Neustart sofort beim nächsten Öffnen bzw. beim nächsten Hintergrundlauf.
 */
class AutoRefresher(
    private val playlists: suspend () -> List<Playlist>,
    /** Komplett aktualisieren (Inhalte + EPG) und warten, bis es fertig ist. */
    private val refresh: suspend (Long) -> Unit,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val mutex = Mutex()

    /** Nach einem Fehlschlag nicht sofort erneut (kaputte Quelle, kein Netz): frühestens nach [RETRY_MS]. */
    private val retryAt = ConcurrentHashMap<Long, Long>()

    /** Fällige Zugänge nacheinander aktualisieren. Liefert den nächsten Prüfzeitpunkt, null = bei keinem Zugang an. */
    suspend fun runDue(source: String): Long? = mutex.withLock {
        val due = playlists().filter { p ->
            val now = clock()
            AutoRefreshSchedule.isDue(p.lastUpdatedMs, p.autoRefreshDays, now) && (retryAt[p.id] ?: 0L) <= now
        }
        for (p in due) {
            val now = clock()
            AppLog.log(TAG, "${p.name}: Countdown abgelaufen (alle ${p.autoRefreshDays} Tage, ${ago(p.lastUpdatedMs, now)}) – aktualisiere ($source)")
            refresh(p.id)
            val after = playlists().firstOrNull { it.id == p.id } ?: continue
            if (after.lastUpdatedMs == p.lastUpdatedMs) {
                retryAt[p.id] = clock() + RETRY_MS
                AppLog.log(TAG, "${p.name}: Aktualisierung fehlgeschlagen – neuer Versuch in 1 Stunde")
            } else {
                retryAt.remove(p.id)
                AppLog.log(TAG, "${p.name}: aktualisiert – nächster Countdown ${p.autoRefreshDays} Tage")
            }
        }
        nextCheck()
    }

    /** Frühester Zeitpunkt, an dem ein Zugang fällig wird (einschließlich Wartezeit nach einem Fehlschlag). */
    suspend fun nextCheck(): Long? {
        val now = clock()
        return playlists().mapNotNull { p ->
            AutoRefreshSchedule.dueAt(p.lastUpdatedMs, p.autoRefreshDays, now)?.let { maxOf(it, retryAt[p.id] ?: 0L) }
        }.minOrNull()
    }

    /**
     * Bei offener App: prüft sofort und wacht dann genau zum nächsten Ablauf auf (spätestens nach [MAX_WAIT_MS], damit
     * eine umgestellte Geräteuhr auffällt). Jede Änderung an Zugängen (Intervall, Aktualisierung) prüft sofort neu.
     */
    fun watch(scope: CoroutineScope, source: Flow<List<Playlist>>, plan: (Long?) -> Unit): Job = scope.launch(Dispatchers.IO) {
        source.map { list -> list.map { Triple(it.id, it.lastUpdatedMs, it.autoRefreshDays) } }.distinctUntilChanged().collectLatest {
            while (isActive) {
                val next = try {
                    runDue("App")
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    AppLog.error(TAG, "Prüfung fehlgeschlagen", e)
                    nextCheck()
                }
                plan(next)
                delay(waitMs(next, clock()))
            }
        }
    }

    companion object {
        const val TAG = "Auto-Update"
        const val RETRY_MS = 60L * 60 * 1000
        const val MIN_WAIT_MS = 30_000L
        const val MAX_WAIT_MS = 5L * 60 * 1000

        fun waitMs(next: Long?, now: Long): Long = next?.let { (it - now).coerceIn(MIN_WAIT_MS, MAX_WAIT_MS) } ?: MAX_WAIT_MS

        internal fun ago(lastMs: Long, now: Long): String {
            if (lastMs <= 0L) return "noch nie geladen"
            val hours = (now - lastMs).coerceAtLeast(0) / (60 * 60 * 1000)
            return "zuletzt vor ${hours / 24} Tagen ${hours % 24} Std."
        }
    }
}

/** Hintergrundlauf: fällige Zugänge aktualisieren und den nächsten Ablauf einplanen. */
class AutoRefreshWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val container = (applicationContext as? PlayerApp)?.container ?: return Result.success()
        try {
            AutoRefreshWork.plan(applicationContext, container.autoRefresher.runDue("Hintergrund"), force = true)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AppLog.error(AutoRefresher.TAG, "Hintergrundlauf fehlgeschlagen", e)
        }
        return Result.success()
    }
}

/**
 * Einplanung über WorkManager (überlebt das Schließen der App und Neustarts des Geräts):
 * einmalig zum Ablaufzeitpunkt plus stündlich als Sicherheitsnetz, falls Fire OS den Termin verschiebt.
 */
object AutoRefreshWork {
    private const val NEXT = "auto-refresh-next"
    private const val CHECK = "auto-refresh-check"
    private const val MIN_DELAY_MS = 60_000L

    @Volatile private var planned: Long? = -1L

    /** [force]: auch bei gleichem Zeitpunkt neu einplanen (der einmalige Lauf ist gerade verbraucht). */
    fun plan(context: Context, nextAtMs: Long?, nowMs: Long = System.currentTimeMillis(), force: Boolean = false) {
        if (!force && nextAtMs == planned) return
        planned = nextAtMs
        val work = WorkManager.getInstance(context)
        if (nextAtMs == null) {
            work.cancelUniqueWork(NEXT)
            work.cancelUniqueWork(CHECK)
            return
        }
        val online = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
        val hourly = PeriodicWorkRequestBuilder<AutoRefreshWorker>(1, TimeUnit.HOURS).setConstraints(online).build()
        work.enqueueUniquePeriodicWork(CHECK, ExistingPeriodicWorkPolicy.KEEP, hourly)
        val delay = (nextAtMs - nowMs).coerceAtLeast(MIN_DELAY_MS)
        val once = OneTimeWorkRequestBuilder<AutoRefreshWorker>().setInitialDelay(delay, TimeUnit.MILLISECONDS).setConstraints(online).build()
        work.enqueueUniqueWork(NEXT, ExistingWorkPolicy.REPLACE, once)
        AppLog.log(AutoRefresher.TAG, "Nächste Prüfung in ${TimeUnit.MILLISECONDS.toMinutes(delay)} Min.")
    }
}
