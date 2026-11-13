package com.mg.player.benchmark

import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.json.JSONArray
import org.json.JSONObject

/**
 * Xtream-kompatibler Fixture-Server für die Macrobenchmarks (AppMacrobenchmark.kt). Bisher brauchten ALLE fünf
 * Benchmarks eine von Hand eingerichtete Test-Playlist auf dem Gerät - in CI (kein manueller Schritt möglich)
 * scheiterten sie deshalb sofort an der ersten assertNotNull-Prüfung, ohne je eine Zahl zu liefern. Das ist
 * vermutlich der eigentliche, bisher nicht dokumentierte Grund für CLAUDE.md's "Offener Punkt 1".
 *
 * Läuft als MockWebServer IM Benchmark-Prozess, gebunden auf Loopback. Die separate App (eigener Prozess, von
 * MacrobenchmarkRule über startActivityAndWait() gestartet) erreicht ihn trotzdem per echtem HTTP: Loopback ist
 * geräteweit, nicht prozessgebunden - dasselbe Prinzip wie bei TrailerLocalServer (siehe TrailerScreen.kt).
 *
 * Eigene, schlanke Implementierung statt Wiederverwendung von ShowcaseServer (app/src/test/.../ShowcaseServer.kt):
 * Das liegt im JVM-Test-Sourceset von :app und ist von diesem separaten com.android.test-Modul (benchmark)
 * nicht erreichbar, ohne Test-Fixture-Code in den Produktionscode von :app zu verschieben.
 */
internal class BenchmarkFixture {

    private val server = MockWebServer()

    /** Muss exakt zu AppMacrobenchmark.TEST_CHANNEL passen - bestehende Benchmarks suchen diesen Namen. */
    private val testChannelName = "BENCHMARK_TEST_CHANNEL"

    /** liveListScroll() verlangt mindestens 500 Sender in "Alle Sender", um echtes Scrollen zu messen. */
    private val channelCount = 520

    /** Serie mit mehreren Staffeln/Folgen - für einen Benchmark auf PhoneSeriesDetail/SeriesDetailScreen. */
    private val seriesSeasons = 3
    private val episodesPerSeason = 8

    fun start(): String {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val url = request.requestUrl ?: return MockResponse().setResponseCode(404)
                return when (url.encodedPath) {
                    "/player_api.php" -> json(api(url.queryParameter("action"), url.queryParameter("series_id")))
                    "/xmltv.php" -> MockResponse().setBody(xmltv()).setHeader("Content-Type", "application/xml")
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        server.start()
        return server.url("/").toString().trimEnd('/')
    }

    fun shutdown() = server.shutdown()

    private fun json(body: String) = MockResponse().setBody(body).setHeader("Content-Type", "application/json")

    private fun api(action: String?, seriesId: String?): String = when (action) {
        null -> JSONObject()
            .put("user_info", JSONObject().put("auth", 1).put("status", "Active").put("active_cons", "0").put("max_connections", "1"))
            .put("server_info", JSONObject().put("timezone", "Europe/Berlin"))
            .toString()

        "get_live_categories" -> JSONArray().put(JSONObject().put("category_id", "1").put("category_name", "Alle Sender").put("parent_id", 0)).toString()

        "get_vod_categories" -> JSONArray().put(JSONObject().put("category_id", "10").put("category_name", "Filme").put("parent_id", 0)).toString()

        "get_series_categories" -> JSONArray().put(JSONObject().put("category_id", "20").put("category_name", "Serien").put("parent_id", 0)).toString()

        "get_live_streams" -> JSONArray().apply {
            put(JSONObject().put("stream_id", 1).put("name", testChannelName).put("category_id", "1").put("epg_channel_id", "bench1"))
            for (i in 2..channelCount) {
                put(JSONObject().put("stream_id", i).put("name", "Sender $i").put("category_id", "1").put("epg_channel_id", "bench$i"))
            }
        }.toString()

        "get_vod_streams" -> JSONArray()
            .put(JSONObject().put("stream_id", 601).put("name", "Benchmark-Film").put("category_id", "10").put("container_extension", "mp4").put("year", "2024"))
            .toString()

        "get_series" -> JSONArray()
            .put(JSONObject().put("series_id", 701).put("name", "Benchmark-Serie").put("category_id", "20").put("rating", "8.0").put("releaseDate", "2024-01-01"))
            .toString()

        "get_series_info" -> seriesInfo(seriesId?.toIntOrNull() ?: 701)

        "get_vod_info" -> JSONObject().put("info", JSONObject().put("plot", "Testfilm für Macrobenchmarks.").put("duration_secs", 5_400)).toString()

        else -> "[]"
    }

    private fun seriesInfo(id: Int): String {
        val episodes = JSONObject()
        for (season in 1..seriesSeasons) {
            val list = JSONArray()
            for (ep in 1..episodesPerSeason) {
                val epId = id * 1000 + season * 100 + ep
                list.put(
                    JSONObject()
                        .put("id", epId.toString())
                        .put("episode_num", ep)
                        .put("season", season)
                        .put("title", "Folge $season×$ep")
                        .put("container_extension", "mp4")
                        .put("info", JSONObject().put("plot", "Testfolge für Macrobenchmarks.").put("duration_secs", 2_400)),
                )
            }
            episodes.put(season.toString(), list)
        }
        return JSONObject()
            .put("info", JSONObject().put("plot", "Eine Serie nur für Macrobenchmarks."))
            .put("episodes", episodes)
            .toString()
    }

    /** Eine laufende Sendung je Sender, drei Stunden vor bis acht Stunden nach jetzt - reicht für den Guide. */
    private fun xmltv(nowMs: Long = System.currentTimeMillis()): String {
        val format = SimpleDateFormat("yyyyMMddHHmmss Z", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
        val sb = StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<tv>\n")
        sb.append("<channel id=\"bench1\"><display-name>bench1</display-name></channel>\n")
        var t = nowMs - 3 * 3_600_000L
        var i = 0
        while (t < nowMs + 8 * 3_600_000L) {
            val end = t + 30 * 60_000L
            sb.append("<programme start=\"${format.format(t)}\" stop=\"${format.format(end)}\" channel=\"bench1\">")
            sb.append("<title lang=\"de\">Testsendung $i</title></programme>\n")
            t = end
            i++
        }
        return sb.append("</tv>\n").toString()
    }
}
