package com.mg.player.data.sport

import com.mg.player.core.network.HttpFetcher
import com.mg.player.core.util.runCatchingCancellable
import com.mg.player.domain.model.EventStatus
import com.mg.player.domain.model.SportEvent
import com.mg.player.domain.model.SportLeague
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/**
 * Sportmonks (Fußball) mit dem eigenen Schlüssel aus den Einstellungen. Welche Ligen es gibt, hängt vom Tarif ab
 * (Gratis: dänische Superliga, schottische Premiership). Ohne Schlüssel liefert diese Quelle nichts.
 */
class SportmonksSource(private val http: HttpFetcher, private val token: suspend () -> String) : SportSource {

    override val name = "Sportmonks"

    @Volatile
    private var leagueNames: Map<Long, String> = emptyMap()

    override suspend fun leagues(): List<SportLeague> {
        val key = token().takeIf { it.isNotBlank() } ?: return emptyList()
        val body = http.getText("${SportEndpoints.sportmonks}/leagues?per_page=50", auth(key), allowRedirects = false)
        val data = JSONObject(body).optJSONArray("data") ?: return emptyList()
        val names = (0 until data.length()).mapNotNull { data.optJSONObject(it) }
            .associate { it.optLong("id") to it.optString("name") }
        leagueNames = names
        return names.map { (id, label) -> SportLeague("$KEY_PREFIX$id", label, name) }
    }

    override fun handles(leagueKey: String) = leagueKey.startsWith(KEY_PREFIX)

    override suspend fun fetch(leagueKey: String, fromMs: Long, toMs: Long): List<SportEvent> {
        val key = token().takeIf { it.isNotBlank() } ?: return emptyList()
        val leagueId = leagueKey.removePrefix(KEY_PREFIX).toLongOrNull() ?: return emptyList()
        if (leagueNames.isEmpty()) runCatchingCancellable { leagues() }
        val events = ArrayList<SportEvent>()
        var url: String? = "${SportEndpoints.sportmonks}/fixtures/between/${date(fromMs)}/${date(toMs)}?include=participants" +
            "&filters=fixtureLeagues:$leagueId&per_page=50"
        var pages = 0
        while (url != null && pages++ < MAX_PAGES) {
            val json = JSONObject(http.getText(url, auth(key), allowRedirects = false))
            events += parse(json).filter { it.id.startsWith("$KEY_PREFIX$leagueId#") }
            val pagination = json.optJSONObject("pagination")
            url =
                pagination?.optString("next_page")?.takeIf {
                    pagination.optBoolean("has_more") &&
                        it.startsWith(SportEndpoints.sportmonks.substringBefore("/v3"))
                }
                    // Der Schlüssel geht im Kopf mit – nie in der Adresse (Server- und Proxy-Protokolle).
                    ?.let(::withoutToken)
        }
        return events.filter { it.startMs in fromMs..toMs }
    }

    /** Sportmonks v3 akzeptiert den Schlüssel im Kopf „Authorization“. */
    private fun auth(key: String) = mapOf("Authorization" to key)

    internal fun parse(json: JSONObject): List<SportEvent> {
        val data = json.optJSONArray("data") ?: return emptyList()
        return (0 until data.length()).mapNotNull { i ->
            val f = data.optJSONObject(i) ?: return@mapNotNull null
            val start = f.optLong("starting_at_timestamp").takeIf { it > 0 }?.times(1000) ?: return@mapNotNull null
            var home: String? = null
            var away: String? = null
            val participants = f.optJSONArray("participants")
            for (p in 0 until (participants?.length() ?: 0)) {
                val team = participants?.optJSONObject(p) ?: continue
                val teamName = team.optString("name").takeIf { it.isNotBlank() }
                if (team.optJSONObject("meta")?.optString("location") == "home") home = teamName else away = teamName
            }
            val leagueId = f.optLong("league_id")
            SportEvent(
                id = "$KEY_PREFIX$leagueId#${f.optLong("id")}",
                sport = "soccer",
                league = leagueNames[leagueId] ?: "Fußball",
                title = if (home != null && away != null) "$home – $away" else f.optString("name"),
                homeTeam = home,
                awayTeam = away,
                startMs = start,
                endMs = f.optInt("length").takeIf { it > 0 }?.let { start + (it + 30) * 60_000L },
                status = status(f.optInt("state_id")),
                stations = emptyList(),
            )
        }
    }

    companion object {
        const val KEY_PREFIX = "sm:"
        private const val MAX_PAGES = 6

        /** Sportmonks-Zustände: 1 = nicht begonnen, 5/7/8 = beendet, Spielphasen = läuft. [UNSICHER] laut Doku v3. */
        internal fun status(stateId: Int): EventStatus = when (stateId) {
            1, 13 -> EventStatus.PRE
            5, 7, 8 -> EventStatus.POST
            2, 3, 4, 6, 9, 21, 22, 25 -> EventStatus.IN
            else -> EventStatus.UNKNOWN
        }

        private fun date(ms: Long) = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }.format(ms)

        /** api_token=… aus einer vom Server gelieferten Adresse entfernen („?api_token=x&page=2“ → „?page=2“). */
        internal fun withoutToken(url: String): String = url.replace(Regex("""([?&])api_token=[^&#]*&?"""), "$1").trimEnd('&', '?')
    }
}
