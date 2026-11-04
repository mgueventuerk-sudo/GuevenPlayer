package com.mg.player.core.network

import java.io.ByteArrayInputStream
import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ThroughputGuardTest {

    @Test
    fun tricklingServerIsAborted() {
        var now = 0L
        // Je Lesevorgang 1 Byte, und zwischen den Lesevorgängen vergehen 10 s.
        val source = object : java.io.InputStream() {
            override fun read(): Int = 'x'.code.also { now += 10_000 }
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                b[off] = 'x'.code.toByte()
                now += 10_000
                return 1
            }
        }
        val guard = ThroughputGuard(source, minBytes = 1024, windowMs = 60_000, clock = { now })
        val error = runCatching { repeat(100) { guard.read(ByteArray(10)) } }.exceptionOrNull()
        assertTrue(error is IOException)
        assertTrue(now <= 70_000)
    }

    @Test
    fun normalDownloadPasses() {
        var now = 0L
        val data = ByteArray(1_000_000)
        val guard = ThroughputGuard(ByteArrayInputStream(data), minBytes = 1024, windowMs = 60_000, clock = { now.also { now += 1_000 } })
        var total = 0
        val buffer = ByteArray(8192)
        while (true) {
            val n = guard.read(buffer)
            if (n < 0) break
            total += n
        }
        assertEquals(data.size, total)
    }
}
