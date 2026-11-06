package com.mg.player.data.sport.proof

import com.mg.player.core.util.StreamMemory
import com.mg.player.domain.model.MatchSource
import java.util.Calendar
import java.util.TimeZone
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.sqrt

/**
 * Plan „Spiel-Finder“ F und G – aus Beweisen lernen:
 * - **Trefferquote je Quelle** (getrennt nach Playlist und Wettbewerb): Hatte eine Quelle für ein später bewiesenes Spiel
 *   den richtigen Sender genannt? Angezeigt wird die Quote als „Vorhersage 87 % (4 von 5)“ – mit der Stichprobe, weil
 *   80 % aus 4 von 5 Spielen alles zwischen 38 % und 96 % bedeuten kann. Sortiert wird nach [rateScore] (vorsichtige
 *   Untergrenze), nicht nach der rohen Quote: Sonst steht 1 von 1 über 17 von 20.
 * - **Schwache Quellen** werden übersprungen ([shouldSkip]), aber nicht für immer: Jedes achte Spiel fragt sie trotzdem
 *   ([EXPLORE_EVERY]). Ohne diese Erkundung liefert eine ausgeblendete Quelle nie wieder Daten und könnte sich auch
 *   dann nicht erholen, wenn der Anbieter sein Programm umstellt.
 * - **Zeitfenster-Gedächtnis:** Bewiesene Sender je Wettbewerb, Wochentag und Anstoßzeit („Bundesliga · Fr 20:30 → Sky“).
 *   Ab [SLOT_MIN] Beweisen wird daraus eine Vorhersage für die nächsten Spiele im selben Zeitfenster. Die Anstoßzeit
 *   liegt auf einem [SLOT_GRID_MIN]-Minuten-Raster (ein TV-Pick verschiebt 20:30 auf 20:45) und zählt in der Zeitzone
 *   des Wettbewerbs (15:00 Samstag ist Premier-League-Zeit in London, nicht in Berlin).
 * Jedes Spiel zählt nur einmal. Alles bleibt auf dem Gerät.
 */
class SourceStats(private val store: StreamMemory.Store? = null, clock: () -> Long = System::currentTimeMillis) {

    private val counts = ConcurrentHashMap<String, Pair<Int, Int>>()
    private val slots = ConcurrentHashMap<String, Int>()
    private val learned = ConcurrentHashMap.newKeySet<String>()

    /** Schon als Fehlschlag gewertete „Falscher Sender“-Meldungen (siehe [learnMiss]). */
    private val missed = ConcurrentHashMap.newKeySet<String>()

    init {
        val stale = ArrayList<String>()
        store?.all()?.forEach { (key, value) ->
            when {
                key.startsWith(RATE) -> value.split('/').takeIf { it.size == 2 }
                    ?.let { (hits, total) -> counts[key.removePrefix(RATE)] = (hits.toIntOrNull() ?: 0) to (total.toIntOrNull() ?: 0) }
                key.startsWith(SLOT) -> slots[key.removePrefix(SLOT)] = value.toIntOrNull() ?: 0
                // „Schon gezählt“ nur, solange der Beweis des Spiels noch gilt – sonst wächst der Speicher ohne Ende.
                key.startsWith(DONE) -> if ((value.toLongOrNull() ?: 0L) > clock() - DONE_KEEP_MS) learned += key.removePrefix(DONE) else stale += key
                key.startsWith(WRONG) -> if ((value.toLongOrNull() ?: 0L) > clock() - DONE_KEEP_MS) missed += key.removePrefix(WRONG) else stale += key
            }
        }
        if (stale.isNotEmpty()) store?.remove(stale)
    }

    /** Eine Vorhersage einer Quelle für ein bewiesenes Spiel: welche Quelle, über welche Playlist, und ob sie stimmte. */
    data class Guess(val source: MatchSource, val playlistId: Long, val hit: Boolean)

