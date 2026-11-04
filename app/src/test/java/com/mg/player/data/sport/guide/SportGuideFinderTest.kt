package com.mg.player.data.sport.guide

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.mg.player.core.PlayerApp
import com.mg.player.core.network.HttpFetcher
import com.mg.player.data.sport.ChannelEventMatcher
import com.mg.player.domain.model.EventStatus
import com.mg.player.domain.model.PlaylistType
import com.mg.player.domain.model.SportEvent
import com.mg.player.domain.repository.PlaylistInput
import com.mg.player.integration.FakeXtreamServer
import java.io.File
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** Ganzer Weg: öffentliches Programm laden → Spiel finden → Sender im eigenen Zugang (ohne EPG des Anbieters). */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class SportGuideFinderTest {

    private val xtream = FakeXtreamServer()
    private val container get() = ApplicationProvider.getApplicationContext<PlayerApp>().container
    private val hour = 3_600_000L
    private val kickoff = (System.currentTimeMillis() / hour + 26) * hour
    private var pid = 0L
    private lateinit var dir: File
    private val guideHttp = HttpFetcher(OkHttpClient())

    /** FakeXtreamServer ist ein loopback-only HTTP-Server; Produktionsquellen bleiben HTTPS-geschützt. */
    private fun guideStore() = SportGuideStore(dir, open = { url -> guideHttp.openMaybeGzip(url, allowInsecureHttp = true) })

    private fun xmltv(ms: Long) = SimpleDateFormat("yyyyMMddHHmmss Z", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }.format(ms)

    private fun guideXml() = """
        <?xml version="1.0" encoding="UTF-8"?>
        <tv>
          <channel id="Sky.Sport.Bundesliga.de"><display-name>Sky Sport Bundesliga</display-name></channel>
          <channel id="DAZN.1.de"><display-name>DAZN 1</display-name></channel>
          <channel id="ZDF.de"><display-name>ZDF</display-name></channel>
          <programme start="${xmltv(kickoff - hour / 2)}" stop="${xmltv(kickoff + 2 * hour)}" channel="Sky.Sport.Bundesliga.de">
            <title>Fußball: Bundesliga</title><sub-title>FC Augsburg – SC Freiburg, 9. Spieltag</sub-title><desc>Live aus Augsburg.</desc>
          </programme>
          <programme start="${xmltv(kickoff)}" stop="${xmltv(kickoff + 2 * hour)}" channel="DAZN.1.de">
            <title>LaLiga 26/27</title><sub-title>FC Barcelona - Real Madrid</sub-title>
          </programme>
          <programme start="${xmltv(kickoff)}" stop="${xmltv(kickoff + hour)}" channel="ZDF.de"><title>heute journal</title></programme>
        </tv>
    """.trimIndent()

    @Before
    fun setUp() = runBlocking {
        xtream.guideXml = guideXml()
        pid = container.playlistRepository.addPlaylist(
            PlaylistInput(name = "Test", type = PlaylistType.XTREAM, serverUrl = xtream.start(), username = "u", password = "p", allowInsecureHttp = true),
        )
        container.playlistRepository.refreshContent(pid) {}
        dir = File(ApplicationProvider.getApplicationContext<PlayerApp>().cacheDir, "guide-test").apply { deleteRecursively() }
    }

    @After
    fun tearDown() {
        xtream.shutdown()
        dir.deleteRecursively()
    }

    private fun game(id: String, home: String, away: String, league: String) =
        SportEvent("espn:soccer/x#$id", "soccer", league, "$home – $away", home, away, kickoff, null, EventStatus.PRE, emptyList())

    @Test
    fun gameFromTheGuideLandsOnOwnChannelAndOthersGetAHint() = runBlocking {
        val dao = container.database.sportDao()
        val store = guideStore()
        val finder = SportChannelFinder(dao, store, guideEnabled = { true })
        val matcher = ChannelEventMatcher(dao, finder)
        val augsburg = game("aug", "FC Augsburg", "SC Freiburg", "Bundesliga")
        val clasico = game("cla", "Barcelona", "Real Madrid", "LaLiga")

        val result = matcher.match(listOf(augsburg, clasico))
        // „ᴅᴇ ┃ ꜱᴋʏ ꜱᴘᴏʀᴛ ʙᴜɴᴅᴇꜱʟɪɢᴀ ʜᴅ“ (Sender 112) ist laut Programm der Sender des Spiels.
        val aug = result.filter { it.eventId == augsburg.id }
        assertEquals(listOf("$pid:L:112"), aug.map { it.channelId })
        // „Live aus Augsburg“: als live gekennzeichnet → etwas sicherer als nur beide Teams.
        assertEquals(GuideMatching.BOTH_TEAMS + 2, aug.single().score)
        // DAZN 1 gibt es im Zugang nicht: kein Sender, aber der Hinweis, wo es läuft.
        assertTrue(result.none { it.eventId == clasico.id })
        assertEquals(listOf("DAZN 1"), matcher.withBroadcasters(listOf(clasico)).single().stations)

        // Nur Sport landet im Cache; zweiter Abruf kommt aus dem Cache (keine weitere Anfrage).
        val cached = SportGuideStore.readCache(File(dir, "DE1.tsv.gz"))!!
        assertEquals(setOf("Sky.Sport.Bundesliga.de", "DAZN.1.de"), cached.channels.keys)
        val requests = xtream.server.requestCount
        assertEquals(2, store.guide(listOf("de")).programs.size)
        assertEquals(requests, xtream.server.requestCount)
    }

    /** Sender, die keine Sportsender sind (Pokal im Ersten), kommen weiter über die Senderliste – nur gezielt geladen. */
    @Test
    fun nonSportStationIsStillFound() = runBlocking {
        val dao = container.database.sportDao()
        val finder = SportChannelFinder(dao, guideStore(), guideEnabled = { false })
        val matcher = ChannelEventMatcher(dao, finder)
        val pokal = game("pok", "FC Bayern München", "1. FC Magdeburg", "DFB-Pokal").copy(stations = listOf("Das Erste"))
        val result = matcher.match(listOf(pokal))
        assertEquals(listOf("$pid:L:104"), result.map { it.channelId })
        // Der kleine Index enthält nur Sport-/Event-Sender, nicht die ganze Liste.
        assertTrue(finder.index().rows.none { it.id == "$pid:L:104" })
        assertTrue(finder.index().rows.any { it.id == "$pid:L:112" })
    }

    @Test
    fun switchedOffMeansNoDownload() = runBlocking {
        val dao = container.database.sportDao()
        val finder = SportChannelFinder(dao, guideStore(), guideEnabled = { false })
        val before = xtream.server.requestCount
        assertTrue(finder.guide().isEmpty)
        assertEquals(before, xtream.server.requestCount)
    }
}
