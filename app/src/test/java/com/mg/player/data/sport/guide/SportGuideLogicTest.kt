package com.mg.player.data.sport.guide

import com.mg.player.data.db.dao.ChannelIndexRow
import com.mg.player.domain.model.EpgProgram
import com.mg.player.domain.model.EventStatus
import com.mg.player.domain.model.SportEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Sender über das öffentliche Sport-Programm finden – mit Schreibweisen aus den echten Programmdateien. */
class SportGuideLogicTest {

    private val min = 60_000L
    private val kickoff = 1_793_200_000_000L

    private fun game(home: String, away: String, league: String = "Premier League", start: Long = kickoff, id: String = "espn:soccer/eng.1#1") =
        SportEvent(id, "soccer", league, "$home – $away", home, away, start, null, EventStatus.PRE, emptyList())

    private fun program(channel: String, start: Long, title: String, desc: String = "") = GuideProgram(channel, start, start + 120 * min, title, "$title $desc")

    private val channels = mapOf(
        "Sky.Sport.Premier.League.de" to GuideChannel("Sky.Sport.Premier.League.de", "Sky Sport Premier League", "de"),
        "Beinsports.tr" to GuideChannel("Beinsports.tr", "beIN SPORTS 1", "tr"),
        "Sky.Sport.Top.Event.de" to GuideChannel("Sky.Sport.Top.Event.de", "Sky Sport Top Event", "de"),
        "DAZN.1.de" to GuideChannel("DAZN.1.de", "DAZN 1", "de"),
    )

    @Test
    fun nameKeysIgnoreCountryQualityAndFancyLetters() {
        val key = GuideCatalog.nameKey("DE| SKY SPORT BUNDESLIGA 1 UHD")
        assertEquals("skysportbundesliga1", key)
        assertEquals(key, GuideCatalog.idKey("Sky.Sport.Bundesliga.1.de"))
        assertEquals(key, GuideCatalog.nameKey("ᴅᴇ ┃ ꜱᴋʏ ꜱᴘᴏʀᴛ ʙᴜɴᴅᴇꜱʟɪɢᴀ 1 ʜᴅ"))
        assertEquals("beinsports1", GuideCatalog.nameKey("TR| BEIN SPORTS 1 4K"))
        assertEquals("beinsports1", GuideCatalog.idKey("beIN.SPORTS.1.tr"))
        assertEquals("de", GuideCatalog.countryOfName("DE: ZDF HD"))
        assertEquals("tr", GuideCatalog.countryOfName("[TR] TRT Spor"))
        assertEquals("uk", GuideCatalog.countryOfName("UK| Sky Sports Main Event"))
        assertNull(GuideCatalog.countryOfName("BBC: One"))
        assertNull(GuideCatalog.countryOfName("TV8 HD"))
        assertEquals("tr", GuideCatalog.countryOfId("TRT.SPOR.HD.tr"))
    }

    @Test
    fun countriesFollowTheOwnSportChannels() {
        assertEquals(listOf("de", "tr"), GuideCatalog.countriesToLoad(listOf("de", "de", "tr", "de", "xx", "tr")))
        assertEquals(listOf("TR1", "TR3"), GuideCatalog.filesFor("tr"))
        assertTrue(GuideCatalog.isSportChannel("Sky.Sport.Premier.League.de", emptyList()))
        assertTrue(GuideCatalog.isSportChannel("TRT.SPOR.HD.tr", emptyList()))
        assertTrue(!GuideCatalog.isSportChannel("ZDF.de", listOf("ZDF")))
        assertTrue(GuideCatalog.isSportProgram("Fußball: Länderspiel – Deutschland – Türkei"))
    }

