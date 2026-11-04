package com.mg.player.data.sport

import com.mg.player.core.network.HttpFetcher
import com.mg.player.core.network.HttpStatusException
import com.mg.player.domain.model.EventStatus
import com.mg.player.domain.model.SportEvent
import com.mg.player.domain.model.SportGroups
import com.mg.player.domain.model.SportLeague
import com.mg.player.domain.model.sportScore
import java.net.URI
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import org.json.JSONObject

/**
 * ESPN-Scoreboard (ohne Schlüssel, inoffizielle Schnittstelle). Je Liga wird zuerst der ganze Zeitraum abgefragt;
 * lehnt ESPN das ab, werden über den Spielplan („calendar“) nur die Spieltage im Zeitraum geholt.
 */
class EspnSource(private val http: HttpFetcher) : SportSource {

    /** [keep]: nur bestimmte Events einer Liga (z. B. Länderspiele von Deutschland und der Türkei). */
    data class League(val sport: String, val slug: String, val label: String, val keep: ((SportEvent) -> Boolean)? = null) {
        val key: String get() = "$KEY_PREFIX$sport/$slug"
    }

    override val name = "ESPN"

    override suspend fun leagues(): List<SportLeague> = LEAGUES.map { SportLeague(it.key, it.label, name, SportGroups.of(it.sport)) }

    override fun handles(leagueKey: String) = leagueKey.startsWith(KEY_PREFIX)

    override suspend fun fetch(leagueKey: String, fromMs: Long, toMs: Long): List<SportEvent> {
        val league = LEAGUES.firstOrNull { it.key == leagueKey } ?: return emptyList()
        val events = fetchLeague(league, fromMs, toMs)
        return league.keep?.let(events::filter) ?: events
    }

    private fun fetchLeague(league: League, fromMs: Long, toMs: Long): List<SportEvent> {
        val base = "${SportEndpoints.espn}/${league.sport}/${league.slug}/scoreboard"
        val range = try {
            parse(getText("$base?dates=${day(fromMs)}-${day(toMs)}"), league)
        } catch (e: HttpStatusException) {
            if (e.code == 429) throw e
            null
        }
        if (range != null) return range.filter { it.startMs in fromMs..toMs }
        // Ausweichweg: Spielplan der Saison, dann nur die Spieltage im Zeitraum.
        val today = getText(base)
        val days = calendarDays(today).filter { it in fromMs..toMs }.map(::day).distinct().take(MAX_DAYS)
        return (parse(today, league) + days.flatMap { parse(getText("$base?dates=$it"), league) })
            .distinctBy { it.id }
            .filter { it.startMs in fromMs..toMs }
    }

    /** ESPN requests carry no key; plain HTTP is enabled only for the in-process loopback test server. */
    private fun getText(url: String): String {
        val localTestEndpoint = runCatching { URI(url).host in LOOPBACK_HOSTS }.getOrDefault(false)
        return http.getText(url, allowInsecureHttp = localTestEndpoint)
    }

    internal fun parse(body: String, league: League): List<SportEvent> {
        val events = JSONObject(body).optJSONArray("events") ?: return emptyList()
        return (0 until events.length()).mapNotNull { i ->
            val e = events.optJSONObject(i) ?: return@mapNotNull null
            val start = parseIso(e.optString("date")) ?: return@mapNotNull null
            val competition = e.optJSONArray("competitions")?.optJSONObject(0)
            var home: String? = null
            var away: String? = null
            var homeScore: String? = null
            var awayScore: String? = null
            val competitors = competition?.optJSONArray("competitors")
            for (c in 0 until (competitors?.length() ?: 0)) {
                val comp = competitors?.optJSONObject(c) ?: continue
                val team = comp.optJSONObject("team")?.optString("displayName")?.takeIf { it.isNotBlank() }
                val goals = comp.optString("score").takeIf { it.isNotBlank() }
                if (comp.optString("homeAway") == "home") {
                    home = team
                    homeScore = goals
                } else {
                    away = team
                    awayScore = goals
                }
            }
            val statusType = e.optJSONObject("status")?.optJSONObject("type")
            val state = statusType?.optString("state")
            SportEvent(
                id = "${league.key}#${e.optString("id")}",
                sport = league.sport,
                league = league.label,
                title = if (home != null && away != null) "$home – $away" else e.optString("name"),
                homeTeam = home,
                awayTeam = away,
                startMs = start,
                endMs = null,
                status = when (state) {
                    "pre" -> EventStatus.PRE
                    "in" -> EventStatus.IN
                    "post" -> EventStatus.POST
                    else -> EventStatus.UNKNOWN
                },
                stations = broadcasts(competition),
                // Vor dem Anstoß liefert ESPN „0“ – erst ab Spielbeginn als Stand zeigen.
                score = if (state == "in" || state == "post") sportScore(homeScore, awayScore, statusType.optString("shortDetail")) else null,
            )
        }
    }

