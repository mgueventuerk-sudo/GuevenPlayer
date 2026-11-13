package com.mg.player.player.supervisor

/**
 * Was der Wiedergabe-Wächter erkennen kann. [code] entspricht dem Katalog im Plan
 * (claude/Plan-Wiedergabe-Waechter-und-Clean-Code.md §2.3) und steht so im Fehlerprotokoll.
 */
enum class Symptom(val code: String, val label: String, val informational: Boolean = false) {
    START_NO_DATA("S1", "Start ohne Daten"),
    START_NO_PICTURE("S2", "Daten, aber keine Wiedergabe"),
    STALL("S3", "Hänger beim Puffern"),
    FROZEN_PICTURE("S4", "Standbild"),
    AUDIO_WITHOUT_VIDEO("S5", "Ton ohne Bild"),

    /**
     * Ton läuft, aber der Player hat gar keine Videospur gefunden – und es spricht etwas dafür, dass der Sender eigentlich
     * ein Bild haben müsste (er hatte schon einmal eins, oder es kommen viel mehr Daten an, als ein Radiosender braucht).
     */
    NO_VIDEO_TRACK("S5t", "Ton ohne Videospur"),
    VIDEO_WITHOUT_AUDIO("S6", "Bild ohne Ton"),
    AV_DESYNC("S7", "Bild und Ton versetzt"),
    JUDDER("S8", "Ruckeln", informational = true),
    LIVE_DRIFT("S9", "Live-Abstand wächst"),
    LIVE_ENDED("S11", "Live-Stream beendet"),
    AUTH("S12", "Zugang verweigert"),
    NOT_FOUND("S13", "Nicht gefunden"),
    RATE_LIMITED("S14", "Server überlastet"),
    NETWORK("S15", "Verbindungsfehler"),
    DECODER("S16", "Decoder-Fehler"),
    FORMAT("S16f", "Format nicht abspielbar"),
    DEVICE_PRESSURE("S18", "Gerät überlastet"),

    /** Sender hat gar keine Videospur (Radio): keine Maßnahme, nur Hinweis. */
    RADIO_ONLY("S5i", "Nur Ton", informational = true),

    /** Sender hat gar keine Tonspur: keine Maßnahme, nur Hinweis. */
    NO_AUDIO_TRACK("S6i", "Ohne Ton", informational = true),
}

/**
 * Ein Befund eines Detektors: was, und die Messwerte, die dazu geführt haben (fürs Protokoll).
 * [confident] = false: Der Befund beruht auf einer Vermutung (z. B. „vielleicht ein Radiosender“) – der Wächter probiert
 * dann nur wenig und merkt sich das Ergebnis, statt die ganze Leiter abzuarbeiten.
 */
data class Finding(val symptom: Symptom, val evidence: String, val confident: Boolean = true)
