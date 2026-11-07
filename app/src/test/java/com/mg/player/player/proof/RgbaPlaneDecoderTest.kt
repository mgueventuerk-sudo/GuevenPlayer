package com.mg.player.player.proof

import java.nio.ByteBuffer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RgbaPlaneDecoderTest {

    @Test
    fun readsRowsWhenFinalPaddingIsAbsent() {
        val bytes = byteArrayOf(
            0x11, 0x22, 0x33, 0x44, 0x55, 0x66, 0x77, 0x22,
            0, 0, 0, 0,
            0x21, 0x32, 0x43, 0x54, 0x65, 0x76, 0x17, 0x28,
        )

        val pixels = RgbaPlaneDecoder.pixels(ByteBuffer.wrap(bytes), 2, 2, 12, 4)

        assertArrayEquals(intArrayOf(0x44112233, 0x22556677, 0x54213243, 0x28657617), pixels)
    }

    @Test
    fun rejectsTruncatedOrInvalidPlane() {
        val bytes = ByteBuffer.allocateDirect(19)
        assertNull(RgbaPlaneDecoder.pixels(bytes, 2, 2, 12, 4))
        assertNull(RgbaPlaneDecoder.pixels(bytes, 2, 2, 7, 4))
        assertNull(RgbaPlaneDecoder.pixels(bytes, 2, 2, 12, 3))
    }

    @Test
    fun respectsBufferPositionAndPixelStride() {
        val bytes = ByteBuffer.allocateDirect(13)
        bytes.put(1, 0x01)
        bytes.put(2, 0x02)
        bytes.put(3, 0x03)
        bytes.put(4, 0x04)
        bytes.put(9, 0x05)
        bytes.put(10, 0x06)
        bytes.put(11, 0x07)
        bytes.put(12, 0x08)
        bytes.position(1)

        assertArrayEquals(intArrayOf(0x04010203, 0x08050607), RgbaPlaneDecoder.pixels(bytes, 2, 1, 16, 8))
    }
}
