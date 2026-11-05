package com.mg.player.data.sport.proof

import com.mg.player.core.util.StreamMemory
import com.mg.player.data.sport.proof.ProofEvaluator.Verdict
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import java.util.concurrent.ConcurrentHashMap

/**
 * Beweise je Spiel und Sender (Plan „Spiel-Finder“ C/H): bewiesen, Platzhalter oder anderes Programm – aus dem Bildbeweis,
 * aus deinem Zuschauen oder deiner Wahl. Schlüssel ist bei normalen Sendern der Senderschlüssel (gilt für alle Playlists,
 * Regel „Freie Playlist“ 3), bei Event-Kanälen die Kanal-ID (gibt es nur bei einem Anbieter). Dazu „Falscher Sender“ je
 * Wettbewerb. Bleibt über Neustarts (kleiner Speicher, alte Einträge fallen nach [MAX_AGE_MS] weg).
 */
class ProofStore(private val store: StreamMemory.Store? = null, private val clock: () -> Long = System::currentTimeMillis) {

    /** Ein Eintrag der Diagnose: wann, welches Spiel, welcher Sender, mit welchem Ergebnis und woher. */
    data class Record(val atMs: Long, val eventId: String, val channel: String, val verdict: Verdict, val by: String, val key: String = "")

    private val verdicts = ConcurrentHashMap<String, Pair<Verdict, Long>>()
    private val blocked = ConcurrentHashMap<String, Long>()
    private val history = ArrayDeque<Record>()
    private val _found = MutableSharedFlow<Record>(extraBufferCapacity = FOUND_BUFFER)

    /** Neue Beweise (nur „bewiesen“) – für den Hinweis „Spiel gefunden auf …“ (Plan „Spiel-Finder“ L). */
    val found: SharedFlow<Record> = _found

    init {
        val now = clock()
        val expired = ArrayList<String>()
        store?.all()?.forEach { (key, value) ->
            val at = value.substringAfter('|').toLongOrNull() ?: return@forEach
            when {
                key.startsWith(BLOCK) -> blocked[key.removePrefix(BLOCK)] = at
                // Abgelaufene Beweise nicht wieder aufleben lassen (sonst galten sie bis zum nächsten neuen Ergebnis weiter).
                key.startsWith(PROOF) && now - at > MAX_AGE_MS -> expired += key
                key.startsWith(PROOF) -> Verdict.entries.firstOrNull { it.name == value.substringBefore('|') }
                    ?.let { verdicts[key.removePrefix(PROOF)] = it to at }
            }
        }
        if (expired.isNotEmpty()) store?.remove(expired)
    }

    /** Ergebnis für [eventId] auf einem Sender ([keys]: Senderschlüssel und/oder Kanal-ID), das stärkste zuerst. */
    fun verdictFor(eventId: String, keys: Collection<String>): Verdict? {
        val found = keys.mapNotNull { verdicts[proofKey(eventId, it)]?.first }
        return when {
            Verdict.PROVEN in found -> Verdict.PROVEN
            Verdict.PLACEHOLDER in found -> Verdict.PLACEHOLDER
            Verdict.OTHER in found -> Verdict.OTHER
            else -> null
        }
    }

    /** Kanal-IDs, die für [eventId] bewiesen sind (auch Sender außerhalb der bisherigen Kandidaten, Plan D). */
    fun provenChannelIds(eventId: String): List<String> = verdicts.entries
        .filter { (key, value) -> key.startsWith("$eventId$SEP") && value.first == Verdict.PROVEN }
        .map { it.key.substringAfter(SEP) }.filter { CHANNEL_ID.matches(it) }

    /** Ergebnisse mit Senderschlüssel (keine Kanal-IDs) – zum Teilen mit anderen Geräten (Plan O). */
    fun shareable(): List<Triple<String, String, Verdict>> = verdicts.entries
        .map { (key, value) -> Triple(key.substringBefore(SEP), key.substringAfter(SEP), value.first) }
        .filter { (_, key, _) -> !CHANNEL_ID.matches(key) }

    fun hasProof(eventId: String): Boolean = verdicts.any { (key, value) -> key.startsWith("$eventId$SEP") && value.first == Verdict.PROVEN }

    /** Ergebnis merken. UNKNOWN wird nicht gespeichert (nächste Prüfung versucht es erneut). [by]: „Bild“, „Zuschauen“, „Mosaik“ … */
    fun record(eventId: String, key: String, channelName: String, verdict: Verdict, by: String) {
        if (verdict == Verdict.UNKNOWN) return
        val now = clock()
        verdicts[proofKey(eventId, key)] = verdict to now
        store?.put(PROOF + proofKey(eventId, key), "${verdict.name}|$now")
        val record = Record(now, eventId, channelName, verdict, by, key)
        val isNew = synchronized(history) {
            val repeat = history.any { it.eventId == eventId && it.key == key && it.verdict == verdict }
            if (!repeat) history.addFirst(record)
            while (history.size > MAX_HISTORY) history.removeLast()
            !repeat
        }
        // Hinweis nur einmal je Spiel und Kanal (mit Kanal-ID – damit lässt sich der Sender öffnen).
        if (verdict == Verdict.PROVEN && isNew && CHANNEL_ID.matches(key)) _found.tryEmit(record)
        prune(now)
    }

    /** „Falscher Sender“: für diesen Wettbewerb nicht mehr zeigen. */
    fun block(league: String, key: String) {
        val now = clock()
        blocked["$league$SEP$key"] = now
        store?.put("$BLOCK$league$SEP$key", "x|$now")
    }

    fun isBlocked(league: String, keys: Collection<String>): Boolean = keys.any { blocked.containsKey("$league$SEP$it") }

    /** Die letzten Prüfungen (neueste zuerst) für die Diagnose. */
    fun recent(): List<Record> = synchronized(history) { history.toList() }

    private fun prune(now: Long) {
        val old = verdicts.filterValues { now - it.second > MAX_AGE_MS }.keys
        if (old.isEmpty()) return
        old.forEach(verdicts::remove)
        store?.remove(old.map { PROOF + it })
    }

    private fun proofKey(eventId: String, key: String) = "$eventId$SEP$key"

    companion object {
        private const val SEP = "\u001F"

        /** Kanal-IDs sehen aus wie „12:L:345“ – Senderschlüssel („dazn1“) nicht. */
        private val CHANNEL_ID = Regex("""\d+:L:.+""")
        private const val PROOF = "p:"
        private const val BLOCK = "b:"
        private const val MAX_HISTORY = 20
        private const val FOUND_BUFFER = 8
        private const val MAX_AGE_MS = 24 * 3_600_000L
    }
}
