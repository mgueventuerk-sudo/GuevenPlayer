package com.mg.player.ui.common

import androidx.compose.ui.graphics.Color

/** Inhaltsart einer Gruppe, erkannt am Namen – für einen dezenten Farbpunkt in der Gruppenliste. */
enum class CategoryKind(val color: Color) {
    SPORT(Color(0xFF34C759)),
    NEWS(Color(0xFF0A84FF)),
    MOVIES(Color(0xFFFF453A)),
    KIDS(Color(0xFFFFD60A)),
    DOCUMENTARY(Color(0xFF64D2FF)),
    MUSIC(Color(0xFFBF5AF2)),
}

/**
 * Farbige Akzente (ab 3.42): Sport grün, Nachrichten blau, Filme rot … – man erkennt die Art einer Gruppe, bevor man
 * den Namen liest. Erkannt an Wortbestandteilen in den häufigsten Sprachen der Anbieter (de, en, tr, ar-lat., fr, es).
 * Kein Treffer = kein Punkt (lieber keine Farbe als eine falsche).
 */
object CategoryAccent {

    private val rules: List<Pair<CategoryKind, List<String>>> = listOf(
        // Reihenfolge zählt: „Sport News“ ist Sport, „Kids Movies“ ist Kinder.
        CategoryKind.KIDS to listOf("kids", "kinder", "çocuk", "cocuk", "cartoon", "enfant", "infantil", "junior"),
        CategoryKind.SPORT to listOf("sport", "spor", "bein", "dazn", "football", "fussball", "fußball", "futbol", "soccer", "liga", "nba", "nfl", "f1"),
        CategoryKind.NEWS to listOf("news", "nachrichten", "haber", "info", "actualit", "noticias", "akhbar"),
        CategoryKind.DOCUMENTARY to listOf("doku", "docu", "documentar", "belgesel", "wissen"),
        CategoryKind.MUSIC to listOf("musik", "music", "müzik", "muzik", "musique", "radio"),
        CategoryKind.MOVIES to listOf("film", "movie", "cinema", "kino", "sinema", "vod", "cine"),
    )

    fun of(name: String): CategoryKind? {
        val words = name.lowercase().split(Regex("[^\\p{L}\\p{N}]+")).filter { it.isNotBlank() }
        return rules.firstOrNull { (_, keys) -> words.any { word -> keys.any { key -> matches(word, key) } } }?.first
    }

    /** Kurze Schlüssel (≤ 3 Zeichen wie „f1“, „nba“) nur als ganzes Wort, längere auch als Wortanfang („sports“, „filme“). */
    private fun matches(word: String, key: String): Boolean = if (key.length <= 3) word == key else word.startsWith(key)
}
