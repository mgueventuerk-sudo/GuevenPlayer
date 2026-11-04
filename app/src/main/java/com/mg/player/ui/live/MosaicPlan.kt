package com.mg.player.ui.live

/**
 * Regeln des Live-Mosaiks, reines Kotlin (ohne Gerät testbar).
 *
 * Ein einziger Abtaster läuft reihum über die Kacheln: Sender öffnen, erstes Bild abwarten, Standbild sichern,
 * nächster Sender. So belegt das Mosaik immer nur eine Verbindung beim Anbieter – auch bei Zugängen, die nur einen
 * gleichzeitigen Stream erlauben – und dekodiert nie mehr als ein Video (schwache TV-Sticks).
 */
internal object MosaicPlan {

    /** Eine Runde über alle Kacheln dauert mindestens so lange (Standbilder alle 30 s neu). */
    const val ROUND_MS = 30_000L

    /** So lange wartet der Abtaster höchstens auf das erste Bild eines Senders. */
    const val FIRST_FRAME_TIMEOUT_MS = 8_000L

    /** Nach dem ersten Bild kurz weiterlaufen lassen: Das allererste Bild ist oft noch unscharf oder grau. */
    const val SETTLE_MS = 450L

    val SIZES = listOf(4, 9)

    /** Größe beim Öffnen: 3×3, auf schwachen Geräten 2×2 (dort dauert jeder Senderwechsel länger). */
    fun defaultSize(lowTier: Boolean): Int = if (lowTier) 4 else 9

    /**
     * Sender für das Mosaik: ab [startId] (dem markierten Sender) die nächsten [size] der Gruppe; reicht das Ende der
     * Liste nicht, geht es vorne weiter. Jeder Sender höchstens einmal.
     */
    fun pick(ids: List<String>, startId: String?, size: Int): List<String> {
        if (ids.isEmpty() || size <= 0) return emptyList()
        val start = ids.indexOf(startId).coerceAtLeast(0)
        return (0 until minOf(size, ids.size)).map { ids[(start + it) % ids.size] }
    }

    /** Wartezeit nach einer Runde, damit eine Runde nie kürzer als [ROUND_MS] ist (schont Anbieter und Datenvolumen). */
    fun restAfterRound(roundStartedMs: Long, nowMs: Long, roundMs: Long = ROUND_MS): Long =
        (roundMs - (nowMs - roundStartedMs)).coerceIn(0L, roundMs)

    /** Startseite: Standbilder alle 2 min, höchstens 5 Runden je Besuch (Datenvolumen, Anbieter-Verbindungen). */
    const val HOME_ROUND_MS = 120_000L
    const val HOME_MAX_ROUNDS = 5

    /** Startseite: so viele Sender in der Zeile „Läuft gerade“ (schwache Geräte weniger). */
    fun homeCount(lowTier: Boolean): Int = if (lowTier) 4 else 8

    /** Größe beim Umschalten (2×2 ↔ 3×3). */
    fun toggled(size: Int): Int = if (size == SIZES.first()) SIZES.last() else SIZES.first()
}
