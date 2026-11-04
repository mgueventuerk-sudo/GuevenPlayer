package com.mg.player.data.combined

import com.mg.player.core.util.CountryDetector
import com.mg.player.data.db.dao.ChannelSourceRow
import com.mg.player.data.importer.ChannelGrouping
import org.junit.Assert.assertEquals
import org.junit.BeforeClass
import org.junit.Test
import java.io.FileInputStream
import java.util.zip.GZIPInputStream

/**
 * Fälle aus einer echten Anbieterliste (21.822 Sender, 04.11.2026) – Namen, Gruppen und EPG-IDs genau wie geliefert,
 * auch die falschen EPG-IDs. Jeder Fall war vorher falsch (zusammen statt getrennt oder umgekehrt).
 */
class RealProviderCasesTest {

    companion object {
        private lateinit var catalog: ChannelCatalog

        @JvmStatic
        @BeforeClass
        fun load() {
            catalog = GZIPInputStream(FileInputStream("src/main/assets/channel-catalog.dat")).use(ChannelCatalog::parse)
        }
    }

    private var n = 0

    private fun ch(playlist: Long, name: String, epg: String, group: String): ChannelSourceRow {
        val g = ChannelGrouping.of(name, CountryDetector.detect(group))
        return ChannelSourceRow("$playlist:L:${n++}", playlist, "$playlist:c", name, null, epg, epg.lowercase(), g.key, g.rank, n, 0)
    }

    private fun apart(vararg rows: ChannelSourceRow) = assertEquals(rows.map { it.name }.toString(), rows.size, groups(*rows))

    private fun together(vararg rows: ChannelSourceRow) = assertEquals(rows.map { it.name }.toString(), 1, groups(*rows))

    private fun groups(vararg rows: ChannelSourceRow): Int {
        val withCatalog = ChannelClusterer.cluster(rows.toList(), MatchRules(), EpgPrints(), catalog).size
        val without = ChannelClusterer.cluster(rows.toList(), MatchRules(), EpgPrints(), ChannelCatalog.EMPTY).size
        // Ohne Katalog darf nie mehr zusammenlaufen als mit (der Katalog verhindert Fehler, er erzeugt keine).
        assert(without >= withCatalog || without == rows.size)
        return withCatalog
    }

    @Test
    fun wrongEpgIdsOfTheProviderDoNotJoinDifferentChannels() {
        // Anbieter gibt „XTRA 3“ die EPG-ID von beIN Sports 9.
        apart(ch(1, "BEIN SPORTS 9 HEVC", "BeinSportsPremium3.qa", "|AR| BEIN"), ch(1, "BEIN SPORTS XTRA 3 4k", "BeinSportsPremium3.qa", "|AR| BEIN"))
        // „LaLiga 123“ mit der EPG-ID von Movistar LaLiga.
        apart(ch(1, "ES - LALIGA123 HEVC", "MLaLiga.es", "|ES| DEPORTES"), ch(1, "ES - M. LALIGA FHD", "MLaLiga.es", "|ES| DEPORTES"))
        // Gemeldet: Pro 7 UHD mit der EPG-ID von MAXX.
        apart(
            ch(1, "DE: Pro 7 UHD", "ProSiebenMaxx.de", "DE | Vollprogramm"),
            ch(2, "DE - PROSIEBEN MAXX HD", "ProSiebenMaxx.de", "|DE| ALLGEMEINES"),
        )
        apart(
            ch(1, "NL - ZIGGO SPORT SELECT 4K", "ZiggoSportSelect.nl", "|NL| NEDERLAND"),
            ch(1, "NL - ZIGGO SPORT VOETBAL 4K", "ZiggoSportSelect.nl", "|NL| NEDERLAND"),
        )
    }

    @Test
    fun addedWordMeansAnotherChannel() {
        apart(
            ch(1, "USA - HBO FAMILY (EAST) HD", "HBOFamily.us", "|NA| USA GENERAL"),
            ch(1, "USA - HBO FAMILY (WEST) HD", "HBOFamily.us", "|NA| USA GENERAL"),
        )
        apart(
            ch(1, "USA - DISCOVERY WEST HD", "DiscoveryWest.us", "|NA| USA GENERAL"),
            ch(1, "USA - DISCOVERY ID WEST HD", "DiscoveryWest.us", "|NA| USA GENERAL"),
        )
        apart(
            ch(1, "FR - TELETOON+", "TeleToonPlus.fr", "|EU| FRANCE GÉNÉRAL"),
            ch(1, "FR - TÉLÉTOON +1 HEVC", "TeleToonPlus.fr", "|EU| FRANCE GÉNÉRAL"),
        )
        apart(ch(1, "MT - TVM 1", "TVM.mt", "|EU| MALTA"), ch(1, "MT - TVM SPORT+", "TVM.mt", "|EU| MALTA"))
        apart(ch(1, "TR - POWER TV 4K", "PowerTV.tr", "|TR| ULUSAL"), ch(1, "TR - POWER TURK", "PowerTV.tr", "|TR| ULUSAL"))
        apart(
            ch(1, "ES - TELEVISIÓN DE GALICIA AMÉRICA", "TVG.es", "|ES| AUTONOMICAS"),
            ch(1, "ES - TELEVISIÓN DE GALICIA EUROPA", "TVG.es", "|ES| AUTONOMICAS"),
        )
    }

