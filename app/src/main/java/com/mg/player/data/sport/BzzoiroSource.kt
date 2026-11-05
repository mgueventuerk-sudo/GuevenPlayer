package com.mg.player.data.sport

import com.mg.player.core.log.AppLog
import com.mg.player.core.network.HttpFetcher
import com.mg.player.core.util.runCatchingCancellable
import com.mg.player.domain.model.EventStatus
import com.mg.player.domain.model.SportEvent
import com.mg.player.domain.model.SportLeague
import com.mg.player.domain.model.sportScore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import org.json.JSONObject
import java.net.URI
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.ConcurrentHashMap

/**
 * Bzzoiro Sports Data (sports.bzzoiro.com) mit dem eigenen Schlüssel aus den Einstellungen – Fußball ist kostenlos.
 * Liefert neben den Spielen auch die TV-Sender je Land: Damit findet die App die Spiele über den Sendernamen
 * („Sky Sport Bundesliga 1“, „DAZN 1“, „beIN Sports 1“), auch wenn das EPG der Playlist keine Mannschaften nennt.
 * Der Schlüssel geht nur an diesen Dienst (Kopf „Authorization: Token …“), nie in die Adresse.
 */
class BzzoiroSource(private val http: HttpFetcher, private val token: suspend () -> String) : SportSource {

    override val name = "Bzzoiro"

    @Volatile
    private var leagueNames: Map<Long, String> = emptyMap()

    /** Sender je Spiel, je Liga zwischengespeichert (ändern sich selten; spart Anfragen bei laufenden Spielen). */
    private val broadcasts = ConcurrentHashMap<Long, Pair<Long, Map<Long, List<String>>>>()

    override suspend fun leagues(): List<SportLeague> {
        val key = token().takeIf { it.isNotBlank() } ?: return emptyList()
        val list = pages("${SportEndpoints.bzzoiro}/leagues/?limit=$PAGE_SIZE", key).mapNotNull { o ->
            val id = o.optLong("id").takeIf { it > 0 } ?: return@mapNotNull null
            Triple(id, o.optString("name"), o.optString("country"))
        }
        leagueNames = list.associate { (id, label, _) -> id to label }
        return list.map { (id, label, country) ->
            SportLeague("$KEY_PREFIX$id", if (country.isBlank()) label else "$label ($country)", name)
        }
    }

    override fun handles(leagueKey: String) = leagueKey.startsWith(KEY_PREFIX)

    override suspend fun fetch(leagueKey: String, fromMs: Long, toMs: Long): List<SportEvent> {
        val key = token().takeIf { it.isNotBlank() } ?: return emptyList()
        val leagueId = leagueKey.removePrefix(KEY_PREFIX).toLongOrNull() ?: return emptyList()
        if (leagueNames.isEmpty()) runCatchingCancellable { leagues() }
        val range = "date_from=${day(fromMs)}&date_to=${day(toMs)}"
        val rows = pages("${SportEndpoints.bzzoiro}/events/?league_id=$leagueId&$range&limit=$PAGE_SIZE", key)
        val stations = stationsFor(leagueId, range, key)
        return rows.mapNotNull { parseEvent(it, leagueId, stations) }.filter { it.startMs in fromMs..toMs }
    }

    private suspend fun stationsFor(leagueId: Long, range: String, key: String): Map<Long, List<String>> {
        val now = System.currentTimeMillis()
        broadcasts[leagueId]?.let { (at, map) -> if (now - at < BROADCAST_TTL_MS) return map }
        // Je zwei Länder gleichzeitig: halbiert die Wartezeit, ohne den Dienst mit Anfragen zu fluten.
        val rows = coroutineScope {
            COUNTRIES.chunked(PARALLEL).flatMap { chunk ->
                chunk.map { country -> async(Dispatchers.IO) { broadcastRows(leagueId, country, range, key) } }.awaitAll().flatten()
            }
        }
        val result = cleanStations(rows)
        broadcasts[leagueId] = now to result
        return result
    }

