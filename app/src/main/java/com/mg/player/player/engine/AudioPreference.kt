package com.mg.player.player.engine

import com.mg.player.domain.model.AudioLanguages

/**
 * Bevorzugte Tonsprachen für alle Player (Einstellungen → Wiedergabe → Sprache). Prozessweit wie PreloadPolicy: Engines
 * entstehen an vielen Stellen (Player, Vorschau, Mosaik, Vorrat) – jede liest die Vorgabe beim Start eines Streams.
 * PlayerApp führt sie aus den Einstellungen nach.
 */
object AudioPreference {
    @Volatile var languages: List<String> = AudioLanguages.DEFAULT

    /** Kürzel in Vorrang-Reihenfolge („de, deu, ger, tr, tur“). */
    fun trackCodes(): List<String> = AudioLanguages.trackCodes(languages)
}
