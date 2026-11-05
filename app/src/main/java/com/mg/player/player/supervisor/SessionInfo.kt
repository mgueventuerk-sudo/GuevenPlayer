package com.mg.player.player.supervisor

import com.mg.player.domain.model.PlayerEngineType

/** Was gerade läuft – bestimmt, welche Maßnahmen möglich sind. */
data class SessionInfo(
    /** Eindeutig je Inhalt (Sender, Film, Folge) – Schlüssel fürs Gedächtnis. */
    val contentKey: String,
    /** Anbieter/Zugang – für den Schutzschalter. */
    val providerKey: String?,
    val isLive: Boolean,
    /** Gibt es noch eine andere Variante/Quelle dieses Senders? */
    val canSwitchVariant: Boolean,
    val engine: PlayerEngineType,
)

/**
 * Grenzwerte (Startwerte aus dem Plan, mit Messungen nachzustellen). Alle Zeiten in Millisekunden.
 * Mit Ersatzquelle reagiert der Wächter schneller, weil ein Wechsel billig ist.
 */
data class SupervisorConfig(
    val startNoDataWithAlternative: Long = 3_000,
    val startNoDataLive: Long = 12_000,
    val startNoDataVod: Long = 20_000,
    val startNoPictureWithAlternative: Long = 3_000,
    val startNoPicture: Long = 8_000,
    /** Live mit Ersatzquelle: 2 s ohne neue Daten reichen (vorher 6 s – zu lang). */
    val stallWithAlternative: Long = 2_000,
    /**
     * Live mit Ersatzquelle: spätestens dann ist es ein Hänger – auch wenn noch Daten kommen, die Engine keine Datenmenge
     * meldet oder die Position steht (ab 3.51). Mit dem Prüftakt von 500 ms wird so nach höchstens 3 s gewechselt.
     */
    val stallWithAlternativeMax: Long = 2_500,
    val stallLive: Long = 20_000,
    val stallVod: Long = 30_000,
    val frozenPicture: Long = 8_000,
    /** So viele Ton-Ausgabefehler in Folge gelten als echter Tonausfall (einer allein ist ein Ausrutscher). */
    val audioErrorBurst: Int = 3,
    val audioWithoutVideo: Long = 6_000,
    val videoWithoutAudio: Long = 5_000,
    val radioHint: Long = 3_000,
    val desyncThreshold: Long = 400,
    val desyncDuration: Long = 10_000,
    val judderWindow: Long = 10_000,
    val judderRatio: Double = 0.15,
    val judderMinFrames: Long = 50,
    val liveDriftThreshold: Long = 45_000,
    val liveDriftDuration: Long = 10_000,
    /** Nach einer Maßnahme so lange keine neuen Befunde der Detektoren (Fehler der Engine zählen weiter). */
    val cooldown: Long = 10_000,
    /** Live mit Ersatzquelle: Hängt auch die nächste Variante, nicht 10 s warten (ab 3.51). */
    val cooldownWithAlternative: Long = 3_000,
    val maxActionsPerSession: Int = 6,
    val maxActionsPerMinute: Int = 3,
    /** So lange stabile Wiedergabe setzt Zähler zurück und merkt, was geholfen hat. */
    val stablePlayback: Long = 30_000,
    val networkSettle: Long = 1_500,
)
