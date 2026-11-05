package com.mg.player.domain.model

/**
 * Woher die Zuordnung Spiel → Sender stammt (ab 3.53, Plan „Spiel-Finder“ Baustein A). Abgeleitet aus dem Score der
 * Zuordnung (+1 Bonus für den aktiven Zugang ist berücksichtigt). [switchable]: lässt sich in den Einstellungen abschalten.
 */
enum class MatchSource(val label: String, val detail: String, val guessed: Boolean = false, val switchable: Boolean = false) {
    PROVEN("● bewiesen", "Im Bild des Senders geprüft: Spielstand-Einblendung passt zum Live-Stand – oder du hast hier zugeschaut."),
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

        /** Score eines im Bild bewiesenen Senders (über allem anderen). */
        const val PROVEN_SCORE = 120

        /** Score eines widerlegten Senders (Platzhalter, anderes Programm, „Falscher Sender“) – nie angezeigt. */
        const val DISPROVEN_SCORE = 20

        fun of(score: Int): MatchSource = when {
            score >= MIN_PROVEN -> PROVEN
            score >= MIN_CONFIRMED -> CONFIRMED
            score >= MIN_GUIDE -> GUIDE
            score >= MIN_STATION -> STATION
            score >= MIN_EPG -> EPG
            score >= MIN_GUIDE_WEAK -> GUIDE_WEAK
            score >= MIN_KEYWORD -> KEYWORD
            score >= MIN_MANUAL -> MANUAL
            else -> CONFLICT
        }

        // Untergrenzen der Bänder (Scores der Quellen, +1 Bonus für den aktiven Zugang eingerechnet).
        private const val MIN_PROVEN = 115
        private const val MIN_CONFIRMED = 105
        private const val MIN_GUIDE = 90
        private const val MIN_STATION = 85
        private const val MIN_EPG = 80
        private const val MIN_GUIDE_WEAK = 70
        private const val MIN_KEYWORD = 55
        private const val MIN_MANUAL = 50
    }
}
