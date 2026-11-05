package com.mg.player.domain.model

/**
 * Woher die Zuordnung Spiel → Sender stammt (ab 3.53, Plan „Spiel-Finder“ Baustein A). Abgeleitet aus dem Score der
 * Zuordnung (+1 Bonus für den aktiven Zugang ist berücksichtigt). [switchable]: lässt sich in den Einstellungen abschalten.
 */
enum class MatchSource(val label: String, val detail: String, val guessed: Boolean = false, val switchable: Boolean = false) {
    CONFIRMED("vom Anbieter bestätigt", "Das Programm des Anbieters nennt das Spiel kurz vor Anstoß."),
    GUIDE("laut TV-Programm", "Das öffentliche TV-Programm des Senders nennt beide Mannschaften.", switchable = true),
    STATION("laut Sendeliste", "Bzzoiro bzw. ESPN nennen diesen Sender für das Spiel.", switchable = true),
    EPG("laut EPG des Anbieters", "Das Programm deines Anbieters nennt beide Mannschaften.", switchable = true),
    GUIDE_WEAK("laut TV-Programm, unsicher", "Das TV-Programm nennt nur eine Mannschaft oder nur den Wettbewerb.", guessed = true, switchable = true),
    KEYWORD("laut EPG-Stichwort", "Das EPG nennt nur die Serie (z. B. Formel 1, UFC).", guessed = true, switchable = true),
    MANUAL("von dir zugeordnet", "Ersatzsender, den du für die Liga gewählt hast (bzw. dessen Reihe).", guessed = true),
    CONFLICT(
        "widerspricht den Senderechten",
        "Eine Quelle nennt den Sender, aber laut Rechte-Tabelle überträgt seine Senderfamilie diesen Wettbewerb hier nicht.",
        guessed = true,
    ),
    ;

    companion object {
        /** Unter diesem Score wird nichts angezeigt (Event-Sender ohne Beweis, widerlegte Sender). */
        const val DISPLAY_MIN = 45

        /** Score eines Senders, der der Rechte-Tabelle widerspricht (sichtbar, aber gekennzeichnet). */
        const val RIGHTS_CONFLICT = 46

        fun of(score: Int): MatchSource = when {
            score >= 105 -> CONFIRMED
            score >= 90 -> GUIDE
            score >= 85 -> STATION
            score >= 80 -> EPG
            score >= 70 -> GUIDE_WEAK
            score >= 55 -> KEYWORD
            score >= 50 -> MANUAL
            else -> CONFLICT
        }
    }
}
