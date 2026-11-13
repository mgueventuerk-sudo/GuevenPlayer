package com.mg.player.player.supervisor

/**
 * Sieht der Sendername nach Radio aus? Nur ein Hinweis, nie ein Beweis: Wer sich irrt, bekommt den alten Weg
 * („Nur Ton“-Hinweis, keine Maßnahme) – nie ein unnötiges Umschalten. Hatte der Sender nachweislich ein Bild,
 * zählt das mehr als der Name (z. B. „Radio Bremen TV“).
 */
object RadioNames {
    private val WORDS = Regex("""(?<![\p{L}\p{N}])(radio|radyo|rádio|radios|radyosu|радио|fm)(?![\p{L}\p{N}])""", RegexOption.IGNORE_CASE)

    fun looksLikeRadio(name: String): Boolean = WORDS.containsMatchIn(name)
}