    @Test
    fun spellingVariantsOfTheSameChannelStayTogether() {
        together(
            ch(1, "FR - 13ÈME RUE HEVC", "13Rue.fr", "|EU| FRANCE HEVC"),
            ch(2, "FR - 13 EME RUE", "13Rue.fr", "|EU| FRANCE GÉNÉRAL"),
            ch(3, "FR - 13EME RUE", "", "|EU| FRANCE GÉNÉRAL"),
        )
        together(ch(1, "FR - TÉVA HEVC", "Teva.fr", "|EU| FRANCE HEVC"), ch(2, "FR - TEVA 4K", "Teva.fr", "|EU| FRANCE 4K"))
        together(
            ch(1, "FR - COMEDY CENTRAL FRANCE HEVC", "ComedyCentral.fr", "|EU| FRANCE HEVC"),
            ch(2, "FR - COMEDY CENTRAL FHD", "ComedyCentral.fr", "|EU| FRANCE GÉNÉRAL"),
        )
        together(ch(1, "FR - USHUAIA FHD", "UshuaiaTV.fr", "|EU| FRANCE GÉNÉRAL"), ch(2, "FR - USHUAIA TV HD", "", "|EU| FRANCE GÉNÉRAL"))
        together(ch(1, "AR - AL JAZEERA LQ", "AlJazeera.qa", "|AR| NEWS"), ch(2, "AR - AL JAZERA HD", "AlJazeera.qa", "|AR| NEWS"))
        together(
            ch(1, "PSL - STAR SPORTS 1 HINDI HD", "StarSports1Hindi.in", "|AS| INDIA"),
            ch(2, "PSL - STAR SPORTS HINDI 1", "StarSports1Hindi.in", "|AS| INDIA"),
        )
        together(
            ch(1, "DE - SKY CINEMA PREMIEREN HD", "SkyCinemaPremieren.de", "|DE| SKY"),
            ch(2, "DE - SKY CINEMA PREMIERE 4K", "SkyCinemaPremieren.de", "|DE| 4K+ PAKET"),
        )
        together(ch(1, "LATAM: TNT Sports 2 HD", "TNTSports2.cl", "LATAM | TV"), ch(2, "|LATAM| TNT Sports 2", "", "LATAM | TV"))
    }

    @Test
    fun brandWithUhdIsItsOwnChannel() {
        // Gemeldet: „TRT 4K ist ein eingestellter Sender, er hat nichts mit TRT 1 oder TRT 2 zu tun.“
        // „TRT“ allein ist kein Sender; nur das Entfernen von „4K/UHD“ machte daraus TRT 1.
        apart(ch(1, "|4K| TRT UHD", "", "|4K|"), ch(1, "TR - TRT 1 UHD", "TRT1.tr", "|TR| ULUSAL"))
        apart(ch(1, "TR: TRT 4K", "TRT4K.tr", "TR | Ulusal"), ch(2, "TR: TRT 1 HD", "TRT1.tr", "TR | Ulusal"), ch(2, "TR: TRT 2 HD", "TRT2.tr", "TR | Ulusal"))
        // Echte Qualitätsvarianten bleiben zusammen.
        together(ch(1, "TR - TRT 1 UHD", "TRT1.tr", "|TR| ULUSAL"), ch(2, "TR: TRT 1 HD", "TRT1.tr", "TR | Ulusal"))
        together(ch(1, "DE: ZDF UHD", "ZDF.de", "DE | Vollprogramm"), ch(2, "DE: ZDF HD", "ZDF.de", "DE | Vollprogramm"))
        together(ch(1, "DE - PROSIEBEN UHD", "Pro7.de", "|DE| ALLGEMEINES"), ch(2, "DE: ProSieben HD", "Pro7.de", "DE | Vollprogramm"))
    }
}