    /**
     * Sender eines Landes; ohne Senderangaben geht es trotzdem weiter (dann über EPG bzw. von Hand zugeordnet).
     * Fehler werden nicht mehr still verschluckt, sondern je Liga gemerkt ([stationProblem]).
     */
    private fun broadcastRows(leagueId: Long, country: String, range: String, key: String): List<Broadcast> {
        val rows = try {
            pages("${SportEndpoints.bzzoiro}/broadcasts/?league_id=$leagueId&country_code=$country&$range&limit=$PAGE_SIZE", key)
        } catch (e: Exception) {
            // Blockierender Abruf (kein suspend): hier kommt keine CancellationException an.
            stationProblems[leagueId] = "Bzzoiro-Senderliste $country nicht abrufbar: ${sportProblem(e)}"
            AppLog.log("Sport", "Bzzoiro-Senderliste $country für Liga $leagueId: ${e.javaClass.simpleName}")
            return emptyList()
        }
        val parsed = rows.mapNotNull(::parseBroadcast).map { it.copy(country = country) }
        if (rows.isNotEmpty() && parsed.isEmpty()) {
            // Antwort da, aber kein Sender lesbar: Feldnamen ins Protokoll (für die Fehlersuche).
            val fields = rows.first().keys().asSequence().toList()
            AppLog.log("Sport", "Bzzoiro-Senderliste $country: ${rows.size} Einträge ohne lesbaren Sender, Felder $fields")
            stationProblems[leagueId] = "Bzzoiro-Senderliste $country: ${rows.size} Einträge, aber kein Sender lesbar (Felder ${fields.take(MAX_FIELDS)})"
        }
        return parsed
    }

    /** Problem mit der Senderliste der Liga (null = keins), danach vergessen. */
    fun stationProblem(leagueKey: String): String? = leagueKey.removePrefix(KEY_PREFIX).toLongOrNull()?.let(stationProblems::remove)

    private val stationProblems = ConcurrentHashMap<Long, String>()

    internal fun parseEvent(o: JSONObject, leagueId: Long, stations: Map<Long, List<String>>): SportEvent? {
        val id = o.optLong("id").takeIf { it > 0 } ?: return null
        val start = parseTime(o.optString("event_date")) ?: return null
        val home = o.optString("home_team").takeIf { it.isNotBlank() && it != "null" }
        val away = o.optString("away_team").takeIf { it.isNotBlank() && it != "null" }
        return SportEvent(
            id = "$KEY_PREFIX$leagueId#$id",
            sport = "soccer",
            league = leagueNames[leagueId] ?: "Fußball",
            title = if (home != null && away != null) "$home – $away" else home ?: away ?: "Spiel",
            homeTeam = home,
            awayTeam = away,
            startMs = start,
            endMs = null,
            status = status(o.optString("status")),
            stations = stations[id].orEmpty(),
            score = scoreOf(o),
        )
    }

    /** Alle Seiten (limit/offset, „next“) – höchstens [MAX_PAGES]; „next“ nur, wenn es auf denselben Dienst zeigt. */
    private fun pages(firstUrl: String, key: String): List<JSONObject> {
        val result = ArrayList<JSONObject>()
        var url: String? = firstUrl
        var pages = 0
        val host = URI(SportEndpoints.bzzoiro).host
        while (url != null && pages++ < MAX_PAGES) {
            val json = JSONObject(http.getText(url, mapOf("Authorization" to "Token $key"), allowRedirects = false))
            val items = json.optJSONArray("results")
            for (i in 0 until (items?.length() ?: 0)) items?.optJSONObject(i)?.let(result::add)
            url = json.optString("next").takeIf { it.isNotBlank() && it != "null" && runCatching { URI(it).host == host }.getOrDefault(false) }
        }
        return result
    }

    /** Eine Zeile aus /broadcasts/: Sender eines Spiels in einem Land ([country] wie abgefragt, „TR“; leer = unbekannt). */
    internal data class Broadcast(val eventId: Long, val channel: String, val startMs: Long?, val country: String = "")

