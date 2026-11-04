package com.mg.player.data.combined

import com.mg.player.data.importer.stableHash
import com.mg.player.domain.model.Playlist
import com.mg.player.domain.model.PlaylistType

/**
 * „Alle Zugänge“: ein virtueller Zugang mit fester ID. Seine Sender, Filme, Serien und Kategorien liegen in denselben
 * Tabellen wie die echten (playlistId = [ID]) – Listen, Suche, Favoriten und Verlauf funktionieren damit unverändert.
 * Welche echten Einträge dahinterstehen, steht in `combined_links`; abgespielt wird immer ein Original.
 */
object Combined {
    const val ID = -2L
    const val NAME = "Alle Zugänge"
    private const val PREFIX = "$ID:"

    fun isCombined(playlistId: Long?): Boolean = playlistId == ID

    fun isCombinedItem(itemId: String?): Boolean = itemId?.startsWith(PREFIX) == true

    /** Stabile ID aus dem Vergleichsschlüssel: Favoriten und Verlauf bleiben über jeden Neuaufbau erhalten. */
    fun itemId(kind: Char, key: String): String = "$ID:$kind:${stableHash(key)}"

    fun categoryId(type: String, key: String): String = "$ID:C:$type:${stableHash(key)}"

    /** Anzeige-Zugang für „Alle Zugänge“ (es gibt keine Zeile in `playlists`). */
    fun playlist(lastUpdatedMs: Long): Playlist = Playlist(
        id = ID,
        name = NAME,
        type = PlaylistType.M3U,
        serverUrl = "",
        username = "",
        password = "",
        m3uUrl = "",
        customEpgUrl = "",
        headerEpgUrl = "",
        lastUpdatedMs = lastUpdatedMs,
    )
}
