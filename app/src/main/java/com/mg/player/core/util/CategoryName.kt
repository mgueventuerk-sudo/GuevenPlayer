package com.mg.player.core.util

import java.util.Locale

/**
 * Gruppennamen aus der Senderliste lesbar machen. Anbieter schreiben sie als Rohwert in die M3U-Datei, meist in
 * Versalien und mit Länderpräfix: `DE | VOLLPROGRAMM`, `TR | ULUSAL`, `DE | KOMÖDIE`. Daneben stehen in derselben
 * Spalte die eigenen Einträge („Alle Sender“, „Favoriten“, „Weiterschauen“) – die Liste zerfiel dadurch optisch in
 * zwei Welten, und Versalien sind auf drei Meter Entfernung schlechter zu lesen als gemischte Schreibweise.
 *
 * Aus `DE | VOLLPROGRAMM` wird `DE · Vollprogramm`. Das Länderkürzel bleibt (bei mehreren Ländern in einer Liste
 * trägt es Information), der Trennstrich weicht dem Mittelpunkt, und nur Wörter ab [MIN_FIX] Zeichen werden
 * umgeschrieben: Kürzel wie HD, 4K, DE oder UFC behalten ihre Versalien, ebenso alles in [ACRONYMS].
 *
 * Namen, die der Anbieter schon gemischt schreibt, bleiben unangetastet – es wird nie etwas kleiner geschrieben,
 * was jemand bewusst groß gesetzt hat.
 */
object CategoryName {

    private const val MIN_FIX = 4

    /** Gängige Kürzel, die auch mit vier und mehr Zeichen Versalien bleiben. */
    private val ACRONYMS = setOf(
        "IPTV", "VOD", "EPG", "HDR", "UHD", "FHD", "HEVC", "H264", "H265", "DAZN", "ORF", "SRF", "ARTE",
        "NBA", "NFL", "NHL", "MLB", "WWE", "MMA", "UFC", "ATP", "WTA", "F1", "MOTOGP", "PPV", "XXX", "VIP",
    )

    private val SEPARATOR = Regex("""\s*[|/]\s*""")
    private val WORDS = Regex("""[\p{L}\p{N}]+""")

    /** Anzeigename einer Gruppe. Leere oder schon gemischt geschriebene Namen kommen unverändert zurück. */
    fun display(raw: String): String {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return trimmed
        return trimmed.split(SEPARATOR).filter { it.isNotBlank() }.joinToString(" · ") { part -> fixCase(part.trim()) }
    }

    /** Versalien in Mischschreibung, Wort für Wort – Kürzel und kurze Wörter bleiben, wie sie sind. */
    private fun fixCase(part: String): String {
        // Schon gemischt geschrieben? Dann hat es jemand so gewollt - nichts anfassen.
        if (part.any { it.isLowerCase() }) return part
        return WORDS.replace(part) { m ->
            val word = m.value
            if (word.length < MIN_FIX || word.uppercase(Locale.ROOT) in ACRONYMS || word.any { it.isDigit() }) {
                word
            } else {
                word.take(1) + word.drop(1).lowercase(Locale.ROOT)
            }
        }
    }
}
