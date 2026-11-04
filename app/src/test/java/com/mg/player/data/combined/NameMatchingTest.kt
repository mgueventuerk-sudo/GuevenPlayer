package com.mg.player.data.combined

import com.mg.player.core.util.CountryDetector
import com.mg.player.data.db.dao.ChannelSourceRow
import com.mg.player.data.db.dao.VodSourceRow
import com.mg.player.data.importer.ChannelGrouping
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Plan Namensabgleich (3.28): verschiedene Schreibweisen derselben Inhalte zusammenführen – und ähnliche, aber
 * verschiedene Inhalte getrennt lassen. Im Zweifel getrennt.
 */
class NameMatchingTest {

    private var n = 0

    private fun ch(playlist: Long, name: String, epg: String = "", group: String = "DE | Vollprogramm"): ChannelSourceRow {
        val grouping = ChannelGrouping.of(name, CountryDetector.detect(group))
        return ChannelSourceRow("$playlist:L:${n++}", playlist, "$playlist:cat", name, null, epg, epg.lowercase(), grouping.key, grouping.rank, n, 0)
    }

    private fun together(vararg rows: ChannelSourceRow, prints: Map<String, String> = emptyMap(), rules: MatchRules = MatchRules()): Boolean {
        val clusters = ChannelClusterer.cluster(rows.toList(), rules, EpgPrints(link = prints))
        return clusters.size == 1
    }

    @Test
    fun sameChannelDifferentSpellings() {
        // EPG-ID mit Qualitätszusatz, Länderpräfix, Zierzeichen.
        assertEquals(true, together(ch(1, "DE: ZDF HD", "ZDF.de"), ch(2, "|DE| ZDF FHD ⭐", "ZDFHD.de")))
        // Nur über das Synonym-Wörterbuch: ARD = Das Erste.
        assertEquals(true, together(ch(1, "DE: Das Erste HD"), ch(2, "DE | ARD ᴴᴰ")))
        assertEquals(true, together(ch(1, "DE: RTL II"), ch(2, "RTL ZWEI HD", group = "Germany")))
        assertEquals(true, together(ch(1, "DE: Pro7 FHD"), ch(2, "DE: ProSieben")))
        // Name ohne Land findet den Sender, den es nur in einem Land gibt.
        assertEquals(true, together(ch(1, "DE: tagesschau24"), ch(2, "tagesschau24", group = "News")))
    }

    @Test
    fun similarButDifferentChannelsStaySeparate() {
        assertEquals(false, together(ch(1, "DE: ZDF HD", "zdf.de"), ch(2, "DE: ZDFneo", "zdfneo.de")))
        assertEquals(false, together(ch(1, "DE: WDR (Köln)"), ch(2, "DE: WDR (Aachen)")))
        assertEquals(false, together(ch(1, "DE: Sky Sport 1"), ch(2, "DE: Sky Sport 2")))
        assertEquals(false, together(ch(1, "DE: RTL"), ch(2, "DE: RTL +1")))
        // Gleicher Name, anderes Land.
        assertEquals(false, together(ch(1, "DE: Sport 1"), ch(2, "PL: Sport 1", group = "PL | Sport")))
        // Ohne Land, aber den Namen gibt es in zwei Ländern: unsicher → getrennt.
        val ambiguous = listOf(ch(1, "DE: Sport 1"), ch(1, "PL: Sport 1", group = "PL | Sport"), ch(2, "Sport 1", group = "Sport"))
        assertEquals(3, ChannelClusterer.cluster(ambiguous).size)
    }

    @Test
    fun programFingerprintOnlyWhenUnique() {
        val a = ch(1, "DE: Erstes Programm Spezial")
        val b = ch(2, "Kanal Eins Deutschland")
        assertEquals(true, together(a, b, prints = mapOf(a.id to "p1", b.id to "p1")))
        // Zwei Regionalfenster desselben Zugangs mit gleichem Programm: Abdruck nicht eindeutig → nichts verbinden.
        val k = ch(1, "DE: WDR (Köln)")
        val aa = ch(1, "DE: WDR (Aachen)")
        val x = ch(2, "WDR Lokal")
        assertEquals(3, ChannelClusterer.cluster(listOf(k, aa, x), MatchRules(), EpgPrints(link = mapOf(k.id to "w", aa.id to "w", x.id to "w"))).size)
    }

