package com.mg.player.data.sport

import com.mg.player.core.util.CountryDetector
import com.mg.player.core.util.NameNormalizer
import com.mg.player.data.sport.guide.GuideCatalog

/**
 * Sendernamen aus Online-Sendelisten an die Namen der Anbieter angleichen (ab 3.47): Bzzoiro nennt z. B.
 * „beIN SPORTS 2 Türkiye“ oder „Sky Sport Bundesliga Deutschland“, der Anbieter „|TR| BEIN SPORTS 2 FHD“ bzw.
 * „DE: Sky Sport Bundesliga HD“. Länderwörter zählen deshalb nicht; die Sendernummer bleibt (2 ≠ 1).
 */
internal object StationNames {

    /** Normalisierter Name ohne Länderwörter (Türkiye, Turkey, Deutschland, TR, DE …). */
    fun withoutCountry(name: String): String {
        val words = name.split(Regex("[^\\p{L}\\p{N}+]+")).filter { it.isNotBlank() && CountryDetector.codeFor(it) == null }
        return NameNormalizer.normalize(words.joinToString(" ").ifBlank { name })
    }

    /**
     * Passt der Kanal [channel] zum Land des Sendernamens [station]? Ohne Land beim Sender passt jeder; sonst nur derselbe
     * Länder-Vorsatz oder ein Kanal ohne Länderangabe („TR| beIN SPORTS 1“ ↔ „|TR| BEIN SPORTS 1 FHD“, nicht „|AR| …“).
     */
    fun sameCountry(station: String, channel: String): Boolean {
        val wanted = GuideCatalog.countryOfName(station) ?: return true
        val actual = GuideCatalog.countryOfName(channel) ?: return true
        return wanted == actual
    }
}
