package com.mg.player.data.recording

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.mg.player.R
import com.mg.player.core.log.AppLog

/**
 * Vordergrund-Dienst, solange mindestens eine Aufnahme läuft. Er nimmt selbst nichts auf,
 * sondern verhindert nur, dass Android die App im Hintergrund beendet.
 */
class RecordingService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        createChannel()
        val text = intent?.getStringExtra(EXTRA_TEXT) ?: "Aufnahme läuft"
        val openApp = PendingIntent.getActivity(
            this,
            0,
            packageManager.getLaunchIntentForPackage(packageName) ?: Intent(),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_record)
            .setContentTitle("GuevenPlayer nimmt auf")
            .setContentText(text)
            .setOngoing(true)
            .setContentIntent(openApp)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        return START_NOT_STICKY
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        manager.createNotificationChannel(NotificationChannel(CHANNEL_ID, "Aufnahmen", NotificationManager.IMPORTANCE_LOW))
    }

    companion object {
        private const val CHANNEL_ID = "recordings"
        private const val NOTIFICATION_ID = 4711
        private const val EXTRA_TEXT = "text"

        /** true, wenn der Vordergrund-Dienst lief - false bedeutet: Aufnahme ohne Android-Schutz vor dem Beenden. */
        fun start(context: Context, text: String): Boolean = runCatching {
            ContextCompat.startForegroundService(
                context,
                Intent(context, RecordingService::class.java).putExtra(EXTRA_TEXT, text),
            )
        }.onFailure { AppLog.error("Aufnahme", "Hintergrunddienst konnte nicht starten", it) }.isSuccess

        fun stop(context: Context) {
            runCatching { context.stopService(Intent(context, RecordingService::class.java)) }
        }
    }
}
