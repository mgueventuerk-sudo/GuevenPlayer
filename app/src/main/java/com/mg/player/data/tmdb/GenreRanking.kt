package com.mg.player.data.tmdb

/**
 * "Deine Empfehlungen" bestand bisher nur aus TMDBs eigenen "neueste"/"bestbewertete" Listen, ohne Bezug zum
 * eigenen Sehverlauf. Reine lokale Heuristik: Titel, die zu den häufigsten Genres der eigenen Favoriten passen,
 * stehen innerhalb derselben, bereits geladenen TMDB-Liste weiter vorne - es wird nichts neu geladen oder gefiltert,
 * nur umsortiert. Ob das spürbar bessere Vorschläge liefert, ist ungeprüft (keine Nutzungsdaten dafür vorhanden).
 */
object GenreRanking {

    /** Häufigste Genres über mehrere Titel hinweg (z. B. Favoriten); bei Gleichstand bleibt die Eingabereihenfolge. */
    fun preferredGenres(genresPerTitle: List<List<String>>, max: Int = 2): Set<String> {
        val counts = LinkedHashMap<String, Int>()
        genresPerTitle.flatten().forEach { counts[it] = (counts[it] ?: 0) + 1 }
        return counts.entries.sortedByDescending { it.value }.take(max).mapTo(LinkedHashSet()) { it.key }
    }

    /** Stabile Sortierung: Treffer mit einem bevorzugten Genre zuerst, sonst bleibt die ursprüngliche Reihenfolge. */
    fun <T> rank(items: List<T>, preferred: Set<String>, genresOf: (T) -> List<String>): List<T> =
        if (preferred.isEmpty()) items else items.sortedBy { if (genresOf(it).any { g -> g in preferred }) 0 else 1 }
}