    @Test
    fun indexFindsOwnChannelByTvgIdNameAndCountry() {
        val index = SportChannelIndex(
            listOf(
                ChannelIndexRow("1:L:1", "DE| SKY SPORT PREMIER LEAGUE UHD", 1, ""),
                ChannelIndexRow("1:L:2", "DE| SKY SPORT PREMIER LEAGUE HD", 1, ""),
                ChannelIndexRow("1:L:3", "TR| BEIN SPORTS 1 4K", 1, ""),
                ChannelIndexRow("1:L:4", "CH| Sky Sport 1 HD", 1, ""),
                ChannelIndexRow("1:L:5", "Some Name", 1, "DAZN.1.de"),
                ChannelIndexRow("1:L:6", "DE| SKY BUNDESLIGA 3", 1, ""),
                ChannelIndexRow("2:L:7", "Sky Sport Premier League", 2, ""),
            ),
        )
        // Je Zugang der beste (erste) Sender.
        assertEquals(listOf("1:L:1", "2:L:7"), index.forGuide(channels.getValue("Sky.Sport.Premier.League.de")).map { it.id })
        assertEquals(listOf("1:L:5"), index.forGuide(channels.getValue("DAZN.1.de")).map { it.id })
        assertEquals(listOf("1:L:3"), index.forGuide(GuideChannel("beIN.SPORTS.1.tr", "beIN SPORTS 1", "tr")).map { it.id })
        // Sky Sport 1 aus Deutschland ist nicht der Schweizer Sender.
        assertTrue(index.forGuide(GuideChannel("Sky.Sport.1.de", "Sky Sport 1", "de")).isEmpty())
        // Locker: „Sky Bundesliga 3“ = „Sky Sport Bundesliga 3“ (eindeutig).
        assertEquals(listOf("1:L:6"), index.forGuide(GuideChannel("Sky.Sport.Bundesliga.3.de", "Sky Sport Bundesliga 3", "de")).map { it.id })
        assertEquals(listOf("1:L:3"), index.forStation("beIN Sports 1").map { it.id })
    }

    @Test
    fun eventChannelsNameTheGame() {
        val index = SportChannelIndex(
            listOf(
                ChannelIndexRow("1:L:1", "DE| DAZN EVENT 3: Galatasaray – Fenerbahçe 20:00", 1, ""),
                ChannelIndexRow("1:L:2", "TR| Galatasaray TV", 1, ""),
                ChannelIndexRow("1:L:3", "PPV 2: UFC Fight Night Moicano vs Nolan", 1, ""),
            ),
        )
        assertEquals(listOf("1:L:1"), index.eventChannels(listOf(setOf("galatasaray"), setOf("fenerbahce"))).map { it.id })
        assertEquals(listOf("1:L:3"), index.eventChannels(listOf(setOf("ufc"), setOf("moicano", "nolan"))).map { it.id })
        assertTrue(index.eventChannels(listOf(setOf("galatasaray"), setOf("besiktas"))).isEmpty())
    }

    @Test
    fun gameIsFoundAtKickoffButNotInReplaysOrHighlights() {
        val guide = SportGuide(
            channels,
            listOf(
                // Echte Schreibweise (Sky DE): Liga im Titel, Paarung im Untertitel.
                program("Sky.Sport.Premier.League.de", kickoff - 30 * min, "Fußball: Premier League – FC Liverpool – Brighton & Hove Albion, 8. Spieltag"),
                program("Sky.Sport.Premier.League.de", kickoff + 26 * 60 * min, "Fußball: Premier League – FC Liverpool – Brighton & Hove Albion, 8. Spieltag"),
                program("Sky.Sport.Top.Event.de", kickoff, "Fußball: League Cup – Highlights XXL: FC Liverpool – FC Brighton"),
            ),
        )
        val hits = GuideMatching.find(game("Liverpool", "Brighton & Hove Albion"), guide)
        assertEquals(listOf("Sky.Sport.Premier.League.de"), hits.map { it.program.channelId })
        assertEquals(GuideMatching.BOTH_TEAMS, hits.single().score)
        assertTrue(GuideMatching.find(game("Chelsea", "Arsenal"), guide).isEmpty())
    }

    @Test
    fun newsChannelsRankBelowTheMatchChannel() {
        val ch = channels + ("Sky.Sport.News.de" to GuideChannel("Sky.Sport.News.de", "Sky Sport News", "de"))
        val title = "Fußball: Bundesliga – SV 07 Elversberg – 1. FSV Mainz 05"
        val guide =
            SportGuide(ch, listOf(program("Sky.Sport.Top.Event.de", kickoff - 65 * min, title), program("Sky.Sport.News.de", kickoff - 65 * min, title)))
        val hits = GuideMatching.find(game("SV Elversberg", "Mainz", "Bundesliga"), guide).sortedByDescending { it.score }
        assertEquals("Sky.Sport.Top.Event.de", hits.first().program.channelId)
        assertTrue(hits.last().score < GuideMatching.BOTH_TEAMS)
    }

    @Test
    fun formulaOneNeedsTheRightGrandPrix() {
        val guide = SportGuide(
            channels,
            listOf(
                program("Beinsports.tr", kickoff, "Formula 1 Azerbaycan GP"),
                program("Sky.Sport.Top.Event.de", kickoff + 10 * min, "Formel 1 – Warm Up – Das Motorsport Spezial"),
            ),
        )
        val f1 = SportEvent("espn:racing/f1#1", "racing", "Formel 1", "Mexico City Grand Prix", null, null, kickoff, null, EventStatus.PRE, emptyList())
        assertTrue(GuideMatching.find(f1, guide).isEmpty())
    }

