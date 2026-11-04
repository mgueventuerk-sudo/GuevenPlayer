package com.mg.player.domain.model

/**
 * Countdown der automatischen Komplett-Aktualisierung (Inhalte + EPG) je Zugang.
 *
 * Grundlage ist die gespeicherte Uhrzeit der letzten erfolgreichen Aktualisierung (`lastUpdatedMs`, Datenbank) – nicht
 * ein laufender Zähler. Deshalb zählt auch die Zeit, in der App oder Gerät aus waren: Fällig ist ein Zugang genau dann,
 * wenn seit der letzten Aktualisierung mindestens `days` × 24 h vergangen sind. Eine Aktualisierung von Hand startet
 * den Countdown ebenfalls neu, ein Fehlschlag nicht (der Zugang bleibt fällig).
 *
 * Schutz gegen falsche Geräteuhr (Fire TV nach dem Einschalten, bevor die Zeit aus dem Netz kommt):
 * - Uhr offensichtlich falsch (vor [MIN_VALID_CLOCK_MS]): nichts entscheiden, später erneut prüfen.
 * - Letzte Aktualisierung liegt mehr als [CLOCK_TOLERANCE_MS] in der Zukunft (damals ging die Uhr vor): Der
 *   Countdown ist nicht verlässlich – sofort fällig, danach zählt er wieder richtig.
 */
object AutoRefreshSchedule {
    const val DAY_MS = 24L * 60 * 60 * 1000
    const val MAX_DAYS = 365

    /** 01.01.2024 – jede echte Uhrzeit liegt danach. */
    const val MIN_VALID_CLOCK_MS = 1_704_067_200_000L
    const val CLOCK_TOLERANCE_MS = 60L * 60 * 1000

    fun isEnabled(days: Int): Boolean = days in 1..MAX_DAYS

    /** Zeitpunkt, zu dem der Countdown endet; null = aus. Noch nie geladen oder unzuverlässige Uhrzeit: jetzt. */
    fun dueAt(lastUpdatedMs: Long, days: Int, nowMs: Long): Long? {
        if (!isEnabled(days)) return null
        if (lastUpdatedMs <= 0L) return nowMs
        if (lastUpdatedMs > nowMs + CLOCK_TOLERANCE_MS && clockValid(nowMs)) return nowMs
        return lastUpdatedMs + days * DAY_MS
    }

    fun isDue(lastUpdatedMs: Long, days: Int, nowMs: Long): Boolean {
        if (!clockValid(nowMs)) return false
        val due = dueAt(lastUpdatedMs, days, nowMs) ?: return false
        return nowMs >= due
    }

    fun clockValid(nowMs: Long): Boolean = nowMs >= MIN_VALID_CLOCK_MS
}
