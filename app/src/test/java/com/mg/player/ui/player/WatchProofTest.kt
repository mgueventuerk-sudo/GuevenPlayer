package com.mg.player.ui.player

import com.mg.player.domain.model.Channel
import org.junit.Assert.assertEquals
import org.junit.Test

/** Plan „Spiel-Finder“ H, Test 10: 5 Minuten Zuschauen zählen als Beweis, 1 Minute nicht. */
class WatchProofTest {

    private var now = 0L
    private val confirmed = mutableListOf<String>()
    private val proof = WatchProof({ confirmed += it.id }, { now })
    private fun channel(id: String) = Channel(id, "Sender", null, "http://x/$id", "", "", null, null, null, playlistId = 1)

    @Test
    fun fiveMinutesCountOneDoesNot() {
        proof.onTick(channel("a"), playing = true)
        now += 60_000
        proof.onTick(channel("a"), playing = true)
        assertEquals(emptyList<String>(), confirmed)
        now += WatchProof.WATCH_MS
        proof.onTick(channel("a"), playing = true)
        proof.onTick(channel("a"), playing = true)
        assertEquals(listOf("a"), confirmed)
    }

    @Test
    fun pauseOrZapResets() {
        proof.onTick(channel("a"), playing = true)
        now += 4 * 60_000
        proof.onTick(channel("a"), playing = false)
        proof.onTick(channel("a"), playing = true)
        now += 2 * 60_000
        proof.onTick(channel("b"), playing = true)
        now += 2 * 60_000
        proof.onTick(channel("b"), playing = true)
        assertEquals(emptyList<String>(), confirmed)
    }
}
