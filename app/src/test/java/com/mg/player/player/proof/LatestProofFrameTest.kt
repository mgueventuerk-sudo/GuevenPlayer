package com.mg.player.player.proof

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class LatestProofFrameTest {
    @Test
    fun copyKeepsImageAliveAndDrainsSkippedFrameAfterCallback() {
        val first = Frame()
        val next = Frame()
        val pending = ArrayDeque(listOf(first, next))
        var acquisitions = 0
        val frames = LatestProofFrame {
            acquisitions++
            pending.removeFirstOrNull()
        }

        frames.onFrame()
        frames.beginCopy()
        repeat(3) { frames.onFrame() }

        assertSame(first, frames.image)
        assertFalse(first.closed)
        assertEquals(1, acquisitions)

        frames.finishCopy()

        assertTrue(first.closed)
        assertSame(next, frames.image)
        assertFalse(next.closed)
        assertEquals(2, acquisitions)
        frames.close()
        assertTrue(next.closed)
    }

    @Test
    fun missingNewFrameKeepsCurrentImageUntilClose() {
        val first = Frame()
        var pending: Frame? = first
        val frames = LatestProofFrame { pending.also { pending = null } }
        frames.onFrame()
        frames.beginCopy()
        frames.finishCopy()
        assertSame(first, frames.image)
        assertFalse(first.closed)
        frames.close()
        assertTrue(first.closed)
    }

    @Test
    fun queuedCallbacksAfterCloseCannotAcquireAnImage() {
        val first = Frame()
        var acquisitions = 0
        val frames = LatestProofFrame {
            acquisitions++
            first
        }
        frames.onFrame()
        frames.beginCopy()
        frames.close()
        frames.finishCopy()
        frames.onFrame()
        frames.close()
        assertNull(frames.image)
        assertEquals(1, acquisitions)
        assertEquals(1, first.closes)
    }

    private class Frame : AutoCloseable {
        var closes = 0
        val closed: Boolean get() = closes > 0
        override fun close() {
            closes++
        }
    }
}
