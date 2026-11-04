package com.mg.player.data.importer

import com.mg.player.core.util.CountryDetector
import com.mg.player.core.util.MediaTitle

/**
 * Gruppierungsdaten eines Senders, schon beim Import berechnet: Varianten wie "ZDF UHD", "ZDF FHD",
 * "ZDF HD" bekommen denselben [key], [rank] sortiert sie nach Qualität (kleiner = besser).
 */
internal data class ChannelGrouping(val key: String, val rank: Int) {
    companion object {
        /** [categoryCountry] hilft, wenn der Sendername selbst kein Land nennt. */
        fun of(name: String, categoryCountry: String?): ChannelGrouping {
            val info = MediaTitle.parse(name)
            val country = CountryDetector.detect(name) ?: categoryCountry
            return ChannelGrouping(MediaTitle.groupKey(info.title, country), MediaTitle.qualityRank(info.badges))
        }
    }
}
