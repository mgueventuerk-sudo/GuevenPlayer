package com.mg.player.ui.common

/**
 * Buch über die Bilder, die wir **von uns aus** in den Arbeitsspeicher geladen haben (nicht über die, die die Oberfläche
 * selbst angefordert hat). Daran entscheidet sich, was wieder freigegeben wird – und nur davon: Was die Oberfläche gezeigt
 * hat, gehört dem normalen Cache und seiner LRU-Regel, nicht uns.
 *
 * Freigegeben wird ein Eintrag, den **nie jemand gezeigt hat**, wenn
 * 1. er außerhalb des Fensters liegt, in dem er bald gebraucht werden könnte ([outsideWindow]) – z. B. weiter weg
 *    gescrollt oder die Kategorie gewechselt – oder
 * 2. er zu lange ungenutzt liegt ([expired]) – z. B. weil man die Liste verlassen hat – oder
 * 3. der Speicher knapp wird ([all]).
 *
 * Rein (kein Android, keine Coil-Typen): Der Schlüssel [K] ist bei Coil der `MemoryCache.Key`.
 */
internal class PrefetchLedger<K : Any>(private val clock: () -> Long = System::currentTimeMillis) {
    /** Ein freigegebener Eintrag: der Schlüssel im Cache und die Adresse (damit sie später wieder vorgeladen werden darf). */
    data class Released<K>(val key: K, val url: String)

    private class Entry(val url: String, val addedAt: Long)

    private val entries = LinkedHashMap<K, Entry>()

    /** Wie viele vorgeladene, noch ungenutzte Bilder gerade im Speicher liegen. */
    @get:Synchronized
    val size: Int get() = entries.size

    @Synchronized
    fun add(key: K, url: String) {
        entries[key] = Entry(url, clock())
    }

    /** Die Oberfläche hat dieses Bild gezeigt: Es war nicht umsonst und gehört ab jetzt dem normalen Cache. true = war unseres. */
    @Synchronized
    fun markUsed(key: K): Boolean = entries.remove(key) != null

    /** Ungenutzte Einträge, deren Bild nicht in [wantedUrls] vorkommt. Sie werden aus dem Buch genommen und zurückgegeben. */
    @Synchronized
    fun outsideWindow(wantedUrls: Set<String>): List<Released<K>> = take { it.url !in wantedUrls }


    /** Ungenutzte Einträge, die länger als [ttlMs] liegen. */
    @Synchronized
    fun expired(ttlMs: Long): List<Released<K>> {
        val now = clock()
        return take { now - it.addedAt > ttlMs }
    }

    /** Alle ungenutzten Einträge (bei knappem Speicher). */
    @Synchronized
    fun all(): List<Released<K>> = take { true }

    private inline fun take(release: (Entry) -> Boolean): List<Released<K>> {
        val gone = entries.filterValues(release).map { (key, entry) -> Released(key, entry.url) }
        gone.forEach { entries.remove(it.key) }
        return gone
    }
}
