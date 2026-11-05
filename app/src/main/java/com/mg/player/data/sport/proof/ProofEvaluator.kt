package com.mg.player.data.sport.proof

import java.text.Normalizer
import java.util.Locale
import kotlin.math.abs

/**
 * Bildbeweis (Plan „Spiel-Finder“ C): Aus dem Text, den die Texterkennung in einem Sender-Bild liest, und dem Live-Stand
 * des Spiels wird entschieden, ob der Sender das Spiel zeigt. Rein (ohne Android), damit voll testbar.
 *
 * Bewiesen ist ein Sender nur, wenn die Spielstand-Einblendung zum Spiel passt: ein Teamkürzel bzw. -name beider Teams
 * (oder eines Teams plus der richtige Stand) – und, falls eine Spielzeit lesbar ist, höchstens [MAX_MINUTE_DIFF] Minuten
 * Abweichung (sonst eine Wiederholung). Ohne Vorlagen je Sender: gesucht wird im ganzen Text.
 */
object ProofEvaluator {

    enum class Verdict { PROVEN, PLACEHOLDER, OTHER, UNKNOWN }

    /** Was über das Spiel bekannt ist: Teamnamen bzw. Kämpfer, Live-Stand („2:1“) und Spielminute. */
    data class Game(val home: String?, val away: String?, val goals: Pair<Int, Int>?, val minute: Int?)

    private const val MAX_MINUTE_DIFF = 3
    private val PLACEHOLDER = Regex("""\b(no|kein|keine)\s+(event|events|sendung|signal)\b|\bcoming\s+soon\b|\bstream\s+offline\b""")
    private val SCORE = Regex("""(?<!\d)(\d{1,2})\s*[-:–]\s*(\d{1,2})(?!\d)""")
    private val CLOCK = Regex("""(?<!\d)(\d{1,3}):([0-5]\d)(?!\d)""")
    private val MINUTE_MARK = Regex("""(?<!\d)(\d{1,3})\s*'""")

    fun evaluate(texts: List<String>, game: Game, staticPicture: Boolean): Verdict {
        val text = fold(texts.joinToString("\n"))
        if (staticPicture || PLACEHOLDER.containsMatchIn(text)) return Verdict.PLACEHOLDER
        val homeHit = game.home?.let { teamSeen(text, it) } == true
        val awayHit = game.away?.let { teamSeen(text, it) } == true
        val scoreHit = game.goals?.let { scoreSeen(text, it) } == true
        val minuteOk = minuteMatches(text, game.minute)
        val foreignScoreBug = SCORE.containsMatchIn(text) && looksLikeScoreBug(text)
        return when {
            minuteOk == false -> Verdict.OTHER
            homeHit && awayHit -> Verdict.PROVEN
            (homeHit || awayHit) && scoreHit -> Verdict.PROVEN
            // Eine fremde Paarung mit Stand im Bild ist ein anderes Spiel.
            !homeHit && !awayHit && foreignScoreBug -> Verdict.OTHER
            else -> Verdict.UNKNOWN
        }
    }

    /** Erkennt ein Team im Text: Kürzel aus den Anfangsbuchstaben, die ersten 3 Buchstaben eines Wortes oder ein Wort ab 4 Zeichen. */
    internal fun teamSeen(text: String, team: String): Boolean {
        val words = WORD.findAll(text).map { it.value }.toSet()
        return candidates(team).any { it in words }
    }

    /** Mögliche Schreibweisen eines Teams in einer Einblendung („Juventus“ → juv, juventus; „AZ Alkmaar“ → az, alk, aza). */
    internal fun candidates(team: String): Set<String> {
        val parts = WORD.findAll(fold(team)).map { it.value }.filter { it !in GENERIC }.toList()
        val result = HashSet<String>()
        parts.forEach { p ->
            if (p.length >= 2) result += p.take(3)
            if (p.length >= 4) result += p
            if (p.length in 2..3) result += p
        }
        if (parts.size >= 2) result += parts.joinToString("") { it.take(1) }
        if (parts.size >= 2) result += (parts[0].take(1) + parts[1].take(2))
        return result.filter { it.length >= 2 }.toSet()
    }

    private fun scoreSeen(text: String, goals: Pair<Int, Int>): Boolean = SCORE.findAll(text).any { m ->
        val a = m.groupValues[1].toInt()
        val b = m.groupValues[2].toInt()
        (a to b) == goals || (b to a) == goals
    }

    /** null = keine Spielzeit lesbar; sonst, ob sie zur Live-Minute passt. */
    private fun minuteMatches(text: String, minute: Int?): Boolean? {
        if (minute == null) return null
        val seen = (CLOCK.findAll(text).map { it.groupValues[1].toInt() } + MINUTE_MARK.findAll(text).map { it.groupValues[1].toInt() })
            .filter { it in 0..MAX_PLAY_MINUTE }.toList()
        if (seen.isEmpty()) return null
        return seen.any { abs(it - minute) <= MAX_MINUTE_DIFF }
    }

    /** Ein Stand allein (z. B. Uhrzeit „20:45“ im Programmhinweis) ist noch keine Einblendung: kurze Wörter daneben nötig. */
    private fun looksLikeScoreBug(text: String): Boolean = text.lines().any { line ->
        SCORE.containsMatchIn(line) && WORD.findAll(line).count { it.value.length in 2..4 } >= 2
    }

    private const val MAX_PLAY_MINUTE = 130
    private val WORD = Regex("""[a-z]{2,}""")
    private val GENERIC = setOf("fc", "sc", "sv", "ac", "cf", "fk", "sk", "club", "de", "la", "the", "of", "1")

    private fun fold(s: String): String =
        Normalizer.normalize(s, Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "").replace('ı', 'i').lowercase(Locale.ROOT)
}

/** Was ein Blick in einen Sender ergeben hat: erkannte Textzeilen und ob das Bild stand. */
data class ProofLook(val texts: List<String>, val staticPicture: Boolean)
