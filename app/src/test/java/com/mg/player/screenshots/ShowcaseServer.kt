package com.mg.player.screenshots

import com.mg.player.data.sport.EspnJson
import com.mg.player.data.sport.SportEndpoints
import com.mg.player.data.sport.guide.GuideCatalog
import java.net.URLEncoder
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
 * Xtream-Server mit vorzeigbarem Inhalt für die Bildschirmfotos: Sender mit EPG (XMLTV rund um "jetzt"),
 * Filme mit Beschreibung, Serien mit Staffeln. Alle Bilder zeigen auf [FakeArt] (img.invalid).
 */
class ShowcaseServer {
    /** Belegte Verbindungen des Zugangs (1/1 = grüne Anzeige „wird genutzt“; 0 = frei, keine Nachfrage beim Abspielen). */
    @Volatile var activeConnections = 1

    /** Sport-Events für die Bundesliga ausliefern (sonst leere Spielpläne – der Sport-Tab bleibt dann weg). */
    @Volatile var sportEnabled = false

    /** Zusätzlich UFC und Premier League – mehrere Sportarten, dazu Spiele auf Sendern, die der Zugang nicht hat. */
    @Volatile var sportMixed = false

    private val server = MockWebServer()

    data class LiveItem(val id: Int, val name: String, val category: String, val epgId: String, val archive: Boolean = false)
    data class VodItem(val id: Int, val name: String, val category: String, val year: String, val rating: String)

    val liveCategories = listOf("1" to "DE | VOLLPROGRAMM", "2" to "DE | NACHRICHTEN", "3" to "DE | SPORT", "4" to "DE | KINDER", "5" to "TR | ULUSAL")
    val vodCategories = listOf("10" to "DE | ACTION", "11" to "DE | DRAMA", "12" to "DE | KOMÖDIE", "13" to "DE | DOKU")
    val seriesCategories = listOf("20" to "DE | SERIEN", "21" to "DE | KRIMI")

    val live = listOf(
        LiveItem(101, "DE: ZDF HD", "1", "zdf.de", archive = true),
        LiveItem(102, "DE: ZDF FHD", "1", "zdf.de", archive = true),
        LiveItem(103, "DE: Das Erste HD", "1", "daserste.de", archive = true),
        LiveItem(104, "DE: ZDFneo HD", "1", "zdfneo.de"),
        LiveItem(105, "DE: 3sat HD", "1", "3sat.de"),
        LiveItem(106, "DE: arte HD", "1", "arte.de"),
        LiveItem(107, "DE: RTL HD", "1", "rtl.de"),
        LiveItem(108, "DE: SAT.1 HD", "1", "sat1.de"),
        LiveItem(109, "DE: ProSieben HD", "1", "pro7.de"),
        LiveItem(110, "DE: VOX HD", "1", "vox.de"),
        LiveItem(111, "DE: kabel eins", "1", "kabel1.de"),
        LiveItem(201, "DE: tagesschau24", "2", "ts24.de"),
        LiveItem(202, "DE: n-tv HD", "2", "ntv.de"),
        LiveItem(203, "DE: WELT HD", "2", "welt.de"),
        LiveItem(301, "DE: Sport1 HD", "3", "sport1.de"),
        LiveItem(401, "DE: KiKA", "4", "kika.de"),
        LiveItem(501, "TR: TRT 1 HD", "5", "trt1.tr"),
    )

    val movies = listOf(
        VodItem(601, "DE - Nordlicht (2023) [4K]", "11", "2023", "7.9"),
        VodItem(602, "DE - Die letzte Fähre (2022)", "10", "2022", "7.1"),
        VodItem(603, "DE - Sturmhöhe Ost (2021)", "11", "2021", "6.8"),
        VodItem(604, "DE - Code Orion (2024) [4K]", "10", "2024", "7.4"),
        VodItem(605, "DE - Kaltfront (2020)", "10", "2020", "6.5"),
        VodItem(606, "DE - Das Haus am See (2019)", "11", "2019", "7.6"),
        VodItem(607, "DE - Ein Sommer in Lissabon (2023)", "12", "2023", "6.9"),
        VodItem(608, "DE - Ziemlich schräge Nachbarn (2022)", "12", "2022", "6.4"),
        VodItem(609, "DE - Unter dem Eis (2024)", "13", "2024", "8.1"),
        VodItem(610, "DE - Die Stimme der Wale (2021)", "13", "2021", "7.8"),
        VodItem(611, "DE - Schattenspiel (2023)", "10", "2023", "7.0"),
        VodItem(612, "DE - Mondscheinbahn (2018)", "11", "2018", "7.3"),
        VodItem(613, "DE - Grenzland (2022) [4K]", "10", "2022", "7.7"),
        VodItem(614, "DE - Familienfest (2021)", "12", "2021", "6.2"),
        VodItem(615, "DE - Wüstenwind (2020)", "10", "2020", "6.9"),
        VodItem(616, "DE - Die Uhrmacherin (2024)", "11", "2024", "8.0"),
    )

