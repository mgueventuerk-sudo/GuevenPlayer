package com.mg.player.data.recording

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.net.toUri
import com.mg.player.core.PlayerApp
import com.mg.player.core.log.AppLog
import kotlinx.coroutines.launch

/**
 * Weckt die App zum Beginn einer geplanten Aufnahme. Nutzt exakte Alarme, wenn Android sie erlaubt,
 * sonst einen ungefähren Alarm (kann dann ein bis zwei Minuten später starten – dafür gibt es den Vorlauf).
 */
class RecordingAlarms(private val context: Context) {

    private val alarmManager: AlarmManager? get() = context.getSystemService(AlarmManager::class.java)

    fun set(recordingId: String, atMs: Long) {
        val manager = alarmManager ?: return
        val intent = pendingIntent(recordingId)
        runCatching {
            val exact = Build.VERSION.SDK_INT < Build.VERSION_CODES.S || manager.canScheduleExactAlarms()
            if (exact) {
                manager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMs, intent)
            } else {
                manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMs, intent)
            }
        }.onFailure { AppLog.error("Aufnahme", "Wecker für geplante Aufnahme konnte nicht gestellt werden", it) }
    }

    fun cancel(recordingId: String) {
        runCatching { alarmManager?.cancel(pendingIntent(recordingId)) }
    }

    private fun pendingIntent(recordingId: String): PendingIntent = PendingIntent.getBroadcast(
        context,
        recordingId.hashCode(),
        Intent(context, RecordingAlarmReceiver::class.java)
            .setAction(ACTION_START)
            // Eindeutige Daten-URI, damit sich mehrere geplante Aufnahmen nicht gegenseitig überschreiben.
            .setData("guevenplayer://recording/$recordingId".toUri())
            .putExtra(EXTRA_ID, recordingId),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    companion object {
        const val ACTION_START = "com.mg.player.action.START_RECORDING"
        const val EXTRA_ID = "recordingId"
    }
}

/** Startet eine geplante Aufnahme, wenn ihr Wecker klingelt. */
class RecordingAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != RecordingAlarms.ACTION_START) return
        val id = intent.getStringExtra(RecordingAlarms.EXTRA_ID) ?: return
        val container = (context.applicationContext as PlayerApp).container
        val pending = goAsync()
        container.appScope.launch {
            try {
                container.recordingManager.startScheduled(id)
            } finally {
                pending.finish()
            }
        }
    }
}

/** Nach einem Neustart sind alle Wecker gelöscht – geplante Aufnahmen werden neu eingeplant. */
class RecordingBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        // Nur die beiden erwarteten System-Ereignisse – alles andere ignorieren.
        if (intent.action != Intent.ACTION_BOOT_COMPLETED && intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        val container = (context.applicationContext as PlayerApp).container
        val pending = goAsync()
        container.appScope.launch {
            try {
                container.recordingManager.awaitReady()
            } finally {
                pending.finish()
            }
        }
    }
}
