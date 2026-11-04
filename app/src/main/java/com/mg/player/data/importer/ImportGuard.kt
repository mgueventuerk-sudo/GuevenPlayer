package com.mg.player.data.importer

import com.mg.player.domain.model.ContentType
import java.util.concurrent.ConcurrentHashMap

/**
 * Schutz vor Datenverlust beim Aktualisieren: Überlastete Xtream-Panels liefern gern `[]` oder eine
 * HTML-Fehlerseite mit Status 200. Ohne Prüfung würde das Aufräumen danach die komplette Mediathek löschen.
 *
 * Regel je Bereich (Live-TV, Filme, Serien):
 * - Vorher leer -> neues Ergebnis immer übernehmen.
 * - Jetzt 0 Einträge oder weniger als 20 % des bisherigen Bestands (ab 200 Einträgen) -> verdächtig:
 *   die alten Einträge bleiben stehen. Liefert die nächste Aktualisierung exakt dasselbe Ergebnis,
 *   ist es offenbar echt (z. B. Paket gewechselt) und wird übernommen – auch über einen App-Neustart hinweg.
 */
class ImportGuard(private val suspicious: SuspicionStore = MemorySuspicionStore()) {

    enum class Verdict { ACCEPT, KEEP_OLD }

    /**
     * Merkt sich das letzte verdächtige Ergebnis je Zugang und Bereich. In der App dauerhaft gespeichert:
     * Sonst würde ein echter Rückgang nie übernommen, wenn man nur einmal pro App-Start aktualisiert.
     */
    interface SuspicionStore {
        operator fun get(key: String): Int?
        operator fun set(key: String, value: Int)
        fun remove(key: String)
    }

    class MemorySuspicionStore : SuspicionStore {
        private val map = ConcurrentHashMap<String, Int>()
        override fun get(key: String): Int? = map[key]
        override fun set(key: String, value: Int) {
            map[key] = value
        }
        override fun remove(key: String) {
            map.remove(key)
        }
    }

    fun judge(playlistId: Long, type: ContentType, previous: Int, fresh: Int): Verdict {
        val key = "$playlistId:${type.name}"
        val looksWrong = previous > 0 && (fresh == 0 || (previous >= MIN_BASE && fresh < previous * SHRINK_RATIO))
        if (!looksWrong) {
            suspicious.remove(key)
            return Verdict.ACCEPT
        }
        // Zweimal hintereinander exakt dasselbe verdächtige Ergebnis: dann glauben wir es.
        if (suspicious[key] == fresh) {
            suspicious.remove(key)
            return Verdict.ACCEPT
        }
        suspicious[key] = fresh
        return Verdict.KEEP_OLD
    }

    companion object {
        const val MIN_BASE = 200
        const val SHRINK_RATIO = 0.2

        fun label(type: ContentType): String = when (type) {
            ContentType.LIVE -> "Sender"
            ContentType.MOVIE -> "Filme"
            ContentType.SERIES -> "Serien"
        }
    }
}
