package com.mg.player.player.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Kritischer Fall aus der Prüfung: Ohne Byte-Grenze lief der Puffer bei 4K-Streams in den Speicher-Absturz. */
class BufferLimitTest {

    private val mb = 1024 * 1024

    @Test
    fun lowRamDeviceGetsSmallBufferAndSizePriority() {
        val limit = BufferLimit.forDevice(heapMb = 512, lowRamDevice = true)
        assertEquals(24 * mb, limit.targetBytes)
        assertFalse(limit.prioritizeTime)
    }

    @Test
    fun smallHeapLimitsByteBuffer() {
        val limit = BufferLimit.forDevice(heapMb = 192, lowRamDevice = false)
        assertEquals((192 / 5) * mb, limit.targetBytes)
        assertFalse(limit.prioritizeTime)
    }

    @Test
    fun largeHeapIsCapped() {
        val limit = BufferLimit.forDevice(heapMb = 1024, lowRamDevice = false)
        assertEquals(80 * mb, limit.targetBytes)
        assertTrue(limit.prioritizeTime)
    }

    @Test
    fun neverBelowMinimum() {
        assertEquals(24 * mb, BufferLimit.forDevice(heapMb = 64, lowRamDevice = false).targetBytes)
    }
}
