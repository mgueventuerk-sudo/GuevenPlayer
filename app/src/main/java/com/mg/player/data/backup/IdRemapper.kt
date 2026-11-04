package com.mg.player.data.backup

/**
 * Beim Wiederherstellen bekommen Playlists neue IDs. Alle gespeicherten Bezüge
 * ("5:M:123", "movie:5:M:123") werden auf die neue Playlist-ID umgeschrieben.
 */
object IdRemapper {

    private val idPattern = Regex("^(\\d+):(.*)$")
    private val typedPrefixes = setOf("movie", "episode", "live")

    fun remapId(id: String, map: Map<Long, Long>): String {
        val match = idPattern.matchEntire(id) ?: return id
        val old = match.groupValues[1].toLongOrNull() ?: return id
        val new = map[old] ?: return id
        return "$new:${match.groupValues[2]}"
    }

    fun remapKey(key: String, map: Map<Long, Long>): String {
        val prefix = key.substringBefore(':', "")
        return if (prefix in typedPrefixes) "$prefix:" + remapId(key.substringAfter(':'), map) else remapId(key, map)
    }
}
