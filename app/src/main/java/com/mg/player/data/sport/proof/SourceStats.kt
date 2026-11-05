package com.mg.player.data.sport.proof

import com.mg.player.core.util.StreamMemory
import com.mg.player.domain.model.MatchSource
import java.util.Calendar
import java.util.TimeZone
import java.util.concurrent.ConcurrentHashMap

/**
 * Plan „Spiel-Finder“ F und G – aus Beweisen lernen:
 * - **Trefferquote je Quelle** (getrennt nach Playlist und Wettbewerb): Hatte eine Quelle für ein später bewiesenes Spiel
 *   den richtigen Sender genannt? Eine Quelle mit 0 von [MIN_SAMPLES] Treffern zählt nicht mehr ([isUseless]); angezeigt
 *   wird die Quote als „Vorhersage 87 %“.
 * - **Zeitfenster-Gedächtnis:** Bewiesene Sender je Wettbewerb, Wochentag und Anstoßzeit („Bundesliga · Fr 20:30 → Sky“).
 *   Ab [SLOT_MIN] Beweisen wird daraus eine Vorhersage für die nächsten Spiele im selben Zeitfenster.
 * Jedes Spiel zählt nur einmal. Alles bleibt auf dem Gerät.
 */
class SourceStats(private val store: StreamMemory.Store? = null, clock: () -> Long = System::currentTimeMillis) {

    private val counts = ConcurrentHashMap<String, Pair<Int, Int>>()
    private val slots = ConcurrentHashMap<String, Int>()
    private val learned = ConcurrentHashMap.newKeySet<String>()

    init {
        val stale = ArrayList<String>()
        store?.all()?.forEach { (key, value) ->
            when {
                key.startsWith(RATE) -> value.split('/').takeIf { it.size == 2 }
                    ?.let { (hits, total) -> counts[key.removePrefix(RATE)] = (hits.toIntOrNull() ?: 0) to (total.toIntOrNull() ?: 0) }
                key.startsWith(SLOT) -> slots[key.removePrefix(SLOT)] = value.toIntOrNull() ?: 0
                // „Schon gezählt“ nur, solange der Beweis des Spiels noch gilt – sonst wächst der Speicher ohne Ende.
                key.startsWith(DONE) -> if ((value.toLongOrNull() ?: 0L) > clock() - DONE_KEEP_MS) learned += key.removePrefix(DONE) else stale += key
            }
        }
        if (stale.isNotEmpty()) store?.remove(stale)
    }

    /** Eine Vorhersage einer Quelle für ein bewiesenes Spiel: welche Quelle, über welche Playlist, und ob sie stimmte. */
    data class Guess(val source: MatchSource, val playlistId: Long, val hit: Boolean)

    /** Bewiesenes Spiel auswerten (einmal je Spiel): Trefferquoten und Zeitfenster aktualisieren. */
    fun learn(eventId: String, league: String, startMs: Long, provenStation: String?, guesses: List<Guess>) {
        if (!learned.add(eventId)) return
        store?.put(DONE + eventId, startMs.toString())
        guesses.forEach { g ->
            val key = rateKey(g.source, league, g.playlistId)
            val (hits, total) = counts[key] ?: (0 to 0)
            val next = (hits + if (g.hit) 1 else 0) to (total + 1)
            counts[key] = next
            store?.put(RATE + key, "${next.first}/${next.second}")
        }
        if (provenStation == null) return
        val slot = slotKey(league, startMs) + SEP + provenStation
        val n = (slots[slot] ?: 0) + 1
        slots[slot] = n
        store?.put(SLOT + slot, n.toString())
    }

    /** Trefferquote in Prozent, null bei zu wenigen Spielen. */
    fun ratePercent(source: MatchSource, league: String, playlistId: Long): Int? {
        val (hits, total) = counts[rateKey(source, league, playlistId)] ?: return null
        return if (total < MIN_SAMPLES) null else hits * PERCENT / total
    }

    /** Trefferquote einer Quelle über alle Wettbewerbe und Playlists (Einstellungen), null bei zu wenigen Spielen. */
    fun overallPercent(source: MatchSource): Int? {
        val prefix = source.name + SEP
        val (hits, total) = counts.filterKeys { it.startsWith(prefix) }.values
            .fold(0 to 0) { (h, t), (hits, total) -> (h + hits) to (t + total) }
        return if (total < MIN_SAMPLES) null else hits * PERCENT / total
    }

    /** Quelle hat für diesen Wettbewerb und diese Playlist nachweislich nie gestimmt (0 von mindestens [MIN_SAMPLES]). */
    fun isUseless(source: MatchSource, league: String, playlistId: Long): Boolean {
        val (hits, total) = counts[rateKey(source, league, playlistId)] ?: return false
        return total >= MIN_SAMPLES && hits == 0
    }

    /** Senderschlüssel, die im Zeitfenster dieses Spiels mindestens [SLOT_MIN]-mal bewiesen wurden. */
    fun slotStations(league: String, startMs: Long): List<String> {
        val prefix = slotKey(league, startMs) + SEP
        return slots.filter { (key, n) -> key.startsWith(prefix) && n >= SLOT_MIN }.entries
            .sortedByDescending { it.value }.map { it.key.removePrefix(prefix) }
    }

    private fun rateKey(source: MatchSource, league: String, playlistId: Long) = "${source.name}$SEP$league$SEP$playlistId"

    /** „Wettbewerb · Wochentag · Uhrzeit“ in deutscher Zeit (Anstoßzeiten sind dort fest). */
    private fun slotKey(league: String, startMs: Long): String {
        val c = Calendar.getInstance(TimeZone.getTimeZone("Europe/Berlin")).apply { timeInMillis = startMs }
        return "$league$SEP${c.get(Calendar.DAY_OF_WEEK)}$SEP${c.get(Calendar.HOUR_OF_DAY)}:${c.get(Calendar.MINUTE)}"
    }

    companion object {
        private const val SEP = "\u001F"
        private const val RATE = "r:"
        private const val SLOT = "s:"
        private const val DONE = "d:"
        const val MIN_SAMPLES = 5
        const val SLOT_MIN = 2
        private const val PERCENT = 100
        private const val DONE_KEEP_MS = 7 * 24 * 3_600_000L
    }
}
