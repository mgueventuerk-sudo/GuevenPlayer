package com.mg.player.data.sport

import com.mg.player.core.network.HttpFetcher
import com.mg.player.domain.model.EventStatus
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** Quellen gegen gespeicherte Antwortbeispiele (kein Internet im Test). */
class SportSourcesTest {

    private val server = MockWebServer()
    private lateinit var http: HttpFetcher
    private val day = 24 * 3_600_000L
    private val now = 1_793_000_000_000L // 2026-10-26

    @Before
    fun setUp() {
        val certificate = HeldCertificate.Builder()
            .addSubjectAlternativeName("localhost")
            .addSubjectAlternativeName("127.0.0.1")
            .build()
        val serverCertificates = HandshakeCertificates.Builder().heldCertificate(certificate).build()
        val clientCertificates = HandshakeCertificates.Builder().addTrustedCertificate(certificate.certificate).build()
        server.useHttps(serverCertificates.sslSocketFactory(), false)
        http = HttpFetcher(
            OkHttpClient.Builder()
                .sslSocketFactory(clientCertificates.sslSocketFactory(), clientCertificates.trustManager)
                .build(),
        )
        server.start()
        SportEndpoints.espn = server.url("/espn").toString().trimEnd('/')
        SportEndpoints.sportmonks = server.url("/v3/football").toString().trimEnd('/')
    }

    @After
    fun tearDown() = server.shutdown()

    private fun serve(handler: (RecordedRequest) -> MockResponse) {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest) = handler(request)
        }
    }

    @Test
    fun espnParsesTeamsStatusAndStations() = runBlocking {
        val body = EspnJson.scoreboard(
            EspnJson.event("401", now + day, "Bayern Munich", "Borussia Dortmund", stations = listOf("ESPN+", "Sport1")),
            EspnJson.event("402", now - 3_600_000, "RB Leipzig", "Mainz", state = "in"),
            EspnJson.event("403", now - day, "SC Freiburg", "FC Augsburg", state = "post"),
        )
        serve { MockResponse().setBody(body) }
        val events = EspnSource(http).fetch("espn:soccer/ger.1", now - 2 * day, now + 7 * day)
        assertEquals(3, events.size)
        val first = events.first { it.id == "espn:soccer/ger.1#401" }
        assertEquals("Bayern Munich – Borussia Dortmund", first.title)
        assertEquals("Bayern Munich", first.homeTeam)
        assertEquals(listOf("ESPN+", "Sport1"), first.stations)
        assertEquals("Bundesliga", first.league)
        assertEquals(EventStatus.IN, events.first { it.id.endsWith("#402") }.status)
        assertEquals(EventStatus.POST, events.first { it.id.endsWith("#403") }.status)
        assertEquals("espn:soccer/ger.1", leagueOfEvent(first.id))
        // Ein Abruf für den ganzen Zeitraum.
        assertTrue(server.takeRequest().path!!.contains("dates=20261024-20261102"))
    }

    @Test
    fun espnFallsBackToMatchDaysWhenRangeIsRejected() = runBlocking {
        val matchDay = now + 2 * day
        serve { request ->
            val dates = request.requestUrl?.queryParameter("dates")
            when {
                dates == null -> MockResponse().setBody(EspnJson.scoreboard(calendar = listOf(now - 30 * day, matchDay, now + 40 * day)))
                "-" in dates -> MockResponse().setResponseCode(400)
                else -> MockResponse().setBody(EspnJson.scoreboard(EspnJson.event("9", matchDay, "Hamburg SV", "Hertha Berlin")))
            }
        }
        val events = EspnSource(http).fetch("espn:soccer/ger.2", now, now + 7 * day)
        assertEquals(listOf("espn:soccer/ger.2#9"), events.map { it.id })
        // Nur der Spieltag im Zeitraum wurde zusätzlich abgefragt (nicht die Spieltage davor/danach).
        assertEquals(3, server.requestCount)
    }

    @Test
    fun unknownLeagueFetchesNothing() = runBlocking {
        serve { MockResponse().setResponseCode(500) }
        assertTrue(EspnSource(http).fetch("espn:soccer/xyz.9", now, now + day).isEmpty())
        assertFalse(EspnSource(http).handles("sm:271"))
        assertEquals(0, server.requestCount)
    }

    @Test
    fun sportmonksUsesUserTokenAndParsesFixtures() = runBlocking {
        val fixtures = JSONObject(
            """
            {"data":[
              {"id":19134,"league_id":271,"name":"Brøndby vs FC København","starting_at":"2026-10-27 17:00:00",
               "starting_at_timestamp":1793120400,"length":90,"state_id":1,
               "participants":[{"name":"FC København","meta":{"location":"away"}},{"name":"Brøndby","meta":{"location":"home"}}]},
              {"id":19135,"league_id":501,"name":"Other league","starting_at_timestamp":1793120400,"state_id":5,"participants":[]}
            ],"pagination":{"has_more":true,"next_page":"https://evil.example/steal?page=2"}}
            """.trimIndent(),
        ).toString()
        serve { request ->
            when {
                request.path!!.contains("/leagues") -> MockResponse().setBody("""{"data":[{"id":271,"name":"Superliga"},{"id":501,"name":"Premiership"}]}""")
                else -> MockResponse().setBody(fixtures)
            }
        }
        val source = SportmonksSource(http) { "geheim" }
        val events = source.fetch("sm:271", now, now + 7 * day)
        assertEquals(listOf("sm:271#19134"), events.map { it.id })
        val e = events.single()
        assertEquals("Brøndby – FC København", e.title)
        assertEquals("Superliga", e.league)
        assertEquals(1_793_120_400_000L + 120 * 60_000L, e.endMs)
        assertEquals(EventStatus.PRE, e.status)
        // Schlüssel geht nur an Sportmonks – die fremde „nächste Seite“ wird nicht aufgerufen.
        val requests = (0 until server.requestCount).map { server.takeRequest() }
        // Schlüssel im Kopf, nie in der Adresse (Server-/Proxy-Protokolle).
        assertTrue(requests.all { it.getHeader("Authorization") == "geheim" && "geheim" !in it.path!! })
        assertEquals(2, server.requestCount)
    }

    @Test
    fun sportmonksNextPageLosesToken() {
        assertEquals("https://api.sportmonks.com/v3/x?page=2", SportmonksSource.withoutToken("https://api.sportmonks.com/v3/x?api_token=k&page=2"))
        assertEquals("https://api.sportmonks.com/v3/x?page=2", SportmonksSource.withoutToken("https://api.sportmonks.com/v3/x?page=2&api_token=k"))
        assertEquals("https://api.sportmonks.com/v3/x", SportmonksSource.withoutToken("https://api.sportmonks.com/v3/x?api_token=k"))
    }

    @Test
    fun sportmonksWithoutTokenStaysOffline() = runBlocking {
        serve { MockResponse().setResponseCode(500) }
        val source = SportmonksSource(http) { "" }
        assertTrue(source.fetch("sm:271", now, now + day).isEmpty())
        assertTrue(source.leagues().isEmpty())
        assertEquals(0, server.requestCount)
    }

    @Test
    fun sportmonksStates() {
        assertEquals(EventStatus.PRE, SportmonksSource.status(1))
        assertEquals(EventStatus.IN, SportmonksSource.status(2))
        assertEquals(EventStatus.POST, SportmonksSource.status(5))
        assertEquals(EventStatus.UNKNOWN, SportmonksSource.status(99))
    }
}
