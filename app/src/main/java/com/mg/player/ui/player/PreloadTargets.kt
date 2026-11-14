package com.mg.player.ui.player

/** Ein Kandidat zum Vorladen: dieser Sender, in dieser Variante (Original-ID), auf diesem Zugang. */
internal data class PreloadTarget(val channelId: String, val itemId: String, val playlistId: Long)

internal object PreloadTargets {
    /**
     * Bis zu [maxTargets] Streams zum Vorladen – **höchstens einer je Zugang** (jeder Zugang erlaubt nur eine Verbindung) und
     * nie auf [activePlaylistId] (dort läuft der Sender gerade). Die Reihenfolge von [candidateIds] ist die Rangfolge: Der erste
     * Sender mit einer Variante auf einem freien anderen Zugang bekommt diesen Zugang. Ein Sender wird nur einmal gewählt.
     * [variantsOf]: (Zugang, Original-ID) je Variante, beste zuerst. Zugänge ohne echte ID (≤ 0, z. B. „Alle Zugänge“)
     * zählen nie. Je Zugang wird [hasFreeSlot] nur einmal gefragt. Rein, ohne Datenbank/Netz – direkt testbar.
     */
    suspend fun pick(
        candidateIds: List<String>,
        activePlaylistId: Long,
        maxTargets: Int,
        variantsOf: suspend (String) -> List<Pair<Long, String>>,
        hasFreeSlot: suspend (Long) -> Boolean,
    ): List<PreloadTarget> {
        val free = HashMap<Long, Boolean>()
        val chosen = LinkedHashMap<Long, PreloadTarget>()
        for (id in candidateIds) {
            if (chosen.size >= maxTargets) break
            if (chosen.values.none { it.channelId == id }) {
                firstUsable(id, activePlaylistId, chosen.keys, variantsOf) { free.getOrPut(it) { hasFreeSlot(it) } }
                    ?.let { chosen[it.playlistId] = it }
            }
        }
        return chosen.values.toList()
    }

    /** Die beste Variante des Senders auf einem echten, noch nicht belegten anderen Zugang mit freiem Platz – oder null. */
    private suspend fun firstUsable(
        channelId: String,
        activePlaylistId: Long,
        takenPlaylists: Set<Long>,
        variantsOf: suspend (String) -> List<Pair<Long, String>>,
        isFree: suspend (Long) -> Boolean,
    ): PreloadTarget? {
        for ((playlistId, itemId) in variantsOf(channelId)) {
            val candidate = playlistId > 0 && playlistId != activePlaylistId && playlistId !in takenPlaylists
            if (candidate && isFree(playlistId)) return PreloadTarget(channelId, itemId, playlistId)
        }
        return null
    }
}
