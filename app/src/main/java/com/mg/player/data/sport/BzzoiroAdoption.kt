package com.mg.player.data.sport

import com.mg.player.core.network.HttpStatusException
import com.mg.player.data.search.SearchText
import com.mg.player.domain.model.SportLeague
import org.json.JSONException

/** Neuer Bzzoiro-Schlüssel: ESPN-Ligen, die Bzzoiro auch hat, dorthin umstellen und die Süper Lig dazunehmen (reine Logik). */
internal object BzzoiroAdoption {

    data class Result(val chosen: Set<String>, val message: String)

    fun adopt(catalog: List<SportLeague>, followed: Set<String>): Result {
        val chosen = followed.toMutableSet()
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
                    switched++
                }
            }
        }
        val message = listOfNotNull("Bzzoiro verbunden – $switched Ligen umgestellt", extra?.let { "$it dazu" }).joinToString(", ") +
            ". Spiele kommen jetzt mit TV-Sendern."
        return Result(chosen, message)
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
