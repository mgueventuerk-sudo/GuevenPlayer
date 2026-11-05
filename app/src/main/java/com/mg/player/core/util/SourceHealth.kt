package com.mg.player.core.util

import java.util.concurrent.ConcurrentHashMap
import kotlin.math.pow

/**
 * Gedächtnis des Wächters je Stream-Quelle (Variante eines Senders bzw. Quelle eines Films aus „Alle Zugänge“), ab 3.50:
 * Jeder Hänger – auch nur eine Sekunde –, jeder langsame oder gescheiterte Start und jeder Fehler zählt als Strafpunkt.
 * Beim nächsten Aufruf kommen die Quellen mit den wenigsten Problemen zuerst ([rank]); die Qualität entscheidet erst
 * danach. Strafpunkte verfallen von selbst (Halbwertszeit [HALF_LIFE_MS]) und halbieren sich nach stabiler Wiedergabe
 * – der Anbieter kann eine Quelle repariert haben. Gespeichert nur Quellen-ID, Punkte und Zeit (keine Adressen).
 */
class SourceHealth(private val store: StreamMemory.Store? = null, private val clock: () -> Long = System::currentTimeMillis) {

    private class Entry(val penalty: Double, val atMs: Long)

    private val map = ConcurrentHashMap<String, Entry>()

    init {
        store?.all()?.forEach { (key, value) ->
            val penalty = value.substringBefore('|').toDoubleOrNull() ?: return@forEach
            val at = value.substringAfter('|').toLongOrNull() ?: return@forEach
            map[key] = Entry(penalty, at)
        }
    }

    /** Aktuelle Strafpunkte (verfallen mit der Zeit), 0 = keine Probleme bekannt. */
    fun penalty(sourceId: String): Double {
        val entry = map[sourceId] ?: return 0.0
        return decayed(entry, clock())
    }

    /**
     * Rang für die Reihenfolge: 0 = unauffällig, 1 = ein kurzer Hänger, … höchstens [MAX_RANK]. Gestuft, damit kleine
     * Unterschiede (Verfall um Minuten) die Reihenfolge gleich guter Quellen nicht durcheinanderbringen.
     */
    fun rank(sourceId: String): Int {
        val p = penalty(sourceId)
        return if (p < HALF_POINT) 0 else (p + HALF_POINT).toInt().coerceAtMost(MAX_RANK)
    }

    /** Problem der Quelle merken – sofort, auch über einen Neustart. */
    fun record(sourceId: String, points: Double) {
        if (sourceId.isBlank() || points <= 0) return
        val now = clock()
        val total = (penalty(sourceId) + points).coerceAtMost(MAX_PENALTY)
        put(sourceId, Entry(total, now))
    }

    /** Stabile Wiedergabe: Strafpunkte halbieren (eine reparierte Quelle steigt wieder auf). */
    fun recordGood(sourceId: String) {
        val now = clock()
        val current = map[sourceId]?.let { decayed(it, now) } ?: return
        val half = current / 2
        if (half < FORGET_BELOW) remove(listOf(sourceId)) else put(sourceId, Entry(half, now))
    }

    /** Zugang gelöscht: seine Quellen vergessen (IDs beginnen mit „<Zugang>:“). */
    fun forget(playlistId: Long) {
        val prefix = "$playlistId:"
        remove(map.keys.filter { it.startsWith(prefix) })
    }

    private fun put(sourceId: String, entry: Entry) {
        map[sourceId] = entry
        store?.put(sourceId, "${entry.penalty}|${entry.atMs}")
        if (map.size > MAX_ENTRIES) prune()
    }

    private fun remove(keys: List<String>) {
        if (keys.isEmpty()) return
        keys.forEach(map::remove)
        store?.remove(keys)
    }

    /** Verfallene und die ältesten Einträge entfernen – mit Luft ([PRUNE_TO]), damit nicht jeder neue Eintrag sortiert. */
    private fun prune() {
        val now = clock()
        val (gone, rest) = map.entries.partition { decayed(it.value, now) < FORGET_BELOW }
        val oldest = rest.sortedBy { it.value.atMs }.take((rest.size - PRUNE_TO).coerceAtLeast(0))
        remove((gone + oldest).map { it.key })
    }

    private fun decayed(entry: Entry, now: Long): Double {
        val age = (now - entry.atMs).coerceAtLeast(0)
        return entry.penalty * 0.5.pow(age.toDouble() / HALF_LIFE_MS)
    }

    companion object {
        /** Kurzer Hänger (ab 1 s) oder langsamer Start. */
        const val SHORT_STALL = 1.0

        /** Längerer Hänger (ab 3 s). */
        const val LONG_STALL = 2.0

        /** Start gescheitert, Fehler oder vom Wächter verlassen. */
        const val FAILED = 3.0

        const val HALF_LIFE_MS = 12L * 3_600_000
        const val MAX_RANK = 9
        private const val HALF_POINT = 0.5
        private const val MAX_PENALTY = 20.0
        private const val FORGET_BELOW = 0.1
        private const val MAX_ENTRIES = 2_000
        private const val PRUNE_TO = MAX_ENTRIES * 9 / 10
    }
}