    private fun broadcasts(competition: JSONObject?): List<String> = buildList {
        val list = competition?.optJSONArray("broadcasts") ?: return@buildList
        for (b in 0 until list.length()) {
            val names = list.optJSONObject(b)?.optJSONArray("names") ?: continue
            for (n in 0 until names.length()) names.optString(n).takeIf { it.isNotBlank() }?.let(::add)
        }
    }

    private fun calendarDays(body: String): List<Long> {
        val calendar = JSONObject(body).optJSONArray("leagues")?.optJSONObject(0)?.optJSONArray("calendar") ?: return emptyList()
        return (0 until calendar.length()).mapNotNull { calendar.opt(it) as? String }.mapNotNull(::parseIso)
    }

    companion object {
        const val KEY_PREFIX = "espn:"
        private val LOOPBACK_HOSTS = setOf("localhost", "127.0.0.1", "::1")
        private const val MAX_DAYS = 8

        /** Auswahl an Ligen; Kürzel wie bei ESPN. */
        val LEAGUES = listOf(
            League("soccer", "ger.1", "Bundesliga"),
            League("soccer", "ger.2", "2. Bundesliga"),
            League("soccer", "ger.dfb_pokal", "DFB-Pokal"),
            League("soccer", "uefa.champions", "Champions League"),
            League("soccer", "uefa.europa", "Europa League"),
            League("soccer", "uefa.europa.conf", "Conference League"),
            League("soccer", "uefa.nations", "Nations League"),
            League("soccer", "fifa.worldq.uefa", "WM-Qualifikation Europa"),
            League("soccer", "eng.1", "Premier League"),
            League("soccer", "esp.1", "LaLiga"),
            League("soccer", "ita.1", "Serie A"),
            League("soccer", "fra.1", "Ligue 1"),
            League("soccer", "tur.1", "Süper Lig"),
            League("soccer", "ned.1", "Eredivisie"),
            League("soccer", "aut.1", "Österreichische Bundesliga"),
            League("soccer", "eng.fa", "FA Cup"),
            League("soccer", "fifa.world", "Weltmeisterschaft"),
            League("soccer", "uefa.euro", "Europameisterschaft"),
            League("soccer", "uefa.euroq", "EM-Qualifikation"),
            League("soccer", "fifa.friendly", "Länderspiele Deutschland & Türkei") { e -> listOfNotNull(e.homeTeam, e.awayTeam).any(::isOwnNation) },
            League("mma", "ufc", "UFC"),
            League("racing", "f1", "Formel 1"),
            League("basketball", "nba", "NBA"),
            League("football", "nfl", "NFL"),
            League("hockey", "nhl", "NHL"),
            League("tennis", "atp", "Tennis: Grand Slams & ATP Finals (Herren)") { e -> isMajorTennis(e.title) },
            League("tennis", "wta", "Tennis: Grand Slams & WTA Finals (Damen)") { e -> isMajorTennis(e.title) },
        )

        private fun isOwnNation(team: String): Boolean = team.lowercase(Locale.ROOT).let {
            it == "germany" || it == "turkey" || it == "türkiye" ||
                it == "turkiye"
        }

        /** Grand Slams und die Saisonfinals – kleinere Turniere würden die Übersicht fluten. */
        internal fun isMajorTennis(title: String): Boolean {
            val t = title.lowercase(Locale.ROOT)
            return listOf("australian open", "roland garros", "french open", "wimbledon", "us open", "atp finals", "wta finals").any { it in t }
        }

        /** Voreinstellung, solange man selbst nichts gewählt hat. */
        val DEFAULT_KEYS: Set<String> = LEAGUES.filter {
            it.slug in setOf("ger.1", "ger.2", "ger.dfb_pokal", "uefa.champions", "uefa.europa", "uefa.nations", "eng.1", "tur.1", "ufc")
        }.mapTo(HashSet()) { it.key }

        internal fun day(ms: Long): String = SimpleDateFormat("yyyyMMdd", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }.format(ms)

        /** „2026-10-31T17:30Z“ bzw. mit Sekunden. */
        internal fun parseIso(raw: String): Long? = listOf("yyyy-MM-dd'T'HH:mmX", "yyyy-MM-dd'T'HH:mm:ssX").firstNotNullOfOrNull { pattern ->
            runCatching { SimpleDateFormat(pattern, Locale.US).parse(raw)?.time }.getOrNull()
        }
    }
}
