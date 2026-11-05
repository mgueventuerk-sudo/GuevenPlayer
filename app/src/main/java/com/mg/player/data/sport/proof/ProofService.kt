package com.mg.player.data.sport.proof

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.mg.player.R
import com.mg.player.core.PlayerApp
import com.mg.player.core.log.AppLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Beweissuche im Hintergrund (Plan „Spiel-Finder“ N): Ein Wecker 2 Minuten vor dem nächsten Anpfiff startet diesen kurzen
 * Vordergrund-Dienst. Er holt die Spiele, sucht höchstens [RUN_MS] lang jede Minute Beweise (nur über freie Playlists) und
 * beendet sich, dann wird der nächste Wecker gestellt. Nur auf dem Gerät, das in den Einstellungen „prüft im
 * Hintergrund“ ist (Plan O: ein Prüfgerät, z. B. der Fire TV Cube).
 */
class ProofService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var job: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startInForeground()
        if (job?.isActive != true) job = scope.launch { hunt() }
        return START_NOT_STICKY
    }

    private suspend fun hunt() {
        val container = (applicationContext as PlayerApp).container
        try {
            if (container.settingsRepository.current().sportProofDevice) {
                container.sport.repository.refresh()
                val until = System.currentTimeMillis() + RUN_MS
                while (System.currentTimeMillis() < until) {
                    container.sport.hunter.run(container.sport.repository::rematch)
                    delay(ROUND_MS)
                }
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            AppLog.error("Sport", "Beweissuche im Hintergrund", e)
        } finally {
            ProofAlarm.scheduleNext(applicationContext)
            stopSelf()
        }
    }

    private fun startInForeground() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            getSystemService(NotificationManager::class.java)?.let { manager ->
                if (manager.getNotificationChannel(CHANNEL_ID) == null) {
                    manager.createNotificationChannel(NotificationChannel(CHANNEL_ID, "Spiel-Finder", NotificationManager.IMPORTANCE_MIN))
                }
            }
        }
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_record)
            .setContentTitle("Spiel-Finder prüft Sender …")
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL_ID = "sport_proof"
        private const val NOTIFICATION_ID = 4712
        private const val RUN_MS = 15 * 60_000L
        private const val ROUND_MS = 60_000L

        fun start(context: Context) {
            runCatching { ContextCompat.startForegroundService(context, Intent(context, ProofService::class.java)) }
                .onFailure { AppLog.error("Sport", "Beweissuche im Hintergrund nicht startbar", it) }
        }
    }
}

/** Wecker für die Beweissuche: 2 Minuten vor dem nächsten Anpfiff eines gefolgten Wettbewerbs (Plan „Spiel-Finder“ N). */
object ProofAlarm {
    private const val LEAD_MS = 2 * 60_000L
    private const val LOOKAHEAD_MS = 3 * 24 * 3_600_000L
    private const val REQUEST_CODE = 4712

    /** Nächsten Wecker stellen (oder löschen, wenn das Gerät nicht prüft). Blockiert nicht: läuft im App-Bereich. */
    fun scheduleNext(context: Context) {
        val container = (context.applicationContext as PlayerApp).container
        container.appScope.launch(Dispatchers.IO) {
            val manager = context.getSystemService(AlarmManager::class.java) ?: return@launch
            val intent = pending(context)
            if (!container.settingsRepository.current().sportProofDevice) {
                manager.cancel(intent)
                return@launch
            }
            val now = System.currentTimeMillis()
            // Erstes Spiel, das noch nicht (fast) begonnen hat: dessen Anpfiff minus Vorlauf.
            val next = container.database.sportDao().eventsStartingBetween(now + LEAD_MS, now + LOOKAHEAD_MS).firstOrNull() ?: return@launch
            val at = next.startMs - LEAD_MS
            runCatching {
                val exact = Build.VERSION.SDK_INT < Build.VERSION_CODES.S || manager.canScheduleExactAlarms()
                if (exact) {
                    manager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, intent)
                } else {
                    manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, intent)
                }
            }.onFailure { AppLog.error("Sport", "Wecker für die Beweissuche nicht gestellt", it) }
        }
    }

    private fun pending(context: Context): PendingIntent = PendingIntent.getBroadcast(
        context,
        REQUEST_CODE,
        Intent(context, ProofAlarmReceiver::class.java),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )
}

/** Wecker hat geklingelt: Beweissuche starten. */
class ProofAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        ProofService.start(context)
    }
}
