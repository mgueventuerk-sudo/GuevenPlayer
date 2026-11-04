package com.mg.player.data.repository

import android.content.Context
import androidx.core.content.edit
import java.util.Calendar

/**
 * Sehgewohnheiten für die Liste „Oft gesehen“: je Sender, wie oft er morgens, mittags, abends bzw. nachts mindestens
 * zwei Minuten lief. Liegt nur auf dem Gerät (eigene Prefs-Datei), wird mit dem Verlauf gelöscht und bei
 * „Ohne Verlauf“ nicht gezählt.
 */
class ChannelHabits(context: Context, private val clock: () -> Long = System::currentTimeMillis) {
    private val prefs = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
    private var cache: MutableMap<String, IntArray>? = null

    @Synchronized
    fun record(channelId: String) {
        val counts = load()
        HabitRanking.bump(counts, channelId, HabitRanking.daypart(hourNow()))
        prefs.edit {
            clear()
            counts.forEach { (id, c) -> putString(id, c.joinToString(",")) }
        }
    }

    /** Sender-IDs für die aktuelle Tageszeit, am häufigsten zuerst. */
    @Synchronized
    fun top(limit: Int = HabitRanking.LIST_SIZE): List<String> = HabitRanking.rank(load(), HabitRanking.daypart(hourNow()), limit)

    @Synchronized
    fun clear() {
        prefs.edit { clear() }
        cache = HashMap()
    }

    private fun hourNow(): Int = Calendar.getInstance().apply { timeInMillis = clock() }.get(Calendar.HOUR_OF_DAY)

    private fun load(): MutableMap<String, IntArray> = cache ?: HashMap<String, IntArray>().also { map ->
        prefs.all.forEach { (id, value) -> HabitRanking.parse(value as? String)?.let { map[id] = it } }
        cache = map
    }

    private companion object {
        const val FILE = "channel_habits"
    }
}

/** Reine Rechenlogik (testbar ohne Android). */
object HabitRanking {
    const val PARTS = 4
    const val LIST_SIZE = 40
    const val MAX_CHANNELS = 300

    /** 0 = Morgen (5–11 Uhr), 1 = Mittag (11–17), 2 = Abend (17–23), 3 = Nacht. */
    fun daypart(hour: Int): Int = when (hour) {
        in 5..10 -> 0
        in 11..16 -> 1
        in 17..22 -> 2
        else -> 3
    }

    fun bump(counts: MutableMap<String, IntArray>, channelId: String, part: Int) {
        val c = counts.getOrPut(channelId) { IntArray(PARTS) }
        c[part] = (c[part] + 1).coerceAtMost(9_999)
        if (counts.size > MAX_CHANNELS) {
            // Selten gesehene Sender fallen heraus, damit die Datei klein bleibt.
            counts.entries.filter { it.key != channelId }.minByOrNull { it.value.sum() }?.let { counts.remove(it.key) }
        }
    }

    /** Die Tageszeit zählt dreifach, die übrigen Zeiten einfach: abends oben, was abends läuft, aber nicht nur das. */
    fun rank(counts: Map<String, IntArray>, part: Int, limit: Int): List<String> = counts.entries
        .map { (id, c) -> id to c[part] * 3 + c.sum() }
        .sortedWith(compareByDescending<Pair<String, Int>> { it.second }.thenBy { it.first })
        .take(limit)
        .map { it.first }

    fun parse(value: String?): IntArray? {
        val parts = value?.split(',')?.mapNotNull { it.trim().toIntOrNull() } ?: return null
        return if (parts.size == PARTS) parts.toIntArray() else null
    }
}
