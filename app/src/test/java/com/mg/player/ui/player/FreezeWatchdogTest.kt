package com.mg.player.ui.player

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** „Bild steht, Ton läuft“ erkennen – aber nie bei Radiosendern oder pausierter Wiedergabe. */
class FreezeWatchdogTest {

    private var now = 0L
    private val watchdog = FreezeWatchdog { now }

    private fun tick(playing: Boolean, frames: Long?): Boolean {
        now += 500
        return watchdog.isFrozen(playing, frames)
    }

    @Test
    fun detectsFrozenPictureOnce() {
        var frames = 0L
        repeat(10) { assertFalse(tick(true, frames++)) }
        val stuck = frames
        var fired = 0
        repeat(40) { if (tick(true, stuck)) fired++ }
        assertTrue("genau einmal auslösen", fired == 1)
    }

    @Test
    fun neverForAudioOnlyPausedOrUnknown() {
        repeat(60) { assertFalse(tick(true, 0L)) }
        repeat(60) { assertFalse(tick(false, 5L)) }
        repeat(60) { assertFalse(tick(true, null)) }
    }
}
