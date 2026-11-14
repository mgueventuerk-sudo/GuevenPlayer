package com.mg.player.ui.player

/** Die Sender, zu denen man beim Zappen als Nächstes springt (Reihenfolge: vor, zurück; am Listenende rundherum). */
internal object ZapNeighbours {
    fun of(channelIds: List<String>, currentId: String): List<String> {
        val index = channelIds.indexOf(currentId)
        if (index < 0 || channelIds.size < 2) return emptyList()
        return listOf(index + 1, index - 1).map { channelIds[Math.floorMod(it, channelIds.size)] }.filter { it != currentId }.distinct()
    }
}
