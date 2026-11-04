package com.mg.player.data.importer

/**
 * Wann das EPG von selbst neu geladen wird (reine Logik, damit sie ohne Gerät testbar ist).
 *
 * - Kein EPG gespeichert: höchstens alle 12 Stunden ein Versuch (ein Zugang ohne EPG lädt sonst bei jedem Start).
 * - EPG vorhanden: nach 24 Stunden ist es veraltet – die gespeicherten Sendungen reichen sonst irgendwann
 *   nicht mehr bis heute Abend. Ist die Quelle ausgefallen, wird frühestens nach 6 Stunden erneut versucht.
 */
object EpgSchedule {
    const val HOUR = 3_600_000L
    const val RETRY_EMPTY_MS = 12 * HOUR
    const val MAX_AGE_MS = 24 * HOUR
    const val RETRY_FAILED_MS = 6 * HOUR

    /** So oft prüft die App im Hintergrund, ob das EPG veraltet ist. */
    const val CHECK_INTERVAL_MS = 6 * HOUR

    /**
     * Version des EPG-Imports. Erhöhen, wenn neue Importe andere Daten liefern (2 = 7 Tage + Sport-Untertitel):
     * Dann wird ein vorhandenes, noch frisches EPG einmalig neu geladen – sonst fehlen nach einem Update
     * bis zu 24 h die neuen Daten (z. B. findet die Sport-Sektion keine Spiele).
     */
    const val FORMAT = 2

    fun shouldLoad(hasPrograms: Boolean, lastAttemptMs: Long, lastSuccessMs: Long, nowMs: Long, attemptedFormat: Int = FORMAT): Boolean {
        val sinceAttempt = nowMs - lastAttemptMs
        if (hasPrograms && attemptedFormat < FORMAT) return true
        if (!hasPrograms) return sinceAttempt > RETRY_EMPTY_MS
        // Ältere Versionen haben keinen Erfolgszeitpunkt gespeichert: dann zählt der letzte Versuch.
        val success = if (lastSuccessMs > 0) lastSuccessMs else lastAttemptMs
        return nowMs - success > MAX_AGE_MS && sinceAttempt > RETRY_FAILED_MS
    }
}

/**
 * Bremse für die öffentlichen Fallback-Quellen: Die Dateien sind zig MB groß. Ohne Bremse würde jede
 * EPG-Aktualisierung sie erneut laden, auch wenn sie schon gestern keinen einzigen Sender gefunden haben.
 *
 * - Gleiche Sender ohne EPG wie beim letzten Mal und letzter Versuch < 24 h: überspringen.
 * - Letzter Versuch hat nichts gefunden: 7 Tage Pause (außer es fehlen jetzt andere Sender als damals).
 * - Andere Sender ohne EPG (z. B. Gruppe eingeblendet): sofort erlaubt.
 */
class FallbackEpgGate(private val store: Store) {

    interface Store {
        fun get(key: String): Long?
        fun put(key: String, value: Long)
    }

    fun allowed(playlistId: Long, unmatchedSignature: Long, nowMs: Long): Boolean {
        val last = store.get(key(playlistId, TRIED)) ?: return true
        if (store.get(key(playlistId, SIGNATURE)) != unmatchedSignature) return true
        val pause = if (store.get(key(playlistId, HITS)) == 0L) NO_MATCH_PAUSE_MS else MIN_INTERVAL_MS
        return nowMs - last > pause
    }

    /** [matched] = null: Quelle war nicht erreichbar – dann gilt nur die 24-Stunden-Pause. */
    fun record(playlistId: Long, unmatchedSignature: Long, nowMs: Long, matched: Int?) {
        store.put(key(playlistId, TRIED), nowMs)
        store.put(key(playlistId, SIGNATURE), unmatchedSignature)
        store.put(key(playlistId, HITS), matched?.toLong() ?: -1L)
    }

    private fun key(playlistId: Long, part: String) = "$playlistId:$part"

    companion object {
        const val MIN_INTERVAL_MS = 24 * EpgSchedule.HOUR
        const val NO_MATCH_PAUSE_MS = 7 * 24 * EpgSchedule.HOUR
        private const val TRIED = "tried"
        private const val SIGNATURE = "sig"
        private const val HITS = "hits"

        /** Reihenfolge-unabhängiger Fingerabdruck der Sender ohne EPG. */
        fun signature(ids: Collection<String>): Long = ids.sorted().fold(1125899906842597L) { acc, id -> 31 * acc + id.hashCode() }
    }
}
