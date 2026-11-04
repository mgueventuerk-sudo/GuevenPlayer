package com.mg.player.data.reminder

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.edit
import androidx.core.net.toUri
import com.mg.player.R
import com.mg.player.core.log.AppLog
import com.mg.player.core.util.TimeUtils
import com.mg.player.domain.model.Reminder
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.json.JSONArray
import org.json.JSONObject

/**
 * Erinnerungen an Sendungen: [LEAD_MS] vor Beginn erscheint in der offenen App ein Hinweis mit „Jetzt umschalten“,
 * sonst eine Benachrichtigung. Gespeichert in einer eigenen Datei, Wecker wie bei geplanten Aufnahmen.
 */
class ReminderManager(private val context: Context, private val clock: () -> Long = System::currentTimeMillis) {

    private val prefs = context.getSharedPreferences("reminders", Context.MODE_PRIVATE)
    private val _items = MutableStateFlow(load())
    val items: StateFlow<List<Reminder>> = _items.asStateFlow()

    private val _due = MutableSharedFlow<Reminder>(extraBufferCapacity = 4)

    /** Fällige Erinnerungen, während die App offen ist (die Oberfläche zeigt dann den Hinweis). */
    val due: SharedFlow<Reminder> = _due

    fun isSet(channelId: String, startMs: Long): Boolean = _items.value.any { it.channelId == channelId && it.startMs == startMs }

    /** Setzt bzw. löscht die Erinnerung und liefert die Rückmeldung für den Nutzer. */
    fun toggle(reminder: Reminder): String {
        if (isSet(reminder.channelId, reminder.startMs)) {
            remove(reminder.id)
            return "Erinnerung an „${reminder.title}“ gelöscht"
        }
        if (reminder.startMs <= clock()) return "Diese Sendung hat schon begonnen"
        _items.update { (it + reminder).sortedBy { r -> r.startMs } }
        save()
        schedule(reminder)
        AppLog.log("Erinnerung", "${reminder.title} · ${TimeUtils.formatDate(reminder.startMs)}")
        return "Erinnerung gesetzt: „${reminder.title}“ um ${TimeUtils.formatClock(reminder.startMs)} – ${LEAD_MS / 60_000} Minuten vorher"
    }

    fun remove(id: String) {
        _items.value.firstOrNull { it.id == id }?.let { cancel(it) }
        _items.update { list -> list.filterNot { it.id == id } }
        save()
    }

    /** Nach App-Start/Neustart: Wecker neu stellen, Vergangenes aufräumen. */
    fun rescheduleAll() {
        val now = clock()
        _items.update { list -> list.filter { it.startMs > now - 30 * 60_000L } }
        save()
        _items.value.filter { it.startMs > now }.forEach(::schedule)
    }

    /** Wecker hat geklingelt: in der offenen App anzeigen, sonst als Benachrichtigung. */
    fun fire(id: String, appVisible: Boolean) {
        val reminder = _items.value.firstOrNull { it.id == id } ?: return
        remove(id)
        if (appVisible && _due.tryEmit(reminder)) return
        notify(reminder)
    }

    private fun schedule(reminder: Reminder) {
        val manager = context.getSystemService(AlarmManager::class.java) ?: return
        val at = (reminder.startMs - LEAD_MS).coerceAtLeast(clock() + 1_000)
        runCatching {
            val exact = Build.VERSION.SDK_INT < Build.VERSION_CODES.S || manager.canScheduleExactAlarms()
            if (exact) {
                manager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, alarmIntent(reminder.id))
            } else {
                manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, alarmIntent(reminder.id))
            }
        }.onFailure { AppLog.error("Erinnerung", "Wecker konnte nicht gestellt werden", it) }
    }

    private fun cancel(reminder: Reminder) {
        runCatching { context.getSystemService(AlarmManager::class.java)?.cancel(alarmIntent(reminder.id)) }
    }

    private fun alarmIntent(id: String): PendingIntent = PendingIntent.getBroadcast(
        context,
        id.hashCode(),
        Intent(context, ReminderReceiver::class.java)
            .setAction(ACTION_FIRE)
            .setData("guevenplayer://reminder/${id.hashCode()}".toUri())
            .putExtra(EXTRA_ID, id),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun notify(reminder: Reminder) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && manager.getNotificationChannel(CHANNEL_ID) == null) {
            manager.createNotificationChannel(NotificationChannel(CHANNEL_ID, "Erinnerungen", NotificationManager.IMPORTANCE_HIGH))
        }
        val open = PendingIntent.getActivity(
            context,
            reminder.id.hashCode(),
            LaunchRequest.playChannelIntent(context, reminder.channelId),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_record)
            .setContentTitle("Gleich: ${reminder.title}")
            .setContentText("${reminder.channelName} · ${TimeUtils.formatClock(reminder.startMs)} Uhr")
            .setContentIntent(open)
            .addAction(0, "Jetzt umschalten", open)
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()
        runCatching { manager.notify(reminder.id.hashCode(), notification) }
            .onFailure { AppLog.log("Erinnerung", "Benachrichtigung nicht erlaubt") }
    }

    private fun load(): List<Reminder> = runCatching {
        val array = JSONArray(prefs.getString(KEY, "[]"))
        (0 until array.length()).map { i ->
            val o = array.getJSONObject(i)
            Reminder(o.getString("channelId"), o.getString("channelName"), o.getString("title"), o.getLong("startMs"))
        }
    }.getOrDefault(emptyList())

    private fun save() {
        val array = JSONArray()
        _items.value.forEach {
            array.put(JSONObject().put("channelId", it.channelId).put("channelName", it.channelName).put("title", it.title).put("startMs", it.startMs))
        }
        prefs.edit { putString(KEY, array.toString()) }
    }

    companion object {
        const val LEAD_MS = 2 * 60_000L
        const val ACTION_FIRE = "com.mg.player.action.REMINDER"
        const val EXTRA_ID = "reminderId"
        private const val KEY = "items"
        private const val CHANNEL_ID = "reminders"
    }
}