    val series = listOf(
        VodItem(701, "Stadt der Lichter", "20", "2023", "8.3"),
        VodItem(702, "Küstenwache Nord", "21", "2021", "7.6"),
        VodItem(703, "Das Labor", "20", "2024", "8.0"),
        VodItem(704, "Grenzgänger", "21", "2022", "7.9"),
        VodItem(705, "Familie Berger", "20", "2019", "7.1"),
        VodItem(706, "Orbit", "20", "2024", "8.5"),
        VodItem(707, "Kommissarin Lenz", "21", "2020", "7.4"),
        VodItem(708, "Die Werft", "20", "2023", "7.8"),
    )

    fun start(): String {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val url = request.requestUrl ?: return MockResponse().setResponseCode(404)
                return when (url.encodedPath) {
                    "/player_api.php" -> json(api(url.queryParameter("action"), url.queryParameter("series_id"), url.queryParameter("vod_id")))
                    "/xmltv.php" -> MockResponse().setBody(xmltv()).setHeader("Content-Type", "application/xml")
                    "/espn/soccer/ger.1/scoreboard" -> json(if (sportEnabled) bundesliga() else EspnJson.scoreboard())
                    "/espn/mma/ufc/scoreboard" -> json(if (sportMixed) ufc() else EspnJson.scoreboard())
                    "/espn/soccer/eng.1/scoreboard" -> json(if (sportMixed) premierLeague() else EspnJson.scoreboard())
                    else -> if (url.encodedPath.startsWith("/espn/")) json(EspnJson.scoreboard()) else MockResponse().setResponseCode(404)
                }
            }
        }
        server.start()
        // Sport-Abrufe der App gehen an diesen Server statt ins Internet.
        SportEndpoints.espn = server.url("/espn").toString().trimEnd('/')
        GuideCatalog.baseUrl = server.url("/guide").toString().trimEnd('/')
        return url
    }

    /** Basisadresse nach [start] – für weitere Zugänge auf denselben Server. */
    val url: String get() = server.url("/").toString().trimEnd('/')

    fun shutdown() = server.shutdown()

    private fun json(body: String) = MockResponse().setBody(body).setHeader("Content-Type", "application/json")

    private fun api(action: String?, seriesId: String?, vodId: String?): String = when (action) {
        null -> JSONObject()
            .put("user_info", JSONObject().put("auth", 1).put("status", "Active").put("active_cons", activeConnections.toString()).put("max_connections", "1"))
            .put("server_info", JSONObject().put("timezone", "Europe/Berlin"))
            .toString()

        "get_live_categories" -> categories(liveCategories)

        "get_vod_categories" -> categories(vodCategories)

        "get_series_categories" -> categories(seriesCategories)

        "get_live_streams" -> JSONArray(
            live.map { item ->
                JSONObject().put("stream_id", item.id).put("name", item.name).put("category_id", item.category)
                    .put("stream_icon", art("logo", item.id, item.name.substringAfter(": ").removeSuffix(" HD")))
                    .put("epg_channel_id", item.epgId).put("tv_archive", if (item.archive) 1 else 0).put("tv_archive_duration", 7)
            },
        ).toString()

        "get_vod_streams" -> JSONArray(
            movies.map { item ->
                JSONObject().put("stream_id", item.id).put("name", item.name).put("category_id", item.category)
                    .put("stream_icon", art("poster", item.id, cleanTitle(item.name))).put("rating", item.rating)
                    .put("container_extension", "mkv").put("year", item.year)
            },
        ).toString()

        "get_series" -> JSONArray(
            series.map { item ->
                JSONObject().put("series_id", item.id).put("name", item.name).put("category_id", item.category)
                    .put("cover", art("poster", item.id, item.name)).put("rating", item.rating).put("releaseDate", "${item.year}-03-01")
                    .put("plot", "Eine Geschichte über Mut, Freundschaft und die Frage, was uns zusammenhält.")
                    .put("backdrop_path", JSONArray().put(art("backdrop", item.id, item.name)))
            },
        ).toString()

        "get_series_info" -> seriesInfo(seriesId?.toIntOrNull() ?: 701)

        "get_vod_info" -> vodInfo(vodId?.toIntOrNull() ?: 601)

        else -> "[]"
    }

    private fun seriesInfo(id: Int): String {
        val title = series.firstOrNull { it.id == id }?.name ?: "Serie"
        val episodes = JSONObject()
        for (season in 1..2) {
            val list = JSONArray()
            for (ep in 1..6) {
                val epId = id * 100 + season * 10 + ep
                list.put(
                    JSONObject()
                        .put("id", epId.toString()).put("episode_num", ep).put("season", season)
                        .put("title", EPISODE_TITLES[(season * 6 + ep) % EPISODE_TITLES.size]).put("container_extension", "mkv")
                        .put(
                            "info",
                            JSONObject().put("movie_image", art("still", epId, "$title $season×$ep"))
                                .put("plot", "Neue Spuren führen das Team an einen Ort, den niemand erwartet hätte.")
                                .put("duration_secs", 2_700 + ep * 60),
                        ),
                )
            }
            episodes.put(season.toString(), list)
        }
        return JSONObject()
            .put(
                "info",
                JSONObject().put(
                    "plot",
                    "Eine Stadt, ein Geheimnis und zwei Ermittlerinnen, die nicht aufgeben.",
                ).put("backdrop_path", JSONArray().put(art("backdrop", id, title))),
            )
            .put("episodes", episodes)
            .toString()
    }

    private fun vodInfo(id: Int): String {
        val item = movies.firstOrNull { it.id == id } ?: movies.first()
        return JSONObject().put(
            "info",
            JSONObject()
                .put(
                    "plot",
                    "Als ein Sturm die Küste erreicht, muss eine Forscherin zwischen ihrer Arbeit und ihrer Familie wählen. Ein ruhiger, bildgewaltiger Film über Entscheidungen.",
                )
                .put("backdrop_path", JSONArray().put(art("backdrop", item.id, cleanTitle(item.name))))
                .put("duration_secs", 7_080)
                .put("releasedate", "${item.year}-09-14")
                .put("rating", item.rating),
        ).toString()
    }

    /** Ein laufendes Spiel und drei kommende – alle auf „Sport1“ (Sendername wie bei ESPN). */
    fun bundesliga(nowMs: Long = System.currentTimeMillis()): String {
        val h = 3_600_000L
        val start = nowMs - nowMs % (15 * 60_000L)
        return EspnJson.scoreboard(
            EspnJson.event("1", start - h / 2, "Bayern Munich", "Borussia Dortmund", state = "in", stations = listOf("Sport1")),
            EspnJson.event("2", start + 3 * h, "RB Leipzig", "VfB Stuttgart", stations = listOf("Sport1")),
            EspnJson.event("3", start + 26 * h, "SC Freiburg", "1. FC Cologne", stations = listOf("Sport1")),
            EspnJson.event("4", start + 4 * 24 * h, "Werder Bremen", "Hamburg SV", stations = listOf("Sport1")),
        )
    }

    /** UFC heute Abend auf Sport1 (im Zugang) und ein Kampf auf DAZN 1 (nicht im Zugang). */
    fun ufc(nowMs: Long = System.currentTimeMillis()): String {
        val h = 3_600_000L
        val start = nowMs - nowMs % (15 * 60_000L)
        return EspnJson.scoreboard(
            EspnJson.event("u1", start + 5 * h, "Moicano", "Nolan", stations = listOf("Sport1")),
            EspnJson.event("u2", start + 29 * h, "Topuria", "Holloway", stations = listOf("DAZN 1")),
        )
    }

    /** Premier League auf Sky (nicht im Zugang) und ein Spiel ganz ohne Senderangabe. */
    fun premierLeague(nowMs: Long = System.currentTimeMillis()): String {
        val h = 3_600_000L
        val start = nowMs - nowMs % (15 * 60_000L)
        return EspnJson.scoreboard(
            EspnJson.event("p1", start + 2 * h, "Liverpool", "Arsenal", stations = listOf("Sky Sport Premier League")),
            EspnJson.event("p2", start + 50 * h, "Brentford", "Fulham"),
        )
    }

    /** XMLTV mit Sendungen von drei Stunden vor bis acht Stunden nach jetzt. */
    fun xmltv(nowMs: Long = System.currentTimeMillis()): String {
        val format = SimpleDateFormat("yyyyMMddHHmmss Z", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
        val sb = StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<tv>\n")
        val channels = live.map { it.epgId }.distinct()
        channels.forEach { sb.append("<channel id=\"$it\"><display-name>$it</display-name></channel>\n") }
        val start0 = nowMs - nowMs % (30 * 60_000L) - 3 * 3_600_000L
        channels.forEachIndexed { index, channel ->
            val titles = PROGRAMS[index % PROGRAMS.size]
            var t = start0 - (index % 3) * 15 * 60_000L
            var i = 0
            while (t < nowMs + 8 * 3_600_000L) {
                val minutes = DURATIONS[(i + index) % DURATIONS.size]
                val end = t + minutes * 60_000L
                val title = titles[i % titles.size]
                sb.append("<programme start=\"${format.format(t)}\" stop=\"${format.format(end)}\" channel=\"$channel\">")
                sb.append("<title lang=\"de\">$title</title><desc lang=\"de\">${DESCRIPTIONS[(i + index) % DESCRIPTIONS.size]}</desc></programme>\n")
                t = end
                i++
            }
        }
        return sb.append("</tv>\n").toString()
    }

    private fun categories(list: List<Pair<String, String>>) =
        JSONArray(list.map { (id, name) -> JSONObject().put("category_id", id).put("category_name", name).put("parent_id", 0) }).toString()

    companion object {
        fun art(kind: String, id: Int, title: String): String = "http://img.invalid/$kind/$id?t=" + URLEncoder.encode(title, "UTF-8")

        fun cleanTitle(name: String) = name.removePrefix("DE - ").substringBefore(" (").substringBefore(" [")

        private val DURATIONS = listOf(45, 60, 90, 30, 105, 60, 75)
        private val PROGRAMS = listOf(
            listOf("Nachrichten", "Länderspiegel", "Der Bergdoktor", "heute journal", "Markus Lanz", "Terra X"),
            listOf("Tagesschau", "Wetter vor acht", "Tatort", "Tagesthemen", "Sportschau", "Brisant"),
            listOf("Der Kommissar und das Meer", "Wilsberg", "Nachtschicht", "Inga Lindström", "Bares für Rares"),
            listOf("Kulturzeit", "nano", "makro", "Dokumentarfilm: Die Alpen", "37 Grad"),
            listOf("Reisen in Europa", "Geheimnisvolle Wälder", "Metropolis", "Konzert: Sinfonie Nr. 5"),
            listOf("Wer wird Millionär?", "Nachtjournal", "Let's Dance", "Spielfilm: Die Jagd"),
            listOf("Bundesliga live", "Fußball: Analyse", "Motorsport", "Darts WM"),
        )
        private val DESCRIPTIONS = listOf(
            "Aktuelle Berichte, Hintergründe und Gespräche zum Tag.",
            "Spannende Geschichten aus der Region – unterhaltsam und überraschend.",
            "Ein Fall, der das Team an seine Grenzen bringt.",
            "Faszinierende Bilder und neue Erkenntnisse über unseren Planeten.",
        )
        private val EPISODE_TITLES = listOf(
            "Der Anfang", "Nachtfahrt", "Spuren im Sand", "Das Versprechen", "Funkstille", "Gegenwind",
            "Die Rückkehr", "Kreuzungen", "Unter Wasser", "Lichtjahre", "Der Pakt", "Finale",
        )
    }
}
