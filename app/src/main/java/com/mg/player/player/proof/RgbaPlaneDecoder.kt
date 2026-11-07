package com.mg.player.player.proof

import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Kopiert nur vorhandene RGBA-Pixel; der letzte ImageReader-Zeilenabstand fehlt oft im Buffer. */
@Suppress("MagicNumber") // RGBA-Kanalbreite und ARGB-Bitpositionen sind Teil des festen Pixelformats.
internal object RgbaPlaneDecoder {
    fun pixels(buffer: ByteBuffer, width: Int, height: Int, rowStride: Int, pixelStride: Int): IntArray? {
        if (width <= 0 || height <= 0) return null
        if (pixelStride < 4 || rowStride.toLong() < width.toLong() * pixelStride) return null
        val pixelCount = width.toLong() * height
        if (pixelCount > Int.MAX_VALUE) return null
        val start = buffer.position()
        val required = start.toLong() + (height - 1L) * rowStride + (width - 1L) * pixelStride + 4L
        if (required > buffer.limit()) return null

        val source = buffer.duplicate().order(ByteOrder.LITTLE_ENDIAN)
        return IntArray(pixelCount.toInt()).also { pixels ->
            for (y in 0 until height) {
                val row = start + y * rowStride
                for (x in 0 until width) {
                    val rgba = source.getInt(row + x * pixelStride)
                    pixels[y * width + x] = (rgba and -0x1000000) or
                        ((rgba and 0xff) shl 16) or (rgba and 0xff00) or ((rgba ushr 16) and 0xff)
                }
            }
        }
    }
}
