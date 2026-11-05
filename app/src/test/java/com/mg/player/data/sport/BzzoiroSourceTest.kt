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
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** Bzzoiro Sports Data gegen Antworten im Format der OpenAPI-Beschreibung (kein Internet im Test). */
class BzzoiroSourceTest {

    private val server = MockWebServer()
    private lateinit var http: HttpFetcher
    private val requests = mutableListOf<RecordedRequest>()

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
        SportEndpoints.bzzoiro = server.url("/api/v2").toString().trimEnd('/')
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                requests += request
                if (request.getHeader("Authorization") != "Token geheim") return MockResponse().setResponseCode(401)
                val path = request.path.orEmpty()
                return MockResponse().setBody(
                    when {
                        path.startsWith("/api/v2/leagues/") -> LEAGUES
                        path.startsWith("/api/v2/events/") -> EVENTS
                        path.startsWith("/api/v2/broadcasts/") && "country_code=DE" in path -> BROADCASTS_DE
                        path.startsWith("/api/v2/broadcasts/") -> EMPTY
                        else -> EMPTY
                    },
                )
            }
        }
    }

    @After
    fun tearDown() = server.shutdown()

    @Test
    fun eventsComeWithGermanTvStationsAndTheKeyOnlyInTheHeader() = runBlocking {
        val source = BzzoiroSource(http) { "geheim" }
        val from = BzzoiroSource.parseTime("2026-10-30T00:00:00Z")!!
        val events = source.fetch("bsd:8", from, from + 7 * 86_400_000L)
        assertEquals(2, events.size)
        val match = events.first { it.id == "bsd:8#1001" }
        assertEquals("Bayern München – Borussia Dortmund", match.title)
        assertEquals("Bundesliga", match.league)
        assertEquals(EventStatus.PRE, match.status)
        assertEquals(listOf("DE| Sky Sport Bundesliga 1", "DE| DAZN 1"), match.stations)
        assertEquals(EventStatus.POST, events.first { it.id == "bsd:8#1002" }.status) // verschoben
        // Fremde „next“-Adresse wird nicht aufgerufen (der Schlüssel bliebe sonst nicht beim Dienst).
        assertTrue(requests.none { it.path.orEmpty().contains("evil") })
        assertTrue(requests.all { "geheim" !in it.path.orEmpty() })
    }

    @Test
    fun withoutKeyNothingIsRequested() = runBlocking {
        val source = BzzoiroSource(http) { "" }
        assertTrue(source.fetch("bsd:8", 0, Long.MAX_VALUE).isEmpty())
        assertTrue(source.leagues().isEmpty())
        assertEquals(0, requests.size)
    }

    @Test
    fun leaguesCarryCountryAndTimesParse() = runBlocking {
        val leagues = BzzoiroSource(http) { "geheim" }.leagues()
        assertEquals(listOf("Bundesliga (Germany)", "Süper Lig (Turkey)"), leagues.map { it.name })
        assertEquals("bsd:8", leagues.first().key)
        assertEquals(BzzoiroSource.parseTime("2026-10-31T14:30:00Z"), BzzoiroSource.parseTime("2026-10-31T15:30:00.000+01:00"))
        assertEquals(EventStatus.IN, BzzoiroSource.status("2nd_half"))
    }

    /** Aufbau wie die echten Antworten (DFB-Pokal, 2. Runde 2026): Apps, Sammelsender und der eigentliche Sender. */
    @Test
    fun appsAndSharedChannelsAreDropped() {
        val t17 = BzzoiroSource.parseTime("2026-10-27T17:00:00+00:00")
        val t19 = BzzoiroSource.parseTime("2026-10-27T19:45:00+00:00")
        fun b(event: Long, name: String, t: Long?) = BzzoiroSource.Broadcast(event, name, t)
        val shared = listOf("Sky Sport Top Event", "Sky Sport Mix", "OneFootball Deutschland", "RTL+", "Sky Go", "WOW")
        val rows = buildList {
            listOf(1L to "Sky Sport 3", 2L to "Sky Sport 6", 3L to "Sky Sport 5", 4L to "Sky Sport 4").forEach { (id, ch) ->
                add(b(id, ch, t17))
                add(b(id, ch.replace("Sport ", "Sport HD "), t17))
                shared.forEach { add(b(id, it, t17)) }
            }
            add(b(1, "RTL", t17))
            add(b(1, "RTL", t17))
            add(b(1, "Sky Sport News", t17))
            // Abendspiel im Free-TV: Das Erste samt Mediathek.
            add(b(5, "Das Erste", t19))
            add(b(5, "ARD-Mediathek", t19))
            add(b(5, "sportschau.de", t19))
            add(b(5, "Sky Sport 2", t19))
            add(b(5, "Sky Sport Top Event", t19))
            add(b(6, "DAZN", null))
        }
        val clean = BzzoiroSource.cleanStations(rows)
        assertEquals(listOf("Sky Sport 3", "Sky Sport HD 3", "RTL"), clean[1])
        assertEquals(listOf("Sky Sport 6", "Sky Sport HD 6"), clean[2])
        // Allein am Abend: „Top Event“ ist dort kein Sammelsender.
        assertEquals(listOf("Das Erste", "Sky Sport 2", "Sky Sport Top Event"), clean[5])
        assertEquals(listOf("DAZN"), clean[6])
    }

    private companion object {
        const val EMPTY = """{"count": 0, "next": null, "previous": null, "results": []}"""
        const val LEAGUES = """{"count": 2, "next": null, "previous": null, "results": [
            {"id": 8, "name": "Bundesliga", "country": "Germany", "is_women": false, "is_active": true, "current_season": null},
            {"id": 52, "name": "Süper Lig", "country": "Turkey", "is_women": false, "is_active": true, "current_season": null}]}"""
        const val EVENTS = """{"count": 2, "next": "https://evil.example/api/v2/events/?offset=200", "previous": null, "results": [
            {"id": 1001, "league_id": 8, "home_team": "Bayern München", "away_team": "Borussia Dortmund",
             "event_date": "2026-10-31T17:30:00+00:00", "status": "notstarted"},
            {"id": 1002, "league_id": 8, "home_team": "SC Freiburg", "away_team": "FC Augsburg",
             "event_date": "2026-11-01T14:30:00Z", "status": "postponed"}]}"""
        const val BROADCASTS_DE = """{"count": 2, "next": null, "previous": null, "results": [
            {"id": 1, "event_id": 1001, "country_code": "DE", "channel_id": 5, "channel_name": "Sky Sport Bundesliga 1"},
            {"id": 2, "event_id": 1001, "country_code": "DE", "channel_id": 6, "channel_name": "DAZN 1"}]}"""
    }
}