    companion object {
        /**
         * Eine Zeile aus /broadcasts/ lesen. Tolerant gegenüber Feldnamen: „channel_name“, „channel“: {„name“} oder „channel“
         * bzw. „event_id“, „event“: {„id“} oder „event“ – vorher reichte ein abweichender Name, und kein Spiel bekam Sender.
         */
        internal fun parseBroadcast(b: JSONObject): Broadcast? {
            val name = listOfNotNull(
                b.optString("channel_name"),
                b.optJSONObject("channel")?.optString("name"),
                b.optString("channel").takeIf { b.optJSONObject("channel") == null },
                b.optString("name"),
            ).firstOrNull { it.isNotBlank() && it != "null" } ?: return null
            val eventId = b.optLong("event_id").takeIf { it > 0 }
                ?: b.optJSONObject("event")?.optLong("id")?.takeIf { it > 0 }
                ?: b.optLong("event").takeIf { it > 0 }
                ?: return null
            return Broadcast(eventId, name, parseTime(b.optString("event_date")))
        }

        const val KEY_PREFIX = "bsd:"
        private const val PAGE_SIZE = 200
        private const val MAX_PAGES = 5
        // 1 h (vorher 3 h): Bzzoiro trägt Sender oft erst kurz vorher nach.
        private const val BROADCAST_TTL_MS = 3_600_000L
        private const val PARALLEL = 2
        private const val MAX_FIELDS = 8

        /** Sender aus diesen Ländern (Deutschland, Österreich, Schweiz, Türkei). */
        private val COUNTRIES = listOf("DE", "AT", "CH", "TR")

        /** Apps und Streaming-Angebote (kein Sender, den ein IPTV-Zugang hat) – stehen bei Bzzoiro bei fast jedem Spiel. */
        private val NOT_TV_EXACT = setOf("sky go", "wow", "rtl+", "joyn", "prime video", "amazon prime video", "magentatv", "dazn app")
        // „beIN Connect Türkei“, „DAZN Connect“ …: Streaming-Apps, kein Sender eines IPTV-Zugangs.
        private val NOT_TV_PARTS = listOf("onefootball", "mediathek", "livestream", "live stream", "news", ".de", ".com", " app", " connect")

        /** Ab so vielen gleichzeitigen Spielen auf einem Sender ist er kein Sender für ein bestimmtes Spiel (Konferenz, „Top Event“, „Mix“). */
        private const val SHARED_LIMIT = 3
        private const val SAME_TIME_MS = 30 * 60_000L

        /**
         * Sender je Spiel, bereinigt: ohne Apps/Streaming und ohne Sammelsender, die laut Bzzoiro mehrere gleichzeitige
         * Spiele zeigen („Sky Sport Top Event“, „Sky Sport Mix“ stehen bei allen Pokalspielen eines Abends). Sonst landeten
         * alle Spiele auf demselben Sender – im Zweifel lieber kein Sender als ein falscher.
         */
        internal fun cleanStations(rows: List<Broadcast>): Map<Long, List<String>> {
            val tv = rows.filter { b ->
                val n = b.channel.trim().lowercase(Locale.ROOT)
                n !in NOT_TV_EXACT && NOT_TV_PARTS.none { it in n }
            }
            val byName = tv.groupBy(::stationName)
            return tv.filter { b ->
                val start = b.startMs ?: return@filter true
                val sameTime = byName[stationName(b)].orEmpty()
                    .filter { o -> o.startMs != null && kotlin.math.abs(o.startMs - start) <= SAME_TIME_MS }
                    .map { it.eventId }.distinct().size
                sameTime < SHARED_LIMIT
            }.groupBy { it.eventId }.mapValues { (_, list) -> list.map(::stationName).distinctBy { it.lowercase(Locale.ROOT) } }
        }

        /**
         * Sendername mit Land wie bei den Anbietern („TR| beIN SPORTS 1“): „beIN SPORTS 1“ gibt es in vielen Ländern
         * (Türkei, Frankreich, Nahost …). Mit dem Land wählt die Zuordnung nur den Sender dieses Landes (oder einen ohne
         * Länderangabe), nie „AR| BEIN SPORTS 1“ für ein Spiel, das Bzzoiro in der Türkei auf beIN SPORTS 1 meldet.
         */
        private fun stationName(b: Broadcast): String = if (b.country.isBlank()) b.channel.trim() else "${b.country}| ${b.channel.trim()}"

        /** „2:1 · 67'“ – nur, wenn das Spiel läuft oder vorbei ist (vorher sind die Tore leer bzw. 0). */
        internal fun scoreOf(o: JSONObject): String? {
            val state = status(o.optString("status"))
            if (state != EventStatus.IN && state != EventStatus.POST) return null
            val minute = o.optInt("current_minute", 0).takeIf { state == EventStatus.IN && it > 0 }?.let { "$it'" }
            return sportScore(o.opt("home_score")?.toString(), o.opt("away_score")?.toString(), minute ?: if (state == EventStatus.POST) "Ende" else null)
        }

        internal fun status(value: String): EventStatus = when (value.lowercase(Locale.ROOT)) {
            "notstarted", "upcoming", "delayed" -> EventStatus.PRE

            "inprogress", "live", "1st_half", "2nd_half", "halftime", "extratime", "penalties" -> EventStatus.IN

            // Abgesagt/verschoben: nicht mehr als kommend anzeigen.
            "finished", "aet", "cancelled", "postponed", "unresolved" -> EventStatus.POST

            else -> EventStatus.UNKNOWN
        }

        /** „2026-10-02T01:00:00+00:00“, „…Z“ oder mit Sekundenbruchteilen (ohne java.time – minSdk 23). */
        internal fun parseTime(value: String): Long? {
            if (value.length < 19) return null
            val base = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
            val utc = runCatching { base.parse(value.substring(0, 19))?.time }.getOrNull() ?: return null
            val zone = value.substring(19).dropWhile { it == '.' || it.isDigit() }
            if (zone.isEmpty() || zone == "Z") return utc
            val sign = if (zone.startsWith('-')) -1 else 1
            val parts = zone.drop(1).split(':')
            val hours = parts.getOrNull(0)?.toIntOrNull() ?: return utc
            val minutes = parts.getOrNull(1)?.toIntOrNull() ?: 0
            return utc - sign * (hours * 60 + minutes) * 60_000L
        }

        private fun day(ms: Long) = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }.format(ms)

