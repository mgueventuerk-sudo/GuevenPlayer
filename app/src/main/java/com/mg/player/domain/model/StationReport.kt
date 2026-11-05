package com.mg.player.domain.model

import java.util.Locale

/**
 * Diagnose der Sendernamen aus der Online-Quelle (Bzzoiro) für Einstellungen → Sport: wie viele Spiele dort einen
 * Sender haben, wie viele davon einem Kanal der Zugänge zugeordnet wurden, und welche Namen in der Senderliste fehlen –
 * so geschrieben, wie Bzzoiro sie liefert (nicht als interner Vergleichsschlüssel).
 */
data class StationReport(val events: Int, val matched: Int, val missing: List<String>) {

    fun text(): String = buildString {
        if (events == 0) {
            append("Bzzoiro-Senderliste: noch kein Spiel mit Sender (Bzzoiro trägt Sender oft erst kurz vorher ein)")
            return@buildString
        }
        append("Bzzoiro-Senderliste: $events ${if (events == 1) "Spiel" else "Spiele"} mit Sender, $matched zugeordnet")
        if (missing.isNotEmpty()) {
            append("\nNicht gefunden: ")
            append(missing.take(MAX_NAMES).joinToString(", ") { "‚$it‘" })
            if (missing.size > MAX_NAMES) append(" und ${missing.size - MAX_NAMES} weitere")
        }
    }

    companion object {
        const val MAX_NAMES = 8

        /** Namen ohne Doppelte (Groß-/Kleinschreibung egal), Reihenfolge wie geliefert. */
        fun distinctNames(names: List<String>): List<String> =
            names.map(String::trim).filter(String::isNotEmpty).distinctBy { it.lowercase(Locale.ROOT) }
    }
}