    @Test
    fun turkishShortNamesMatch() {
        val guide =
            SportGuide(
                channels,
                listOf(
                    program("Beinsports.tr", kickoff, "Ç. Rizespor - Trabzonspor"),
                    program("Beinsports.tr", kickoff + 3 * 60 * min, "Konyaspor - Galatasaray"),
                ),
            )
        assertEquals(1, GuideMatching.find(game("Caykur Rizespor", "Trabzonspor", "Süper Lig"), guide).size)
        assertTrue(GuideMatching.find(game("Konyaspor", "Galatasaray", "Süper Lig"), guide).isEmpty()) // 3 h später: Wiederholung
    }

    @Test
    fun seriesUseKeywordPlusFightersOrSession() {
        val guide = SportGuide(
            channels,
            listOf(
                program("DAZN.1.de", kickoff + 60 * min, "UFC Fight Night: Moicano vs. Nolan", "Hauptkarte live"),
                program("Sky.Sport.Top.Event.de", kickoff - 20 * min, "Formel 1: Großer Preis von Mexiko – 2. Freies Training"),
            ),
        )
        val ufc = SportEvent("espn:mma/ufc#1", "mma", "UFC", "UFC Fight Night: Moicano vs. Nolan", null, null, kickoff, null, EventStatus.PRE, emptyList())
        assertEquals(GuideMatching.SERIES_SPECIFIC + 2, GuideMatching.find(ufc, guide).single().score) // „live“ in der Beschreibung
        val f1 = SportEvent("espn:racing/f1#1", "racing", "Formel 1", "Mexico City Grand Prix", null, null, kickoff, null, EventStatus.PRE, emptyList())
        assertEquals("Sky.Sport.Top.Event.de", GuideMatching.find(f1, guide).single().program.channelId)
    }

    @Test
    fun seriesFromTheGuideCountFirstAiringsAndLiveOnes() {
        val ch = mapOf("Sky.Sport.1.de" to GuideChannel("Sky.Sport.1.de", "Sky Sport 1", "de"), "Sport1.de" to GuideChannel("Sport1.de", "SPORT1", "de"))
        val guide = SportGuide(
            ch,
            listOf(
                program("Sport1.de", kickoff, "Darts: PDC World Series – Finale"),
                program("Sport1.de", kickoff + 20 * 60 * min, "Darts: PDC World Series – Finale"), // Wiederholung
                program("Sport1.de", kickoff + 22 * 60 * min, "Darts: Highlights der Woche"),
                program("Sky.Sport.1.de", kickoff + 30 * 60 * min, "Live: MotoGP"),
                program("Sky.Sport.1.de", kickoff + 54 * 60 * min, "Live: MotoGP"),
            ),
        )
        val darts = GuideSeries.events(GuideSeries.SERIES.first { it.slug == "darts" }, guide, kickoff - min, kickoff + 4 * 24 * 60 * min)
        assertEquals(listOf("Darts: PDC World Series – Finale"), darts.map { it.title })
        assertEquals(listOf("SPORT1"), darts.single().stations)
        val moto = GuideSeries.events(GuideSeries.SERIES.first { it.slug == "motogp" }, guide, kickoff - min, kickoff + 4 * 24 * 60 * min)
        assertEquals(2, moto.size)
        assertEquals("MotoGP", moto.first().title)
        // Das Event findet genau seine Sendung wieder.
        assertEquals("Sky.Sport.1.de", GuideMatching.find(moto.first(), guide).single().program.channelId)
    }

    @Test
    fun kickoffCheckConfirmsOrDemotes() {
        val g = game("Liverpool", "Brighton & Hove Albion")
        fun p(title: String, start: Long = kickoff - 10 * min) = EpgProgram(title, null, start, start + 120 * min)
        assertEquals(KickoffCheck.Verdict.CONFIRMED, KickoffCheck.verdict(g, listOf(p("Premier League: Liverpool - Brighton"))))
        assertEquals(KickoffCheck.Verdict.OTHER_MATCH, KickoffCheck.verdict(g, listOf(p("Premier League: Chelsea - Arsenal"))))
        assertEquals(KickoffCheck.Verdict.UNKNOWN, KickoffCheck.verdict(g, listOf(p("Live Event"))))
        assertEquals(KickoffCheck.Verdict.UNKNOWN, KickoffCheck.verdict(g, listOf(p("Liverpool - Brighton", start = kickoff + 4 * 60 * min))))
    }
}
