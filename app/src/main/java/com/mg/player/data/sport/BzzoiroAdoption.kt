package com.mg.player.data.sport

import com.mg.player.core.network.HttpStatusException
import com.mg.player.data.search.SearchText
import com.mg.player.domain.model.SportLeague
import org.json.JSONException

/** Neuer Bzzoiro-Schlüssel: ESPN-Ligen, die Bzzoiro auch hat, dorthin umstellen und die Süper Lig dazunehmen (reine Logik). */
internal object BzzoiroAdoption {

    /** [moved]: ESPN-Liga → Bzzoiro-Liga (für die Übernahme von Hand gewählter Ersatzsender). */
    data class Result(val chosen: Set<String>, val message: String, val moved: Map<String, String> = emptyMap())

    fun adopt(catalog: List<SportLeague>, followed: Set<String>): Result {
        val chosen = followed.toMutableSet()
        val moved = HashMap<String, String>()
        var switched = 0
        var extra: String? = null
        BzzoiroSource.DEFAULTS.forEach { (espnKey, candidates) ->
            val match = catalog.firstOrNull { league -> candidates.any { (label, country) -> sameLeague(league.name, label, country) } }
                ?: return@forEach
            when {
                espnKey == null -> if (chosen.add(match.key)) extra = match.name.substringBeforeLast(" (")

                espnKey in chosen -> {
                    chosen -= espnKey
                    chosen += match.key
                    moved[espnKey] = match.key
                    switched++
                }
            }
        }
        val message = listOfNotNull("Bzzoiro verbunden – $switched Ligen umgestellt", extra?.let { "$it dazu" }).joinToString(", ") +
            ". Spiele kommen jetzt mit TV-Sendern."
        return Result(chosen, message, moved)
    }

    /**
     * Schon verbunden, aber dieselbe Liga doppelt (ESPN und Bzzoiro, z. B. „Süper Lig“ und „Trendyol Super Lig“): die
     * ESPN-Liga entfernen – Bzzoiro liefert die Sender je Spiel. Ein von Hand gewählter Ersatzsender wandert mit.
     * null = nichts doppelt.
     */
    fun mergeDuplicates(catalog: List<SportLeague>, followed: Set<String>, manual: Map<String, String>): Pair<Set<String>, Map<String, String>>? {
        val chosen = followed.toMutableSet()
        val channels = manual.toMutableMap()
        BzzoiroSource.DEFAULTS.forEach { (espnKey, candidates) ->
            if (espnKey == null || espnKey !in chosen) return@forEach
            val match = catalog.firstOrNull { league -> candidates.any { (label, country) -> sameLeague(league.name, label, country) } }
            if (match == null || match.key !in chosen) return@forEach
            chosen -= espnKey
            channels.remove(espnKey)?.let { channels.putIfAbsent(match.key, it) }
        }
        return if (chosen == followed) null else chosen to channels
    }

    /** „Bundesliga (Germany)“ passt zu label „bundesliga“, Land „germany“ (leeres Land = egal); Akzente und Satzzeichen egal. */
    fun sameLeague(display: String, label: String, country: String): Boolean {
        val name = SearchText.words(display.substringBeforeLast(" (")).joinToString(" ")
        val land = SearchText.words(display.substringAfterLast(" (", "").removeSuffix(")")).joinToString(" ")
        return name == SearchText.words(label).joinToString(" ") && (country.isEmpty() || land == SearchText.words(country).joinToString(" "))
    }
}

/** Kurzer, verständlicher Grund eines fehlgeschlagenen Sport-Abrufs. */
internal fun sportProblem(e: Exception): String = when (val code = (e as? HttpStatusException)?.code) {
    401, 403 -> "Schlüssel ungültig oder Liga nicht im Tarif"
    429 -> "zu viele Anfragen, später erneut"
    null -> if (e is JSONException) "unerwartete Antwort" else "keine Verbindung"
    else -> "Server-Fehler $code"
}
