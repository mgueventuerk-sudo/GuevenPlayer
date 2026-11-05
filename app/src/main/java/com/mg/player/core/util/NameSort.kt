package com.mg.player.core.util

import java.util.Locale

/**
 * Alphabetische Ordnung für Sendernamen wie ein Mensch sie erwartet (ab 3.44): Anbieter-Vorsätze („DE:“, „|DE|“,
 * „TR ▸“), Zierzeichen und Qualitätsangaben (HD, FHD, UHD, 4K) zählen nicht, Groß/Klein und Umlaute sind egal,
 * Zahlen werden als Zahl verglichen („Sky Sport 2“ vor „Sky Sport 10“).
 */
object NameSort {

    // Vorsatz = 2–3 Buchstaben mit Trenner: „DE:“, „|DE|“, „[DE]“, „DE |“, „TR ▸“ – nicht „ARD-alpha“ oder „ZDF neo“.
    private val prefix = Regex("^\\s*(?:[\\[(|]\\s*)?[A-Za-z]{2,3}\\s*(?:[\\])|:▸►»]|\\s\\|)\\s*")
    private val decoration = Regex("[^\\p{L}\\p{N} ]")
    private val quality = setOf("hd", "fhd", "uhd", "sd", "4k", "8k", "hevc", "h265", "1080p", "720p", "2160p", "raw")
    private val number = Regex("\\d+")
    private const val NUMBER_WIDTH = 8

    /** Vergleichsschlüssel; gleiche Schlüssel ordnet [comparator] nach dem Originalnamen. */
    fun key(name: String): String {
        var rest = name
        // Mehrere Vorsätze hintereinander („|DE| ✪ …“): bis zu dreimal abschneiden.
        repeat(MAX_PREFIXES) { rest = rest.replace(prefix, "") }
        val words = TextFold.plain(rest).lowercase(Locale.ROOT).replace(decoration, " ").split(' ')
            .filter { it.isNotBlank() && it !in quality }
        return words.joinToString(" ") { word -> word.replace(number) { it.value.padStart(NUMBER_WIDTH, '0') } }
    }

    val comparator: Comparator<String> = compareBy<String> { key(it) }.thenBy { it }

    private const val MAX_PREFIXES = 3
}