        /**
         * Gegenstücke zu den ESPN-Voreinstellungen (Name und Land wie bei Bzzoiro) plus die Süper Lig. Mit Schlüssel
         * ersetzt Bzzoiro diese ESPN-Ligen – sonst stünde jedes Spiel doppelt da.
         */
        private val TURKISH_LEAGUE = listOf("super lig" to "turkey", "trendyol super lig" to "turkey")

        internal val DEFAULTS: Map<String?, List<Pair<String, String>>> = mapOf(
            "espn:soccer/ger.1" to listOf("bundesliga" to "germany"),
            "espn:soccer/ger.2" to listOf("2. bundesliga" to "germany", "2.bundesliga" to "germany"),
            "espn:soccer/ger.dfb_pokal" to listOf("dfb pokal" to "germany", "dfb-pokal" to "germany"),
            "espn:soccer/uefa.champions" to listOf("uefa champions league" to "", "champions league" to ""),
            "espn:soccer/uefa.europa" to listOf("uefa europa league" to "", "europa league" to ""),
            "espn:soccer/uefa.nations" to listOf("uefa nations league" to "", "nations league" to ""),
            "espn:soccer/uefa.europa.conf" to listOf("uefa conference league" to "", "conference league" to ""),
            "espn:soccer/eng.1" to listOf("premier league" to "england"),
            "espn:soccer/eng.fa" to listOf("fa cup" to "england"),
            "espn:soccer/esp.1" to listOf("la liga" to "spain", "laliga" to "spain"),
            "espn:soccer/ita.1" to listOf("serie a" to "italy"),
            "espn:soccer/fra.1" to listOf("ligue 1" to "france"),
            "espn:soccer/ned.1" to listOf("eredivisie" to "netherlands"),
            "espn:soccer/aut.1" to listOf("austrian bundesliga" to "austria", "bundesliga" to "austria"),
            // Bei Bzzoiro „Trendyol Super Lig“ (ohne ü) – verglichen wird ohne Akzente. Ersetzt die ESPN-Süper-Lig (sonst
            // stand die Liga doppelt da); wer sie nicht verfolgt, bekommt sie dazu.
            "espn:soccer/tur.1" to TURKISH_LEAGUE,
            null to TURKISH_LEAGUE,
        )
    }
}