    /** Bewiesenes Spiel auswerten (einmal je Spiel): Trefferquoten und Zeitfenster aktualisieren. */
    @Synchronized
    fun learn(eventId: String, league: String, startMs: Long, provenStation: String?, guesses: List<Guess>) {
        if (!learned.add(eventId)) return
        store?.put(DONE + eventId, startMs.toString())
        guesses.forEach { g ->
            // Hat eine Korrektur dieses Spiel für diese Quelle schon gewertet, zählt es nicht zweimal
            // (die Quote bliebe sonst nicht „ein Spiel, eine Wertung“).
            if (missKey(eventId, g.source, g.playlistId) in missed) return@forEach
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

    /**
     * „Falscher Sender“ (Plan H): Die Quelle, die diesen Sender genannt hat, hat sich geirrt – als Fehlschlag werten.
     * Ohne das lernt das System aus Korrekturen nichts: [learn] läuft nur, wenn ein Sender **bewiesen** wird, und wer
     * einen falschen Sender wegklickt, nennt dabei keinen richtigen.
     *
     * Zählt je Spiel und Quelle einmal und benutzt eine eigene Marke ([WRONG]): Wird das Spiel später doch noch
     * bewiesen, darf [learn] trotzdem noch werten.
     */
    @Synchronized
    fun learnMiss(eventId: String, league: String, source: MatchSource, playlistId: Long, atMs: Long) {
        // Ist das Spiel schon über einen Beweis gewertet, bleibt es dabei: der Beweis ist die bessere Auskunft.
        if (eventId in learned) return
        val marker = missKey(eventId, source, playlistId)
        if (!missed.add(marker)) return
        store?.put(WRONG + marker, atMs.toString())
        val key = rateKey(source, league, playlistId)
        val (hits, total) = counts[key] ?: (0 to 0)
        val next = hits to (total + 1)
        counts[key] = next
        store?.put(RATE + key, "${next.first}/${next.second}")
    }

    /** Trefferquote in Prozent, null bei zu wenigen Spielen. */
    fun ratePercent(source: MatchSource, league: String, playlistId: Long): Int? {
        val (hits, total) = counts[rateKey(source, league, playlistId)] ?: return null
        return if (total < MIN_SAMPLES) null else hits * PERCENT / total
    }

    /** Trefferquote einer Quelle über alle Wettbewerbe und Playlists (Einstellungen), null bei zu wenigen Spielen. */
    fun overallPercent(source: MatchSource): Int? {
        val (hits, total) = overall(source)
        return if (total < MIN_SAMPLES) null else hits * PERCENT / total
    }

    /** Stichprobe hinter der Quote: (Treffer, gewertete Spiele) – für die Anzeige „4 von 5“. Null ohne jede Wertung. */
    fun sample(source: MatchSource, league: String, playlistId: Long): Pair<Int, Int>? =
        counts[rateKey(source, league, playlistId)]

    /** Stichprobe über alle Wettbewerbe und Playlists; null, solange nichts gewertet wurde. */
    fun overallSample(source: MatchSource): Pair<Int, Int>? = overall(source).takeIf { it.second > 0 }

    /**
     * Vorsichtige Quote zum Sortieren: Untergrenze des 95-%-Intervalls (Wilson). 1 von 1 ergibt 0,21 und steht damit
     * unter 17 von 20 (0,65) – die rohe Quote (100 % gegen 85 %) hätte es umgekehrt sortiert. Ohne Wertung 0.
     */
    fun rateScore(source: MatchSource, league: String, playlistId: Long): Double {
        val (hits, total) = counts[rateKey(source, league, playlistId)] ?: return 0.0
        return wilsonLower(hits, total)
    }

    /**
     * Diese Quelle für dieses Spiel überspringen: Sie hat in mindestens [MIN_SAMPLES] Spielen nie gestimmt oder ihre
     * Obergrenze liegt unter [WEAK_MAX] – **und** dieses Spiel ist kein Erkundungsspiel. Jedes [EXPLORE_EVERY]-te
     * Spiel wird sie trotzdem gefragt, sonst kann sie sich nach einer Programmumstellung nie erholen.
     * Die Entscheidung hängt nur an [eventId], Quelle und Playlist: dasselbe Spiel bekommt immer dieselbe Antwort,
     * damit die Zuordnung nicht zwischen zwei Aufrufen flackert.
     */
    fun shouldSkip(source: MatchSource, league: String, playlistId: Long, eventId: String): Boolean {
        val (hits, total) = counts[rateKey(source, league, playlistId)] ?: return false
        if (total < MIN_SAMPLES) return false
        val hopeless = hits == 0 || wilsonUpper(hits, total) < WEAK_MAX
        return hopeless && !explores(eventId, source, playlistId)
    }

    /** Erkundungsspiel (siehe [shouldSkip]): stabil aus Spiel, Quelle und Playlist gewürfelt. */
    internal fun explores(eventId: String, source: MatchSource, playlistId: Long): Boolean =
        Math.floorMod("$eventId$SEP${source.name}$SEP$playlistId".hashCode(), EXPLORE_EVERY) == 0

    private fun overall(source: MatchSource): Pair<Int, Int> {
        val prefix = source.name + SEP
        return counts.filterKeys { it.startsWith(prefix) }.values
            .fold(0 to 0) { (h, t), (hits, total) -> (h + hits) to (t + total) }
    }

    /** Senderschlüssel, die im Zeitfenster dieses Spiels mindestens [SLOT_MIN]-mal bewiesen wurden. */
    fun slotStations(league: String, startMs: Long): List<String> {
        val prefix = slotKey(league, startMs) + SEP
        return slots.filter { (key, n) -> key.startsWith(prefix) && n >= SLOT_MIN }.entries
            .sortedByDescending { it.value }.map { it.key.removePrefix(prefix) }
    }

    private fun rateKey(source: MatchSource, league: String, playlistId: Long) = "${source.name}$SEP$league$SEP$playlistId"

    private fun missKey(eventId: String, source: MatchSource, playlistId: Long) = "$eventId$SEP${source.name}$SEP$playlistId"

    /**
     * „Wettbewerb · Wochentag · Uhrzeit“ in der Zeitzone des Wettbewerbs (dort liegen die Anstoßzeiten fest), die
     * Minute auf dem [SLOT_GRID_MIN]-Raster: Ein TV-Pick, der 20:30 auf 20:45 verschiebt, bleibt dasselbe Zeitfenster.
     */
    private fun slotKey(league: String, startMs: Long): String {
        val c = Calendar.getInstance(zoneOf(league)).apply { timeInMillis = startMs }
        val minute = c.get(Calendar.MINUTE) / SLOT_GRID_MIN * SLOT_GRID_MIN
        return "$league$SEP${c.get(Calendar.DAY_OF_WEEK)}$SEP${c.get(Calendar.HOUR_OF_DAY)}:$minute"
    }

    companion object {
        private const val SEP = "\u001F"
        private const val RATE = "r:"
        private const val SLOT = "s:"
        private const val DONE = "d:"

        /** Marke „Fehlschlag aus einer Korrektur schon gewertet“ (siehe [learnMiss]). */
        private const val WRONG = "w:"
        const val MIN_SAMPLES = 5
        const val SLOT_MIN = 2

        /** Anstoßzeiten auf Viertelstunden runden (TV-Picks verschieben 20:30 auf 20:45). */
        const val SLOT_GRID_MIN = 15

        /** Jedes so vielte Spiel fragt auch eine schwache Quelle (Erkundung, siehe [shouldSkip]). */
        const val EXPLORE_EVERY = 8

        /** Bis zu dieser Obergrenze des 95-%-Intervalls gilt eine Quelle als schwach (1 von 30 ⇒ 0,17). */
        const val WEAK_MAX = 0.2

        private const val PERCENT = 100
        private const val DONE_KEEP_MS = 7 * 24 * 3_600_000L

        /** 95-%-Intervall (z = 1,96). */
        private const val Z = 1.96

        /** Feste Nenner der Wilson-Formel: z²/(2n) im Mittelpunkt, z²/(4n²) unter der Wurzel. */
        private const val HALF_CORRECTION = 2.0
        private const val QUARTER_CORRECTION = 4.0

        /** Zeitzonen der Wettbewerbe nach dem Länderkürzel in der Kennung („espn:soccer/eng.1“ → London). */
        private val ZONES = mapOf(
            "eng" to "Europe/London", "sco" to "Europe/London", "esp" to "Europe/Madrid", "ita" to "Europe/Rome",
            "fra" to "Europe/Paris", "ned" to "Europe/Amsterdam", "por" to "Europe/Lisbon", "tur" to "Europe/Istanbul",
            "usa" to "America/New_York", "bra" to "America/Sao_Paulo", "mex" to "America/Mexico_City",
            "ger" to BERLIN, "aut" to "Europe/Vienna", "sui" to "Europe/Zurich", "bel" to "Europe/Brussels",
            "pol" to "Europe/Warsaw",
        )

        /**
         * Zeitzone eines Wettbewerbs; alles Unbekannte (auch „uefa.champions“, dessen Anstoßzeiten nach Mitteleuropa
         * angesetzt werden) bleibt bei Berlin.
         */
        internal fun zoneOf(league: String): TimeZone {
            val country = league.substringAfterLast('/').substringBefore('.').lowercase()
            return TimeZone.getTimeZone(ZONES[country] ?: BERLIN)
        }

        /** Untergrenze des Wilson-Intervalls: vorsichtige Quote, die kleine Stichproben nach unten zieht. */
        internal fun wilsonLower(hits: Int, total: Int): Double = wilson(hits, total, -1)

        /** Obergrenze des Wilson-Intervalls: 0 von 5 ergibt 0,43 – „nie gestimmt“ heißt nach 5 Spielen noch wenig. */
        internal fun wilsonUpper(hits: Int, total: Int): Double = wilson(hits, total, +1)

        private fun wilson(hits: Int, total: Int, side: Int): Double {
            if (total <= 0) return if (side < 0) 0.0 else 1.0
            val n = total.toDouble()
            val p = hits / n
            val z2 = Z * Z
            val denominator = 1 + z2 / n
            val center = (p + z2 / (HALF_CORRECTION * n)) / denominator
            val margin = Z / denominator * sqrt(p * (1 - p) / n + z2 / (QUARTER_CORRECTION * n * n))
            return (center + side * margin).coerceIn(0.0, 1.0)
        }

        private const val BERLIN = "Europe/Berlin"
    }
}
