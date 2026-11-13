package com.mg.player.ui.common

/** Welche Bilder nach den sichtbaren Einträgen vorzuladen sind. Rein, damit testbar. */
internal object PrefetchPlan {
    /**
     * Die Adressen der nächsten [count] Einträge nach [lastVisible] (nur bereits geladene Einträge bis [itemCount]),
     * ohne leere, doppelte und die, für die [alreadyDone] gilt.
     */
    fun upcoming(lastVisible: Int, itemCount: Int, count: Int, urlAt: (Int) -> String?, alreadyDone: (String) -> Boolean): List<String> {
        if (lastVisible < 0 || count <= 0) return emptyList()
        val end = minOf(itemCount, lastVisible + 1 + count)
        return (lastVisible + 1 until end).mapNotNull { urlAt(it)?.takeIf(String::isNotBlank) }.distinct().filterNot(alreadyDone)
    }
}
