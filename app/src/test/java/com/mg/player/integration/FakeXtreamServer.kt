package com.mg.player.integration

import com.mg.player.data.sport.SportEndpoints
import com.mg.player.data.sport.guide.GuideCatalog
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.json.JSONArray
import org.json.JSONObject

/**
 * Nachgebauter Xtream-Server (player_api.php) mit Namen, wie echte Anbieter sie liefern:
 * Länderkürzel vorne, Qualitäts-Zusätze, Sonderzeichen.
 */
class FakeXtreamServer(private val extraLive: Int = 0, private val extraMovies: Int = 0, private val extraSeries: Int = 0) {
    val server = MockWebServer()

    /** Belegte und erlaubte Verbindungen, wie sie user_info meldet. */
    @Volatile var activeConnections = 0

    @Volatile var maxConnections = 1

    /** Öffentliches Sport-Programm (XMLTV) für die Tests der Senderfindung; null = Datei fehlt (404). */
    @Volatile var guideXml: String? = null

    /** Panel, das category_id übergeht und immer alle Sender schickt (gibt es wirklich). */
    @Volatile var ignoresCategoryFilter = false

    @Volatile var liveCategories = listOf(
        "1" to "DE | VOLLPROGRAMM",
        "2" to "DE | NACHRICHTEN",
        "3" to "TR | ULUSAL",
        "4" to "UK | ENTERTAINMENT",
        "5" to "ᴅᴇ ┃ ꜱᴘᴏʀᴛ",
    )
    val vodCategories = listOf("10" to "DE | ACTION", "11" to "DE | KOMÖDIE", "12" to "EN | ACTION")
    val seriesCategories = listOf("20" to "DE | SERIEN", "21" to "EN | SERIES")

    @Volatile var live = listOf(
        Triple("101", "DE: ZDF HD", "1"), Triple("102", "DE: ZDF FHD", "1"), Triple("103", "DE: ZDF ᵁᴴᴰ", "1"),
        Triple("104", "DE: Das Erste HD", "1"), Triple("105", "|DE| RTL", "1"), Triple("106", "DE ▎SAT.1 HD", "1"),
        Triple("107", "DE: ZDFneo", "1"), Triple("108", "DE: n-tv", "2"), Triple("109", "DE: WELT", "2"),
        Triple("110", "DE: tagesschau24", "2"), Triple("201", "TR: TRT 1", "3"), Triple("202", "TR: Show TV", "3"),
        Triple("301", "UK: BBC One HD", "4"), Triple("111", "DE: 𝗣𝗿𝗼𝗦𝗶𝗲𝗯𝗲𝗻 HD", "1"),
        Triple("112", "ᴅᴇ ┃ ꜱᴋʏ ꜱᴘᴏʀᴛ ʙᴜɴᴅᴇꜱʟɪɢᴀ ʜᴅ", "5"),
    )
    val movies = listOf(
        Triple("501", "DE - Inception (2010)", "10"),
        Triple("502", "Der Pate [4K]", "10"),
        Triple("503", "Ziemlich beste Freunde", "11"),
        Triple("504", "Die Känguru-Chroniken (2020)", "11"),
        Triple("505", "EN - Heat (1995)", "12"),
    )
    val series = listOf(Triple("701", "Dark", "20"), Triple("702", "Babylon Berlin", "20"), Triple("703", "Breaking Bad", "21"))

    fun start(): String {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val url = request.requestUrl ?: return MockResponse().setResponseCode(404)
                if (url.encodedPath.startsWith("/espn/")) return MockResponse().setBody("{\"events\":[]}")
                if (url.encodedPath.startsWith("/guide/")) {
                    val xml = guideXml?.takeIf { url.encodedPath.endsWith("DE1.xml.gz") } ?: return MockResponse().setResponseCode(404)
                    val gz = java.io.ByteArrayOutputStream().also { out -> java.util.zip.GZIPOutputStream(out).use { it.write(xml.toByteArray()) } }
                    return MockResponse().setBody(okio.Buffer().write(gz.toByteArray()))
                }
                if (url.encodedPath != "/player_api.php") return MockResponse().setResponseCode(404)
                val body = when (url.queryParameter("action")) {
                    null -> JSONObject()
                        .put(
                            "user_info",
                            JSONObject().put("auth", 1).put("status", "Active")
                                .put("active_cons", activeConnections.toString()).put("max_connections", maxConnections.toString()),
                        )
                        .put("server_info", JSONObject().put("timezone", "Europe/Berlin"))
                        .toString()

                    "get_live_categories" -> categories(liveCategories)

                    "get_vod_categories" -> categories(vodCategories)

                    "get_series_categories" -> categories(seriesCategories)

                    "get_live_streams" -> JSONArray(
                        (live + synthetic(extraLive, 10_000, "DE: Sender", "1")).filter { (_, _, cat) ->
                            val wanted = url.queryParameter("category_id")
                            wanted == null || ignoresCategoryFilter || wanted == cat
                        }.map { (id, name, cat) ->
                            JSONObject().put("stream_id", id.toInt()).put("name", name).put("category_id", cat)
                                .put("stream_icon", "http://logo.invalid/$id.png").put("epg_channel_id", "").put("tv_archive", 0)
                        },
                    ).toString()

                    "get_vod_streams" -> JSONArray(
                        (movies + synthetic(extraMovies, 100_000, "Film Nummer", "10")).map { (id, name, cat) ->
                            JSONObject().put("stream_id", id.toInt()).put("name", name).put("category_id", cat)
                                .put("stream_icon", "http://poster.invalid/$id.jpg").put("rating", "7.5").put("container_extension", "mkv")
                        },
                    ).toString()

                    "get_series" -> JSONArray(
                        (series + synthetic(extraSeries, 300_000, "Serie Folge", "20")).map { (id, name, cat) ->
                            JSONObject().put("series_id", id.toInt()).put("name", name).put("category_id", cat)
                                .put("cover", "http://poster.invalid/s$id.jpg").put("rating", "8.4")
                        },
                    ).toString()

                    else -> "[]"
                }
                return MockResponse().setBody(body).setHeader("Content-Type", "application/json")
            }
        }
        server.start()
        // Sport-Abrufe der App gehen an diesen Server (leere Spielpläne) statt ins Internet.
        SportEndpoints.espn = server.url("/espn").toString().trimEnd('/')
        GuideCatalog.baseUrl = server.url("/guide").toString().trimEnd('/')
        return server.url("/").toString().trimEnd('/')
    }

    /** Viele weitere Einträge für Last-Tests (Namen mit laufender Nummer, damit Suchen gezielt treffen). */
    private fun synthetic(count: Int, firstId: Int, prefix: String, category: String) =
        (0 until count).map { i -> Triple((firstId + i).toString(), "$prefix ${i + 1}", category) }

    private fun categories(list: List<Pair<String, String>>) =
        JSONArray(list.map { (id, name) -> JSONObject().put("category_id", id).put("category_name", name).put("parent_id", 0) }).toString()

    fun shutdown() = server.shutdown()
}
