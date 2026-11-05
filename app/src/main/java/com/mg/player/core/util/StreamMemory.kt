package com.mg.player.core.util

import androidx.core.content.edit
import java.util.concurrent.ConcurrentHashMap

/**
 * Gemerkt: Sender-Gruppe -> Stream-Variante, die zuletzt funktioniert hat (Auto-Failover). Mit [store] bleibt das
 * über einen App-Neustart erhalten – ein langsamer oder kaputter Primär-Stream kostet dann nicht bei jedem Start
 * wieder 3 Sekunden. Nach [MAX_AGE_MS] wird die erste Qualität wieder probiert (der Anbieter kann sie repariert haben).
 * Der Schlüssel enthält den Zugang: Zwei Zugänge mit gleichem Sendernamen überschreiben sich nicht,
 * und beim Löschen eines Zugangs verschwinden seine Einträge.
 */
class StreamMemory(private val store: Store? = null, private val clock: () -> Long = System::currentTimeMillis) {
    /** Dauerhafter Speicher (z. B. SharedPreferences); Werte im Format "variantId|zeitpunkt". */
    interface Store {
        fun all(): Map<String, String>
        fun put(key: String, value: String)
        fun remove(keys: Collection<String>)
    }

    private val map = ConcurrentHashMap<String, Entry>()

    private class Entry(val variantId: String, val atMs: Long)

    init {
        store?.all()?.forEach { (key, value) ->
            val variant = value.substringBeforeLast('|')
            val at = value.substringAfterLast('|').toLongOrNull() ?: return@forEach
            if (variant.isNotEmpty()) map[key] = Entry(variant, at)
        }
    }

    operator fun get(playlistId: Long, groupKey: String): String? {
        if (groupKey.isBlank()) return null
        val key = key(playlistId, groupKey)
        val entry = map[key] ?: return null
        if (clock() - entry.atMs > MAX_AGE_MS) {
            map.remove(key)
            store?.remove(listOf(key))
            return null
        }
        return entry.variantId
    }

    fun remember(playlistId: Long, groupKey: String, variantId: String) {
        if (groupKey.isBlank()) return
        val key = key(playlistId, groupKey)
        if (map[key]?.variantId == variantId) return
        val now = clock()
        map[key] = Entry(variantId, now)
        store?.put(key, "$variantId|$now")
    }

    fun forget(playlistId: Long) {
        val prefix = "$playlistId:"
        val removed = ArrayList<String>()
        // Bewusst per Iterator: removeIf gibt es erst ab Android 7 (minSdk ist 23).
        val iterator = map.keys.iterator()
        while (iterator.hasNext()) {
            val key = iterator.next()
            if (key.startsWith(prefix)) {
                iterator.remove()
                removed += key
            }
        }
        if (removed.isNotEmpty()) store?.remove(removed)
    }

    private fun key(playlistId: Long, groupKey: String) = "$playlistId:$groupKey"

    companion object {
        const val MAX_AGE_MS = 3 * 24 * 3_600_000L
    }
}

/** [StreamMemory.Store] in eigenen SharedPreferences (klein: ein Eintrag je Sender mit mehreren Qualitäten). */
class PrefsStreamStore(context: android.content.Context, name: String = "stream_memory") : StreamMemory.Store {
    private val prefs = context.getSharedPreferences(name, android.content.Context.MODE_PRIVATE)

    override fun all(): Map<String, String> = prefs.all.mapNotNull { (k, v) -> (v as? String)?.let { k to it } }.toMap()

    override fun put(key: String, value: String) {
        prefs.edit { putString(key, value) }
    }

    override fun remove(keys: Collection<String>) {
        prefs.edit { keys.forEach(::remove) }
    }
}
