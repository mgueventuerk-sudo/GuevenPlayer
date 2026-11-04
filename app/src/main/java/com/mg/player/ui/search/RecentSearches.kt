package com.mg.player.ui.search

/** Zuletzt gesuchte Begriffe: neueste zuerst, ohne Doppelte (Groß/Klein egal), höchstens [MAX]. */
internal object RecentSearches {
    const val MAX = 8

    /** Mindestlänge wie bei der Suche selbst. */
    private const val MIN_LENGTH = 2

    fun add(list: List<String>, query: String): List<String> {
        val q = query.trim().replace(Regex("\\s+"), " ")
        if (q.length < MIN_LENGTH) return list
        return (listOf(q) + list.filterNot { it.equals(q, ignoreCase = true) }).take(MAX)
    }
}
