package com.mg.player.data.sport

import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import org.json.JSONArray
import org.json.JSONObject

/** Baut Antworten im Aufbau des ESPN-Scoreboards (gekürzt auf die Felder, die die App liest). */
object EspnJson {
    private fun iso(ms: Long) = SimpleDateFormat("yyyy-MM-dd'T'HH:mm'Z'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }.format(ms)

    fun event(id: String, startMs: Long, home: String, away: String, state: String = "pre", stations: List<String> = emptyList()): JSONObject {
        val competitors = JSONArray()
            .put(JSONObject().put("homeAway", "home").put("team", JSONObject().put("displayName", home)))
            .put(JSONObject().put("homeAway", "away").put("team", JSONObject().put("displayName", away)))
        val broadcasts = JSONArray().apply { if (stations.isNotEmpty()) put(JSONObject().put("market", "national").put("names", JSONArray(stations))) }
        return JSONObject()
            .put("id", id)
            .put("date", iso(startMs))
            .put("name", "$away at $home")
            .put("status", JSONObject().put("type", JSONObject().put("state", state)))
            .put("competitions", JSONArray().put(JSONObject().put("competitors", competitors).put("broadcasts", broadcasts)))
    }

    fun scoreboard(vararg events: JSONObject, calendar: List<Long> = emptyList()): String = JSONObject()
        .put("leagues", JSONArray().put(JSONObject().put("calendar", JSONArray(calendar.map(::iso)))))
        .put("events", JSONArray(events.toList()))
        .toString()
}
