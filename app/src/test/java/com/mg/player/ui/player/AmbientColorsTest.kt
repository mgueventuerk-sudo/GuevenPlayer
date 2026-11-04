package com.mg.player.ui.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AmbientColorsTest {

    private val width = 32
    private val height = 18

    private fun frame(pixel: (x: Int, y: Int) -> Int) = IntArray(width * height) { pixel(it % width, it / width) }

    private fun red(argb: Int) = (argb shr 16) and 0xFF
    private fun green(argb: Int) = (argb shr 8) and 0xFF
    private fun blue(argb: Int) = argb and 0xFF

    @Test
    fun blackFrameHasNoAmbientColor() {
        assertNull(AmbientColors.extract(frame { _, _ -> 0xFF000000.toInt() }, width, height))
    }

    @Test
    fun leftAndRightHalvesAreSeparate() {
        val (left, right) = requireNotNull(
            AmbientColors.extract(frame { x, _ -> if (x < width / 2) 0xFFE01010.toInt() else 0xFF1010E0.toInt() }, width, height),
        )
        assertTrue(red(left) > blue(left))
        assertTrue(blue(right) > red(right))
    }

    @Test
    fun letterboxBarsDoNotDarkenTheColor() {
        // Oben und unten je 4 Zeilen Schwarz (Kinoformat), dazwischen sattes Grün.
        val result = AmbientColors.extract(frame { _, y -> if (y < 4 || y >= height - 4) 0xFF000000.toInt() else 0xFF20C040.toInt() }, width, height)
        val (left, _) = requireNotNull(result)
        assertEquals(0x20, red(left))
        assertEquals(0xC0, green(left))
    }

    @Test
    fun oneDarkHalfTakesTheOtherHalf() {
        val result = AmbientColors.extract(frame { x, _ -> if (x < width / 2) 0xFF000000.toInt() else 0xFFF0A000.toInt() }, width, height)
        val (left, right) = requireNotNull(result)
        assertEquals(left, right)
    }

    @Test
    fun saturatedPixelsOutweighGrey() {
        // Drei Viertel Grau, ein Viertel Rot: Das Rot muss den Farbton bestimmen.
        val result = AmbientColors.extract(frame { x, _ -> if (x % 4 == 0) 0xFFFF0000.toInt() else 0xFF808080.toInt() }, width, height)
        val (left, _) = requireNotNull(result)
        assertTrue(red(left) > green(left) + 20)
    }

    @Test
    fun tameKeepsHueAndClampsLightness() {
        val tamed = AmbientColors.tame(0xFFFFFFF0.toInt(), dark = true)
        val hsl = AmbientColors.toHsl(tamed)
        assertTrue(hsl[2] in 0.34f..0.56f)
        val blue = AmbientColors.toHsl(AmbientColors.tame(0xFF0000FF.toInt(), dark = true))
        assertEquals(240f, blue[0], 1.5f)
        assertTrue(blue[1] <= 0.81f)
    }

    @Test
    fun hslRoundTrip() {
        listOf(0xFFE01010, 0xFF10E010, 0xFF1010E0, 0xFF808080, 0xFFC0A040).map { it.toInt() }.forEach { argb ->
            val hsl = AmbientColors.toHsl(argb)
            val back = AmbientColors.fromHsl(hsl[0], hsl[1], hsl[2])
            assertEquals(red(argb).toFloat(), red(back).toFloat(), 1f)
            assertEquals(green(argb).toFloat(), green(back).toFloat(), 1f)
            assertEquals(blue(argb).toFloat(), blue(back).toFloat(), 1f)
        }
    }

    @Test(expected = IllegalArgumentException::class)
    fun wrongSizeIsRejected() {
        AmbientColors.extract(IntArray(10), width, height)
    }

    @Test
    fun resultIsOpaque() {
        val (left, right) = requireNotNull(AmbientColors.extract(frame { _, _ -> 0x40336699 }, width, height))
        assertEquals(0xFF, (left ushr 24) and 0xFF)
        assertEquals(0xFF, (right ushr 24) and 0xFF)
    }
}
