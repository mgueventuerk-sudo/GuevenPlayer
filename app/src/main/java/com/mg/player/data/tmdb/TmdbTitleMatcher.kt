package com.mg.player.data.tmdb

import com.mg.player.core.util.MediaTitle
import com.mg.player.data.search.SearchText

/** Vergleicht TMDB-Namen mit IPTV-Anbieternamen nach Entfernen von Ländern, Jahren und Qualitäts-Tags. */
internal object TmdbTitleMatcher {
    /** Ähnlich statt gleich erst ab so vielen Zeichen – kürzere Titel müssen exakt passen. */
    private const val MIN_FUZZY_CHARS = 7

    /** Kürzerer Titel mindestens so lang im Verhältnis zum längeren. */
    private const val MIN_LENGTH_RATIO = 0.82f

    /** Einzelwörter dürfen ab dieser Länge einen Tippfehler haben. */
    private const val MIN_FUZZY_WORD = 6

    enum class Match { EXACT, APPROXIMATE }

    private val ignoredWords = setOf(
        "the", "a", "an", "of", "and", "to", "in", "on", "der", "die", "das", "den", "dem", "ein", "eine", "einer", "und",
        "de", "at", "ch", "tr", "uk", "fr", "es", "nl", "pl", "en", "ger", "eng",
    )

    fun compare(catalogTitles: Collection<String>, libraryTitle: String): Match? {
        val libraryTokens = tokens(libraryTitle)
        if (libraryTokens.isEmpty()) return null
        val libraryForms = forms(libraryTitle)
        val cleanedCatalogTitles = catalogTitles.flatMap(::forms)
        if (libraryForms.any { it in cleanedCatalogTitles }) return Match.EXACT

        return if (catalogTitles.any { similar(tokens(it), libraryTokens) }) Match.APPROXIMATE else null
    }

    private fun similar(catalogTokens: List<String>, libraryTokens: List<String>): Boolean {
        val catalogTokens = catalogTokens.filterNot { it in ignoredWords }
        if (catalogTokens.isEmpty()) return false
        val catalogCompact = catalogTokens.joinToString("")
        val libraryCompact = libraryTokens.joinToString("")
        val shorter = minOf(catalogCompact.length, libraryCompact.length)
        val longer = maxOf(catalogCompact.length, libraryCompact.length)
        if (shorter >= MIN_FUZZY_CHARS && shorter.toFloat() / longer >= MIN_LENGTH_RATIO && editDistanceAtMost(catalogCompact, libraryCompact, 1)) return true

        val shorterTokens: List<String>
        val longerTokens: List<String>
        if (catalogTokens.size <= libraryTokens.size) {
            shorterTokens = catalogTokens
            longerTokens = libraryTokens
        } else {
            shorterTokens = libraryTokens
            longerTokens = catalogTokens
        }
        val shorterLength = shorterTokens.sumOf(String::length)
        val longerLength = longerTokens.sumOf(String::length)
        if (shorterLength < MIN_FUZZY_CHARS || shorterLength.toFloat() / longerLength < MIN_LENGTH_RATIO) return false

        val used = HashSet<Int>()
        return shorterTokens.all { word ->
            val candidateIndex = longerTokens.indices.firstOrNull { index ->
                index !in used && (longerTokens[index] == word || (word.length >= MIN_FUZZY_WORD && editDistanceAtMost(word, longerTokens[index], 1)))
            } ?: return@all false
            used += candidateIndex
            true
        }
    }

    private fun forms(raw: String): Set<String> {
        val cleaned = TitleCleaner.clean(MediaTitle.parse(raw).title).title
        val words = SearchText.words(cleaned)
        if (words.isEmpty()) return emptySet()
        return setOf(words.joinToString(""), TitleCleaner.normalize(cleaned).replace(" ", ""))
            .filterTo(LinkedHashSet()) { it.length >= 2 }
    }

    private fun tokens(raw: String): List<String> = SearchText.words(TitleCleaner.clean(MediaTitle.parse(raw).title).title)
        .filterNot { it in ignoredWords }

    /** Bounded Levenshtein distance: rejects quickly for names that cannot be within [limit] edits. */
    private fun editDistanceAtMost(left: String, right: String, limit: Int): Boolean {
        if (kotlin.math.abs(left.length - right.length) > limit) return false
        var previous = IntArray(right.length + 1) { it }
        for (i in left.indices) {
            val current = IntArray(right.length + 1)
            current[0] = i + 1
            var rowMinimum = current[0]
            for (j in right.indices) {
                current[j + 1] = minOf(
                    current[j] + 1,
                    previous[j + 1] + 1,
                    previous[j] + if (left[i] == right[j]) 0 else 1,
                )
                rowMinimum = minOf(rowMinimum, current[j + 1])
            }
            if (rowMinimum > limit) return false
            previous = current
        }
        return previous[right.length] <= limit
    }
}
