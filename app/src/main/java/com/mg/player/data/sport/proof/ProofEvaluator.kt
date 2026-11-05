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
        if (minuteMatches(text, game.minute) == false) return Verdict.OTHER
        return judge(teamHits(text, game), game.goals?.let { scoreSeen(text, it) } == true, SCORE.containsMatchIn(text) && looksLikeScoreBug(text))
    }

    /** Wie viele der beiden Teams im Text vorkommen (0–2). */
    private fun teamHits(text: String, game: Game): Int =
        listOfNotNull(game.home, game.away).count { teamSeen(text, it) }

    private fun judge(teams: Int, scoreHit: Boolean, foreignScoreBug: Boolean): Verdict = when {
        teams >= 2 -> Verdict.PROVEN
        teams == 1 && scoreHit -> Verdict.PROVEN
        // Eine fremde Paarung mit Stand im Bild ist ein anderes Spiel.
        teams == 0 && foreignScoreBug -> Verdict.OTHER
        else -> Verdict.UNKNOWN
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
            result += p.take(ABBREVIATION)
            if (p.length >= FULL_WORD_MIN || p.length <= ABBREVIATION) result += p
        }
        if (parts.size >= 2) {
            result += parts.joinToString("") { it.take(1) }
            result += parts[0].take(1) + parts[1].take(2)
        }
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
        SCORE.containsMatchIn(line) && WORD.findAll(line).count { it.value.length in SHORT_WORD } >= 2
    }

    /**
     * Logo-Prüfung (Plan „Spiel-Finder“ E): Zeigt das Bild in mehreren kurzen Textzeilen (Senderlogo, Bauchbinde) eine
     * andere Senderfamilie als der Name des Senders – und nie die eigene –, läuft dort ein anderes Programm.
     * Unbekannte Familie des Senders → keine Aussage.
     */
    fun foreignLogo(texts: List<String>, channelName: String, familyOf: (String) -> String?): Boolean {
        val own = familyOf(channelName) ?: return false
        if (texts.any { familyOf(it) == own }) return false
        return texts.count { it.trim().split(BLANK).size <= LOGO_WORDS && familyOf(it) != null } >= LOGO_MIN
    }

    private const val LOGO_WORDS = 3
    private const val LOGO_MIN = 2
    private val BLANK = Regex("""\s+""")
    private const val MAX_PLAY_MINUTE = 130
    private const val ABBREVIATION = 3
    private const val FULL_WORD_MIN = 4
    private val SHORT_WORD = 2..4
    private val WORD = Regex("""[a-z]{2,}""")
    private val GENERIC = setOf("fc", "sc", "sv", "ac", "cf", "fk", "sk", "club", "de", "la", "the", "of", "1")

    private fun fold(s: String): String =
        Normalizer.normalize(s, Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "").replace('ı', 'i').lowercase(Locale.ROOT)
}

/** Was ein Blick in einen Sender ergeben hat: erkannte Textzeilen und ob das Bild stand. */
data class ProofLook(val texts: List<String>, val staticPicture: Boolean)
