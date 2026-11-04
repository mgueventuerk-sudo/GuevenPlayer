package com.mg.player.ui.player

import com.mg.player.domain.model.PlayerEngineType
import com.mg.player.player.engine.EngineState
import com.mg.player.player.engine.VideoPlayerEngine

/**
 * Wer bei Störungen der Wiedergabe entscheidet. Zwei Umsetzungen, umschaltbar in den Einstellungen
 * (Player → „Wiedergabe-Wächter“):
 * - [SupervisorGuard]: der Wiedergabe-Wächter (eine Zustandsmaschine für alle Symptome, Standard).
 * - [LegacyGuard]: die bisherige Logik (Neuversuche, Quellenwechsel, Standbild) als Rückweg.
 *
 * Der [PlayerViewModel] meldet nur Ereignisse; Entscheidungen über Neu-Verbinden, Quellen- und Engine-Wechsel
 * trifft allein der Guard.
 */
internal interface PlaybackGuard {
    /** Neuer Inhalt (Sender, Film, Folge), bevor er startet. */
    fun onContentStarted(playable: Playable)

    /** Die Wiedergabe des Inhalts beginnt (nach [onContentStarted] oder nach Rückkehr in den Vordergrund). */
    fun onPlaybackBegun(playable: Playable)

    /** Die Engine wurde (neu) vorbereitet – auch bei jedem Neu-Verbinden. */
    fun onEnginePrepared()

    /** Zustand der Engine; wird nicht aufgerufen, während ein Senderwechsel ansteht oder die App im Hintergrund ist. */
    suspend fun onEngineState(engine: VideoPlayerEngine, state: EngineState, playable: Playable?)

    /** Takt des sichtbaren Players: bei aktivem Start oder Eingriff 500 ms, bei stabiler Wiedergabe 1 s. */
    fun onTick(engine: VideoPlayerEngine)

    /** Andere Variante desselben Senders läuft jetzt. */
    fun onVariantSwitched(playable: Playable)

    /** Andere Engine läuft jetzt. */
    fun onEngineSwitched(type: PlayerEngineType)

    /** Der Nutzer greift ein (Quelle/Engine von Hand, „Weiter versuchen“): laufende Maßnahmen verwerfen. */
    fun onUserInterrupt()

    /** App im Hintergrund: nichts mehr unternehmen. */
    fun stop()

    fun release()
}
