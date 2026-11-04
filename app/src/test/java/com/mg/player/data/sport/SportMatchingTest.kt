package com.mg.player.data.sport

import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.mg.player.core.PlayerApp
import com.mg.player.domain.model.AppSettings
import com.mg.player.domain.model.ContentType
import com.mg.player.domain.model.EventStatus
import com.mg.player.domain.model.PlaylistType
import com.mg.player.domain.model.SportEvent
import com.mg.player.domain.repository.PlaylistInput
import com.mg.player.domain.repository.SettingsRepository
import com.mg.player.integration.FakeXtreamServer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.update
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** Zuordnung Event → Sender über Sendernamen und EPG, und die Online-Aktualisierung mit Takt und Backoff. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class SportMatchingTest {

    private val xtream = FakeXtreamServer()
    private val espn = MockWebServer()
    private val container get() = ApplicationProvider.getApplicationContext<PlayerApp>().container
    private val hour = 3_600_000L
    private val kickoff = (System.currentTimeMillis() / hour + 3) * hour
    private var pid = 0L
    private lateinit var testSettings: TestSettingsRepository
    private var sportScope: CoroutineScope? = null

    @Before
    fun setUp() = runBlocking {
        pid = container.playlistRepository.addPlaylist(
            PlaylistInput(name = "Test", type = PlaylistType.XTREAM, serverUrl = xtream.start(), username = "u", password = "p", allowInsecureHttp = true),
        )
        container.playlistRepository.refreshContent(pid) {}
        testSettings = TestSettingsRepository(AppSettings(activePlaylistId = pid, sportLeagues = setOf("espn:soccer/ger.1")))
        epg("112", kickoff, "Fußball: Bundesliga – FC Bayern München – Borussia Dortmund")
        epg("101", kickoff, "Fußball: FC Bayern München – 1. FSV Mainz 05")
        epg("104", kickoff + 15 * 60_000, "Formel 1: Großer Preis von Mexiko – Das Rennen")
        espn.start()
        SportEndpoints.espn = espn.url("/espn").toString().trimEnd('/')
    }

    @After
    fun tearDown() {
        xtream.shutdown()
        espn.shutdown()
        sportScope?.cancel()
    }

    /** Die Sport-Flows laufen im App-Scope auf dem Hauptthread – der muss im Test weiterlaufen. */
    private suspend fun waitUntil(condition: () -> Boolean) = withTimeout(10_000) {
        while (!condition()) {
            shadowOf(Looper.getMainLooper()).idle()
            delay(25)
        }
    }

    private suspend fun epg(channel: String, start: Long, title: String) = withContext(Dispatchers.IO) {
        val key = container.database.channelDao().entity("$pid:L:$channel")!!.epgKey
        container.database.openHelper.writableDatabase.execSQL(
            "INSERT INTO epg_programs (playlistId, channelKey, startMs, endMs, title, description) VALUES (?, ?, ?, ?, ?, NULL)",
            arrayOf<Any>(pid, key, start, start + 2 * hour, title),
        )
    }

    private fun sportRepository(): SportRepository {
        val dao = container.database.sportDao()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        sportScope = scope
        return SportRepository(
            dao = dao,
            settings = testSettings,
            sources = listOf(EspnSource(container.httpFetcher)),
            matcher = ChannelEventMatcher(dao),
            scope = scope,
            hasPlaylists = { true },
            contentUpdated = flowOf(0L),
        )
    }

    private class TestSettingsRepository(initial: AppSettings) : SettingsRepository {
        private val value = MutableStateFlow(initial)
        override val settings: StateFlow<AppSettings> = value
        override suspend fun current() = value.value
        override suspend fun update(transform: (AppSettings) -> AppSettings) {
            value.update(transform)
        }
    }

    private fun event(id: String, home: String?, away: String?, start: Long = kickoff, stations: List<String> = emptyList(), sport: String = "soccer") =
        SportEvent("espn:soccer/ger.1#$id", sport, "Bundesliga", "$home – $away", home, away, start, null, EventStatus.PRE, stations)

    private suspend fun matched(vararg events: SportEvent): Map<String, List<String>> =
        ChannelEventMatcher(container.database.sportDao()).match(events.toList())
            .groupBy({ it.eventId.substringAfter('#') }, { it.channelId.substringAfterLast(':') })

    @Test
    fun bothTeamsMustBeInTheEpgTitle() = runBlocking {
        val result = matched(event("dortmund", "Bayern Munich", "Borussia Dortmund"), event("mainz", "Bayern Munich", "Mainz"))
        assertEquals(listOf("112"), result["dortmund"])
        // ZDF gibt es in drei Qualitäten – zugeordnet wird genau eine.
        assertEquals(1, result["mainz"]?.size)
        assertTrue(result["mainz"]!!.single() in listOf("101", "102", "103"))
        // Andere Paarung zur selben Zeit: kein Sender.
        assertTrue(matched(event("x", "Bayern Munich", "Werder Bremen")).isEmpty())
    }

    @Test
    fun stationNameWorksBeyondTheEpgButNotForSimilarNames() = runBlocking {
        val later = kickoff + 3 * 24 * hour
        val result = matched(event("zdf", "Bayern Munich", "Borussia Dortmund", start = later, stations = listOf("ZDF")))
        assertEquals(1, result["zdf"]?.size)
        assertTrue(result["zdf"]!!.single() in listOf("101", "102", "103")) // nicht ZDFneo (107)
    }

    @Test
    fun seriesWithoutTeamsUseTheirKeyword() = runBlocking {
        val f1 = SportEvent("espn:racing/f1#1", "racing", "Formel 1", "Mexico City GP", null, null, kickoff, null, EventStatus.PRE, emptyList())
        assertEquals(mapOf("1" to listOf("104")), matched(f1))
    }

    /** Liga ohne EPG-Treffer: Der von Hand zugeordnete Sender gilt für alle ihre Spiele. */
    @Test
    fun manuallyAssignedChannelCoversTheWholeLeague() = runBlocking {
        val later = kickoff + 3 * 24 * hour
        val events = listOf(event("a", "Union Berlin", "St. Pauli", start = later), event("b", "Freiburg", "Augsburg", start = later))
        val matcher = ChannelEventMatcher(container.database.sportDao())
        assertTrue(matcher.match(events).isEmpty())
        val result = matcher.match(events, manual = mapOf("espn:soccer/ger.1" to "$pid:L:112"))
        assertEquals(setOf("espn:soccer/ger.1#a", "espn:soccer/ger.1#b"), result.map { it.eventId }.toSet())
        assertTrue(result.all { it.channelId == "$pid:L:112" && it.score == ChannelEventMatcher.MANUAL_SCORE })
    }

    @Test
    fun hiddenGroupsAreNeverMatched() = runBlocking {
        val sport = container.categoryRepository.observeAll(pid, ContentType.LIVE).first().first { it.id.endsWith(":5") || it.name.contains("ꜱᴘᴏʀᴛ") }
        container.categoryRepository.save(pid, sport.copy(hidden = true))
        assertTrue(matched(event("dortmund", "Bayern Munich", "Borussia Dortmund")).isEmpty())
    }

    @Test
    fun refreshShowsMatchedEventsAndRespectsLimits() = runBlocking {
        var status = 200
        espn.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = if (status != 200) {
                MockResponse().setResponseCode(status).setHeader("Retry-After", "600")
            } else {
                MockResponse().setBody(
                    EspnJson.scoreboard(
                        EspnJson.event("1", kickoff, "Bayern Munich", "Borussia Dortmund"),
                        EspnJson.event("2", kickoff, "Union Berlin", "St. Pauli"), // läuft auf keinem eigenen Sender
                    ),
                )
            }
        }
        val sport = sportRepository()
        // Wie die Startseite: Übersicht und Sichtbarkeit abonnieren (sie laufen nur mit Abonnenten).
        val watchers = CoroutineScope(Dispatchers.Default).launch {
            launch { sport.board.collect { } }
            sport.visible.collect { }
        }
        sport.refresh(force = true)
        waitUntil { sport.board.value?.isEmpty == false }
        val board = sport.board.value!!
        val upcoming = board.upcoming.values.flatten()
        assertEquals(listOf("espn:soccer/ger.1#1"), upcoming.map { it.event.id })
        assertTrue(upcoming.single().channelId.endsWith(":L:112"))
        waitUntil { sport.visible.value }

        // Innerhalb des Mindestabstands kein neuer Abruf.
        val requests = espn.requestCount
        sport.refresh()
        assertEquals(requests, espn.requestCount)

        // 429: die Liga wartet (Retry-After), auch ein erzwungener Abruf fragt nicht sofort erneut.
        status = 429
        sport.refresh(force = true)
        val after429 = espn.requestCount
        sport.refresh(force = true)
        assertEquals(after429, espn.requestCount)
        assertTrue(sport.problem.value!!.contains("zu viele Anfragen"))

        // Liga abgewählt: ihre Events verschwinden, der Tab wird ausgeblendet.
        testSettings.update { it.copy(sportLeagues = emptySet()) }
        sport.refresh(force = true)
        waitUntil { !sport.visible.value }
        assertFalse(sport.visible.value)
        watchers.cancel()
    }
}
