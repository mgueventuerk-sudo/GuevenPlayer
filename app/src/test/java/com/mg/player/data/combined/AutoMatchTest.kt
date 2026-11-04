package com.mg.player.data.combined

import com.mg.player.core.util.CountryDetector
import com.mg.player.data.db.dao.ChannelSourceRow
import com.mg.player.data.importer.ChannelGrouping
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import java.io.FileInputStream
import java.util.zip.GZIPInputStream

/**
 * Vollautomatische Zuordnung (3.34): Senderkatalog + Schutzregeln. Ziel – keine Prüfliste mehr: Was derselbe Sender ist,
 * läuft zusammen; was sich widerspricht (anderer Katalog-Sender, anderes Land, anderes Programm), bleibt getrennt.
 */
class AutoMatchTest {

    companion object {
        private lateinit var catalog: ChannelCatalog
        private var loadMs = 0L

        @JvmStatic
        @BeforeClass
        fun load() {
            val start = System.nanoTime()
            catalog = GZIPInputStream(FileInputStream("src/main/assets/channel-catalog.dat")).use(ChannelCatalog::parse)
            loadMs = (System.nanoTime() - start) / 1_000_000
        }
    }

    private var n = 0

    private fun ch(playlist: Long, name: String, epg: String = "", group: String = "DE | Vollprogramm"): ChannelSourceRow {
        val grouping = ChannelGrouping.of(name, CountryDetector.detect(group))
        return ChannelSourceRow("$playlist:L:${n++}", playlist, "$playlist:cat", name, null, epg, epg.lowercase(), grouping.key, grouping.rank, n, 0)
    }

    private fun plain(vararg rows: ChannelSourceRow) = ChannelClusterer.cluster(rows.toList(), MatchRules(), EpgPrints(), ChannelCatalog.EMPTY).size

    private fun groups(vararg rows: ChannelSourceRow, prints: EpgPrints = EpgPrints(), stats: MatchStats? = null) =
        ChannelClusterer.cluster(rows.toList(), MatchRules(), prints, catalog, stats).size

    @Test
    fun catalogIsComplete() {
        assertTrue("Katalog zu klein: ${catalog.size}", catalog.size > 25_000)
        assertTrue("Katalog laden dauerte $loadMs ms", loadMs < 3_000)
        assertEquals("DasErste.de", catalog.byNameKey(ChannelCatalog.keyFor("Das Erste", "DE")))
        assertEquals("DasErste.de", catalog.byNameKey(ChannelCatalog.keyFor("ARD", "DE")))
        assertEquals("DasErste.de", catalog.byEpgId("daserste.de@HD"))
    }

    @Test
    fun qualityVariantsOfThreeProvidersBecomeOne() {
        val rows = arrayOf(
            ch(1, "DE: n-tv HD", "ntv.de"),
            ch(1, "DE: n-tv FHD"),
            ch(2, "|DE| NTV ᴴᴰ"),
            ch(3, "n-tv RAW", group = "Deutschland"),
            ch(3, "TR: NTV", group = "TR | Haber"),
        )
        // Alle deutschen n-tv-Varianten zusammen, das türkische NTV getrennt.
        assertEquals(2, groups(*rows))
    }

    @Test
    fun wrongEpgIdDoesNotPullZdfneoIntoZdf() {
        // Anbieter B hat ZDFneo die EPG-ID von ZDF gegeben – der Name gewinnt.
        assertEquals(2, groups(ch(1, "DE: ZDF HD", "ZDF.de"), ch(2, "DE: ZDFneo", "ZDF.de"), ch(3, "ZDF neo HD", group = "Germany")))
    }

    @Test
    fun chainCannotJoinTwoDifferentChannels() {
        // ZDF ← gleiche EPG-ID → „ZDFneo“ ← gleicher Name → ZDFneo: Die Kette darf ZDF und ZDFneo nie vereinen.
        val stats = MatchStats()
        assertEquals(2, groups(ch(1, "ZDF", "ZDF.de"), ch(2, "ZDFneo", "ZDF.de"), ch(3, "ZDFneo", "ZDFneo.de"), stats = stats))
        assertTrue(stats.refused > 0)
        assertEquals(3, stats.identified)
    }

    @Test
    fun synonymsComeFromTheCatalog() {
        assertEquals(1, groups(ch(1, "DE: Das Erste HD"), ch(2, "DE | ARD ᴴᴰ"), ch(3, "ARD Das Erste FHD", "DasErste.de")))
        assertEquals(1, groups(ch(1, "DE: RTL II"), ch(2, "RTL ZWEI HD", group = "Germany")))
    }

    @Test
    fun programmeDecidesWhenNamesAreEqualButContentIsNot() {
        // Gleicher Name, unbekannt im Katalog: Laufen völlig andere Sendungen, ist es nicht derselbe Sender.
        val a = ch(1, "DE: Sky Cinema Event 1")
        val b = ch(2, "DE: Sky Cinema Event 1")
        val apart = EpgPrints(titles = mapOf(a.id to intArrayOf(1, 2, 3), b.id to intArrayOf(7, 8, 9)))
        val shared = EpgPrints(titles = mapOf(a.id to intArrayOf(1, 2, 3), b.id to intArrayOf(3, 8, 9)))
        assertEquals(2, groups(a, b, prints = apart))
        assertEquals(1, groups(a, b, prints = shared))
    }