    @Test
    fun ownRulesWin() {
        val a = ch(1, "DE: ZDF HD", "zdf.de")
        val b = ch(2, "DE: ZDF", "zdf.de")
        val split = MatchRules(split = listOf("e:zdf.de" to "e:zdf.de"))
        assertEquals(false, together(a, b, rules = split))
        val c = ch(1, "DE: Hauptsender Eins")
        val d = ch(2, "DE: HSE")
        assertEquals(true, together(c, d, rules = MatchRules(merge = listOf("n:DE:hauptsendereins" to "n:DE:hse"))))
    }

    private fun vod(playlist: Long, name: String, year: String? = null, tmdb: String? = null) =
        VodSourceRow("$playlist:M:${n++}", playlist, "$playlist:cat", name, year, tmdb, n, null, null, null)

    @Test
    fun moviesBySameWorkNotSameTitle() {
        fun groups(vararg rows: VodSourceRow, series: Boolean = false) = VodClusterer.cluster(rows.toList(), series).size
        // Andere Sprache, gleiche TMDB-ID.
        assertEquals(1, groups(vod(1, "DE: Der Pate (1972)", tmdb = "238"), vod(2, "The.Godfather.1972.MULTI.1080p", tmdb = "238")))
        // Gleicher Titel, gleiches Jahr, ohne IDs.
        assertEquals(1, groups(vod(1, "|DE| Inception (2010) [4K]"), vod(2, "Inception 2010"), vod(3, "Inception 2010 [FHD]")))
        // Remake: anderes Jahr.
        assertEquals(2, groups(vod(1, "Dune (1984)"), vod(2, "Dune (2021)")))
        // Gleicher Titel + Jahr, aber zwei verschiedene TMDB-IDs: nicht über den Titel verbinden.
        assertEquals(3, groups(vod(1, "Heat (1995)", tmdb = "949"), vod(2, "Heat (1995)", tmdb = "999"), vod(3, "Heat (1995)")))
        // Film ohne Jahr und ohne ID: zu unsicher, bleibt allein.
        assertEquals(2, groups(vod(1, "Der Kaiser von Kalifornien"), vod(2, "Der Kaiser von Kalifornien")))
        // Serie ohne Jahr findet das eine Jahr, das es zu dem Titel gibt.
        assertEquals(1, groups(vod(1, "Dark (2017)"), vod(2, "DE: Dark"), series = true))
    }

    @Test
    fun categoriesByCountryAndTopic() {
        val a = CategoryTopics.classify("LIVE", "DE | Sport")
        val b = CategoryTopics.classify("LIVE", "GERMANY SPORTS HD")
        assertEquals(a.key, b.key)
        assertEquals("Deutschland · Sport", a.label)
        assertNotEquals(a.key, CategoryTopics.classify("LIVE", "DE | Vollprogramm").key)
        assertNotEquals(a.key, CategoryTopics.classify("MOVIE", "DE | Sport").key)
        assertNull(CategoryTopics.classify("LIVE", "DE | Transport").topic)
        assertEquals(CategoryTopics.classify("LIVE", "TR: Spor").key, CategoryTopics.classify("LIVE", "TURKEY SPORTS").key)
        // Ohne erkennbares Thema nur bei gleichem Namen zusammen.
        assertEquals(CategoryTopics.classify("LIVE", "DE | Eigene").key, CategoryTopics.classify("LIVE", "DE: Eigene").key)
    }

    @Test
    fun epgIdsNormalized() {
        assertEquals("zdf.de", MatchKeys.epg("ZDFHD.de"))
        assertEquals("ard.de", MatchKeys.epg("DasErste.de"))
        assertNull(MatchKeys.epg("none"))
        assertNull(MatchKeys.epg("12345"))
    }
}
