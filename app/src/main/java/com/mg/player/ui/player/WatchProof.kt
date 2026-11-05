package com.mg.player.ui.player

import com.mg.player.domain.model.Channel

/**
 * Plan „Spiel-Finder“ H: Läuft ein Live-Sender 5 Minuten ohne Unterbrechung, gilt er für die laufenden Spiele, für die er
 * Kandidat ist, als bewiesen (du hättest sonst umgeschaltet). Pause oder anderer Sender setzt die Zeit zurück.
 */
internal class WatchProof(private val onConfirmed: (Channel) -> Unit, private val clock: () -> Long) {
    private var channelId: String? = null
    private var since = 0L
    private var done = false

    fun onTick(channel: Channel?, playing: Boolean) {
        if (channel == null || !playing) {
            channelId = null
            return
        }
        if (channel.id != channelId) {
            channelId = channel.id
            since = clock()
            done = false
        } else if (!done && clock() - since >= WATCH_MS) {
            done = true
            onConfirmed(channel)
        }
    }

    companion object {
        const val WATCH_MS = 5 * 60_000L
    }
}