    @Test
    fun ownMergeRuleWinsOverGuards() {
        val a = ch(1, "DE: ZDF", "ZDF.de")
        val b = ch(2, "DE: ZDFneo", "ZDFneo.de")
        val rules = MatchRules(merge = listOf("e:zdf.de" to "e:zdfneo.de"))
        assertEquals(2, ChannelClusterer.cluster(listOf(a, b), MatchRules(), EpgPrints(), catalog).size)
        assertEquals(1, ChannelClusterer.cluster(listOf(a, b), rules, EpgPrints(), catalog).size)
    }

    @Test
    fun differentTmdbIdsStayApartEvenWithEqualTitles() {
        fun vod(p: Long, name: String, tmdb: String?) = com.mg.player.data.db.dao.VodSourceRow("$p:V:${n++}", p, "c", name, null, tmdb, n, null, null, null)
        val groups = VodClusterer.cluster(listOf(vod(1, "Heat (1995)", "949"), vod(2, "Heat 1995", "999"), vod(3, "Heat (1995) 4K", "949")), false)
        assertEquals(2, groups.size)
    }

    @Test
    fun numbersKeepSiblingChannelsApart() {
        // Gemeldeter Fall: TRT 1 und TRT 2 lagen zusammen (falsche EPG-ID beim Anbieter, TRT 1 fehlte im Katalog).
        assertEquals(2, groups(ch(1, "TR: TRT 1 HD", "TRT1.tr", "TR | Ulusal"), ch(2, "TR: TRT 2", "TRT1.tr", "TR | Ulusal")))
        assertEquals("TRT1.tr", catalog.byNameKey(ChannelCatalog.keyFor("TRT 1", "TR")))
        // Ohne Katalog genauso – die Zahl im Namen entscheidet.
        assertEquals(2, plain(ch(1, "DE: Sky Sport 1", "SkySport.de"), ch(2, "DE: Sky Sport 2", "SkySport.de")))
        assertEquals(2, plain(ch(1, "DE: RTL", "RTL.de"), ch(2, "DE: RTL 2", "RTL.de")))
        assertEquals(2, plain(ch(1, "UK: Channel 4", "Channel4.uk", "UK | TV"), ch(2, "UK: Channel 4 +1", "Channel4.uk", "UK | TV")))
        assertEquals(2, plain(ch(1, "DE: 3sat", "Sat1.de"), ch(2, "DE: Sat.1", "Sat1.de")))
        // Gleiche Zahl, gleiche Buchstaben: weiter zusammen.
        assertEquals(1, plain(ch(1, "DE: Sky Sport 1 HD", "SkySport1.de"), ch(2, "|DE| SKY SPORT 1 FHD", "")))
    }

    @Test
    fun wrongEpgIdAloneNeverJoinsDifferentNames() {
        assertEquals(2, plain(ch(1, "DE: Sky Cinema Action", "SkyCinema.de"), ch(2, "DE: Sky Cinema Family", "SkyCinema.de")))
        // Ähnliche Schreibweisen mit derselben EPG-ID bleiben verbunden.
        assertEquals(1, plain(ch(1, "DE: Sky Cinema Premieren", "SkyCinemaPremieren.de"), ch(2, "DE: Sky Cinema Premiere", "SkyCinemaPremieren.de")))
    }

    @Test
    fun separatedGroupsNeverShareAnId() {
        // TRT 2 mit der EPG-ID von TRT 1: zwei Gruppen – und zwei verschiedene Schlüssel (= IDs in „Alle Zugänge“).
        val rows = listOf(
            ch(1, "TR: TRT 1 HD", "TRT1.tr", "TR | Ulusal"),
            ch(1, "TR: TRT 2 HD", "TRT2.tr", "TR | Ulusal"),
            ch(2, "TR: TRT 1", "TRT1.tr", "TR | Ulusal"),
            ch(2, "TR: TRT 2", "TRT1.tr", "TR | Ulusal"),
            ch(1, "DE: ProSieben HD", "Pro7.de"),
            ch(2, "DE: ProSieben MAXX", "Pro7.de"),
        )
        for (cat in listOf(catalog, ChannelCatalog.EMPTY)) {
            val clusters = ChannelClusterer.cluster(rows, MatchRules(), EpgPrints(), cat)
            assertEquals(4, clusters.size)
            assertEquals(clusters.size, clusters.map { it.key }.distinct().size)
        }
    }

    @Test
    fun twoLetterCodesDoNotFormNeutralWords() {
        // „pl“ + „us“ ist nicht „plus“: Florida ≠ Florida Plus, Tastemade ≠ Tastemade+.
        val florida = ch(1, "US: FanDuel Sports Network Florida HD", "FSFlorida.us", "US | TV")
        val floridaPlus = ch(2, "US: FanDuel Sports Network Florida Plus HD", "FSFlorida.us", "US | TV")
        assertEquals(2, plain(florida, floridaPlus))
        assertEquals(2, plain(ch(1, "USA - TASTEMADE HD", "Tastemade.us", "US | TV"), ch(2, "USA - TASTEMADE+ HD", "Tastemade.us", "US | TV")))
    }
}
