package com.mg.player.ui.player

/** Ein Kandidat zum Vorladen: dieser Sender, in dieser Variante (Original-ID), auf diesem Zugang. */
internal data class PreloadTarget(val channelId: String, val itemId: String, val playlistId: Long)

internal object PreloadTargets {
    /**
     * Erster Sender aus [candidateIds], der eine Variante auf einem **anderen** echten Zugang als [activePlaylistId] hat, auf
     * dem nachweislich ein Platz frei ist – samt der Variante. [variantsOf]: (Zugang, Original-ID) je Variante, beste
     * zuerst. Zugänge ohne echte ID (≤ 0, z. B. „Alle Zugänge“) zählen nie. Je Zugang wird [hasFreeSlot] nur einmal gefragt.
     * Rein, ohne Datenbank/Netz – direkt testbar.
     */
    suspend fun pick(
        candidateIds: List<String>,
        activePlaylistId: Long,
        variantsOf: suspend (String) -> List<Pair<Long, String>>,
        hasFreeSlot: suspend (Long) -> Boolean,
    ): PreloadTarget? {
        val free = HashMap<Long, Boolean>()
        for (id in candidateIds) {
            for ((playlistId, itemId) in variantsOf(id)) {
                if (playlistId <= 0 || playlistId == activePlaylistId) continue
                if (free.getOrPut(playlistId) { hasFreeSlot(playlistId) }) return PreloadTarget(id, itemId, playlistId)
            }
        }
        return null
    }
}
