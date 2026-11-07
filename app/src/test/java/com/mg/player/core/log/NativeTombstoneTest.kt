package com.mg.player.core.log

import java.io.ByteArrayInputStream
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeTombstoneTest {
    @Test fun decodesCrashingThreadInsteadOfPrintingBinary() {
        val signal = field(2, "SIGSEGV".toByteArray()) + field(4, "SEGV_ACCERR".toByteArray())
        val frame = field(4, "decoderCrash".toByteArray()) + field(6, "/system/lib64/libcodec.so".toByteArray())
        val thread = field(2, "MediaCodec_loop".toByteArray()) + field(4, frame)
        val entry = number(1, 42) + field(2, thread)
        val tombstone = number(6, 42) + field(10, signal) + field(16, entry)

        val report = NativeTombstone.summarize(ByteArrayInputStream(tombstone))

        assertTrue(report.contains("SIGSEGV / SEGV_ACCERR"))
        assertTrue(report.contains("Thread: MediaCodec_loop (TID 42)"))
        assertTrue(report.contains("#0 libcodec.so: decoderCrash"))
    }

    @Test fun malformedTraceProducesReadableMessage() {
        val report = NativeTombstone.summarize(ByteArrayInputStream(byteArrayOf(0x82.toByte())))
        assertTrue(report.contains("konnte nicht ausgewertet werden"))
    }

    private fun field(number: Int, value: ByteArray): ByteArray = varint((number * 8 + 2).toLong()) + varint(value.size.toLong()) + value
    private fun number(number: Int, value: Long): ByteArray = varint((number * 8).toLong()) + varint(value)

    private fun varint(value: Long): ByteArray {
        var remaining = value
        val bytes = ArrayList<Byte>()
        do {
            val next = (remaining and 0x7f).toInt()
            remaining = remaining ushr 7
            bytes += (next or if (remaining > 0) 0x80 else 0).toByte()
        } while (remaining > 0)
        return bytes.toByteArray()
    }
}
