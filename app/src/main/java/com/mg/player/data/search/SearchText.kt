package com.mg.player.data.search

import com.mg.player.core.util.TextFold
import java.text.Normalizer
import java.util.Locale

/**
 * Wie Titel im Suchindex abgelegt und Eingaben verglichen werden. Beides läuft durch dieselbe Faltung,
 * damit es egal ist, wie ein Anbieter schreibt oder wie man sucht:
 *
 * - Zierschriften und Sonderformen werden zu normalen Buchstaben: "𝗭𝗗𝗙", "ＺＤＦ", "ᵁᴴᴰ" -> "zdf", "uhd".
 * - Akzente fallen weg ("Café" -> "cafe"), deutsche Umlaute zusätzlich als ae/oe/ue ("kaenguru").
 * - Satzzeichen trennen Wörter ("n-tv" -> "n", "tv"); zusätzlich gibt es die zusammengeschriebene Form
 *   ("ntv", "sat1"). So finden auch Spracheingaben wie "Z D F" den Sender.
 */
object SearchText {

    private val combiningMarks = Regex("\\p{Mn}+")
    private val separators = Regex("[^\\p{L}\\p{Nd}]+")
    private val special = mapOf(
        'ß' to "ss",
        'æ' to "ae",
        'œ' to "oe",
        'ø' to "o",
        'ł' to "l",
        'ı' to "i",
        'đ' to "d",
        'þ' to "th",
    )
    private val germanUmlauts = mapOf('ä' to "ae", 'ö' to "oe", 'ü' to "ue")

    /** Höchstens so viele Wörter aus einer Eingabe – längere Eingaben werden abgeschnitten. */
    const val MAX_QUERY_WORDS = 6

    /** Text -> kleingeschriebene Wörter ohne Akzente und Zierschrift. */
    fun words(text: String): List<String> = fold(text).split(separators).filter { it.isNotEmpty() }

    /**
     * Inhalt der Index-Spalte für einen Titel: alle Wörter (gefaltet und mit Umlaut-Schreibweise) plus die
     * zusammengeschriebene Form. Beispiel "DE: Die Känguru-Chroniken" ->
     * "de die kanguru chroniken kaenguru dediekanguruchroniken dediekaenguruchroniken".
     */
    fun indexTerms(name: String): String {
        val folded = words(name)
        val german = germanWords(name)
        val all = LinkedHashSet<String>(folded.size * 2 + 2)
        all += folded
        all += german
        all += folded.joinToString("")
        all += german.joinToString("")
        all.remove("")
        return all.joinToString(" ")
    }

    /** Eingabe -> FTS-Abfrage: jedes Wort als Präfix ("zdf hd" -> "zdf* hd*"). null, wenn nichts übrig bleibt. */
    fun ftsQuery(input: String): String? {
        val tokens = words(input).take(MAX_QUERY_WORDS)
        if (tokens.isEmpty()) return null
        return tokens.joinToString(" ") { "$it*" }
    }

    /** Eingabe zusammengeschrieben ("Z D F" -> "zdf", "n-tv" -> "ntv") – für die Suche mitten im Wort. */
    fun compact(input: String): String = words(input).take(MAX_QUERY_WORDS).joinToString("")

    /** Für die Sortierung: Titel und Eingabe in derselben Form vergleichen. */
    fun comparable(text: String): String = words(text).joinToString(" ")

    private fun fold(text: String): String {
        val lower = TextFold.plain(text).lowercase(Locale.ROOT)
        val sb = StringBuilder(lower.length)
        for (ch in lower) sb.append(special[ch] ?: ch)
        // NFKD zerlegt Zierschriften und Akzente; die Akzente (Mn) fallen danach weg.
        val decomposed = Normalizer.normalize(sb, Normalizer.Form.NFKD)
        return combiningMarks.replace(decomposed, "").lowercase(Locale.ROOT)
    }

    /** Wörter mit deutscher Umlaut-Schreibweise – nur wenn der Titel überhaupt Umlaute enthält. */
    private fun germanWords(text: String): List<String> {
        val lower = Normalizer.normalize(TextFold.plain(text).lowercase(Locale.ROOT), Normalizer.Form.NFC)
        if (lower.none { it in germanUmlauts }) return emptyList()
        val sb = StringBuilder(lower.length + 4)
        for (ch in lower) sb.append(germanUmlauts[ch] ?: ch)
        return words(sb.toString())
    }
}
