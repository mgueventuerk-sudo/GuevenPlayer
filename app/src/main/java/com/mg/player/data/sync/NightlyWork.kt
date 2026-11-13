package com.mg.player.data.sync

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.mg.player.core.PlayerApp
import com.mg.player.core.log.AppLog
import com.mg.player.data.db.AppDatabase
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

/**
 * Nächtliche Pflege, wenn das Gerät nichts zu tun hat (Bildschirm aus, im WLAN/LAN, genug Speicher):
 * EPG aller Zugänge erneuern, sofern fällig, und die Datenbank verkleinern, wenn viel Platz frei geworden ist.
 * Bis 3.12 lief das EPG nur bei offener App – also genau dann, wenn man schaut, und auf derselben Leitung.
 * Bei offener App bleibt alles wie bisher (Prüfung alle 6 Stunden).
 */
class NightlyWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val container = (applicationContext as? PlayerApp)?.container ?: return Result.success()
        try {
            for (playlist in container.playlistRepository.observePlaylists().first()) {
                if (container.syncManager.state.value.running) break
                if (container.epgRepository.shouldAutoLoad(playlist.id)) {
                    AppLog.log("Nacht", "EPG wird erneuert (${playlist.name})")
                    container.syncManager.refreshEpg(playlist.id).join()
                }
            }
            refreshChannelCatalog(container)
            // Stichwort-Autoaufnahme lief bisher nur, solange die App offen war (AppRoot.keepPlanned()) – ohne
            // diesen Aufruf frieren Regeln ein, sobald die App tagelang nicht geöffnet wird.
            container.autoRecorder.plan()
            DbMaintenance.vacuumIfWorthIt(container.database, applicationContext.filesDir.usableSpace)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AppLog.error("Nacht", "Pflege fehlgeschlagen (Versuch ${runAttemptCount + 1})", e)
            // Nicht endlos wiederholen (Akku/Netz bei dauerhaft kaputter Quelle): nach 3 Versuchen bis zur nächsten Nacht.
            return if (runAttemptCount < MAX_RETRIES) Result.retry() else Result.success()
        }
        return Result.success()
    }

    /** Senderkatalog mit dem Internet abgleichen (wöchentlich), wenn „Alle Zugänge“ genutzt wird – danach neu zuordnen. */
    private suspend fun refreshChannelCatalog(container: com.mg.player.core.di.AppContainer) {
        if (!container.combined.builder.available) return
        val outcome = container.combined.refreshCatalog(force = false)
        if (outcome is com.mg.player.data.combined.ChannelCatalogUpdate.Outcome.Updated) {
            container.combined.builder.rebuildLater("Senderkatalog aktualisiert")
        }
    }

    companion object {
        private const val NAME = "nightly-maintenance"
        private const val MAX_RETRIES = 2

        /** Einmal am Tag, nur im Leerlauf und ohne getaktete Verbindung. Mehrfaches Einplanen ändert nichts. */
        fun schedule(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.UNMETERED)
                .setRequiresDeviceIdle(true)
                .setRequiresStorageNotLow(true)
                .build()
            val request = PeriodicWorkRequestBuilder<NightlyWorker>(24, TimeUnit.HOURS, 6, TimeUnit.HOURS)
                .setConstraints(constraints)
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(NAME, ExistingPeriodicWorkPolicy.KEEP, request)
        }
    }
}

/** Datenbank-Pflege: VACUUM nur, wenn es sich lohnt und genug Platz für die temporäre Kopie da ist. */
object DbMaintenance {
    private const val MIN_FREE_RATIO = 0.3
    private const val MIN_FREE_BYTES = 16L * 1024 * 1024

    /** Lohnt sich ein VACUUM? (VACUUM braucht vorübergehend etwa die Größe der Datenbank zusätzlich.) */
    fun shouldVacuum(pageCount: Long, freePages: Long, pageSize: Long, usableBytes: Long): Boolean {
        if (pageCount <= 0) return false
        val freeBytes = freePages * pageSize
        val dbBytes = pageCount * pageSize
        return freePages.toDouble() / pageCount >= MIN_FREE_RATIO && freeBytes >= MIN_FREE_BYTES && usableBytes > dbBytes * 2
    }

    suspend fun vacuumIfWorthIt(db: AppDatabase, usableBytes: Long): Boolean = withContext(Dispatchers.IO) {
        val sql = db.openHelper.writableDatabase
        fun pragma(name: String): Long = sql.query("PRAGMA $name").use { if (it.moveToFirst()) it.getLong(0) else 0L }
        val pages = pragma("page_count")
        val free = pragma("freelist_count")
        val size = pragma("page_size")
        if (!shouldVacuum(pages, free, size, usableBytes)) return@withContext false
        val started = System.currentTimeMillis()
        sql.execSQL("VACUUM")
        AppLog.log("Nacht", "Datenbank verkleinert: ${free * size / 1_048_576} MB frei gegeben (${System.currentTimeMillis() - started} ms)")
        true
    }
}
