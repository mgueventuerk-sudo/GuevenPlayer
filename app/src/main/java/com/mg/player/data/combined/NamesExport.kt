package com.mg.player.data.combined

import com.mg.player.data.db.AppDatabase

/**
 * Testsatz für den Namensabgleich: Gruppen- und Sendernamen, Titel und EPG-IDs aller Zugänge – ohne Zugangsdaten,
 * ohne Adressen, ohne Logos. Zugänge erscheinen nur als Nummer. Zeilen: `Art|Zugang|Gruppe|Name|EPG-ID bzw. Jahr|TMDB`.
 */
object NamesExport {
    private const val LIMIT_PER_TYPE = 20_000

    suspend fun build(db: AppDatabase): String {
        val dao = db.combinedDao()
        val sb = StringBuilder()
        sb.appendLine("# GuevenPlayer – Namen für den Abgleich (ohne Zugangsdaten)")
        db.playlistDao().all().forEachIndexed { n, playlist ->
            val groups = dao.categorySources(playlist.id).associate { it.id to it.name }
            fun group(id: String) = clean(groups[id].orEmpty())
            dao.channelSources(playlist.id).take(LIMIT_PER_TYPE).forEach {
                sb.append("L|").append(n + 1).append('|').append(group(it.categoryId)).append('|').append(clean(it.name)).append('|')
                    .appendLine(clean(it.epgChannelId))
            }
            dao.movieSources(playlist.id).take(LIMIT_PER_TYPE).forEach {
                sb.append("M|").append(n + 1).append('|').append(group(it.categoryId)).append('|').append(clean(it.name)).append('|')
                    .append(it.year.orEmpty()).append('|').appendLine(it.tmdbId.orEmpty())
            }
            dao.seriesSources(playlist.id).take(LIMIT_PER_TYPE).forEach {
                sb.append("S|").append(n + 1).append('|').append(group(it.categoryId)).append('|').append(clean(it.name)).append('|')
                    .append(it.year.orEmpty()).append('|').appendLine(it.tmdbId.orEmpty())
            }
        }
        return sb.toString()
    }

    /** Trennzeichen und Zeilenumbrüche aus Namen entfernen; Adressen haben hier nichts verloren. */
    private fun clean(s: String): String = s.replace('|', '/').replace('\n', ' ').replace('\r', ' ').let { if ("://" in it) "" else it }
}
