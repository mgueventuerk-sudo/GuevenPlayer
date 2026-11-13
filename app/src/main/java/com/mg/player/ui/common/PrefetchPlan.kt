package com.mg.player.ui.common

/** Erster und letzter sichtbarer Eintrag (-1 = nichts sichtbar). */
internal data class Visible(val first: Int, val last: Int)

/** Welche Bilder nach den sichtbaren Einträgen vorzuladen sind. Rein, damit testbar. */
internal object PrefetchPlan {
    /**
     * Die Adressen des Fensters, in dem Vorgeladenes bleiben darf: [behind] Einträge vor dem ersten und [ahead] hinter dem
     * letzten sichtbaren (nur geladene Einträge). Alles Vorgeladene außerhalb wird freigegeben.
     */
    fun window(first: Int, last: Int, itemCount: Int, behind: Int, ahead: Int, urlAt: (Int) -> String?): Set<String> {
        if (first < 0 || last < first) return emptySet()
        val from = maxOf(0, first - behind)
        val to = minOf(itemCount - 1, last + ahead)
        return (from..to).mapNotNull { urlAt(it)?.takeIf(String::isNotBlank) }.toSet()
    }

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
