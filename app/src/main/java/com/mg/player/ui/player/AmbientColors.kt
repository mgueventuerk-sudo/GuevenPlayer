package com.mg.player.ui.player

import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Ambient-Licht: Farben aus einem stark verkleinerten Videobild (ARGB-Pixel), reines Kotlin und damit ohne Gerät
 * testbar. Links und rechts getrennt wie bei einem Ambilight; schwarze Balken (Letterbox) und sehr dunkle Pixel
 * zählen nicht, kräftige Farben zählen mehr als Grau.
 */
internal object AmbientColors {

    /** Unterhalb dieser Helligkeit (0–255, hellster Kanal) gilt ein Pixel als schwarz. */
    private const val BLACK_LEVEL = 24

    /** Weniger als dieser Anteil verwertbarer Pixel je Hälfte: keine eigene Farbe (dunkle Szene, Schwarzbild). */
    private const val MIN_COVERAGE = 0.08f

    /** (links, rechts) als ARGB oder null, wenn das Bild kaum Farbe hat. Fehlt eine Hälfte, gilt die andere für beide. */
    fun extract(pixels: IntArray, width: Int, height: Int): Pair<Int, Int>? {
        require(width > 1 && height > 0 && pixels.size >= width * height) { "Bildgröße passt nicht zu den Pixeln" }
        val half = width / 2
        val left = Accumulator()
        val right = Accumulator()
        for (y in 0 until height) {
            val row = y * width
            for (x in 0 until width) {
                val p = pixels[row + x]
                val r = (p shr 16) and 0xFF
                val g = (p shr 8) and 0xFF
                val b = p and 0xFF
                val max = maxOf(r, g, b)
                val acc = if (x < half) left else right
                acc.total++
                if (max < BLACK_LEVEL) continue
                val saturation = (max - minOf(r, g, b)) / max.toFloat()
                // Grau trägt etwas bei (sonst flackert es bei Nachrichtenstudios), Farbe deutlich mehr.
                acc.add(r, g, b, 0.15f + saturation * (max / 255f))
            }
        }
        val l = left.color()
        val r = right.color()
        return when {
            l != null && r != null -> l to r
            l != null -> l to l
            r != null -> r to r
            else -> null
        }
    }

    /**
     * Zähmt eine Bildfarbe für den Hintergrund: Sättigung und Helligkeit in einem ruhigen Bereich, damit Text lesbar
     * bleibt und grelle Szenen (Werbung, Explosionen) die Oberfläche nicht überstrahlen. Ergebnis: deckendes ARGB.
     */
    fun tame(argb: Int, dark: Boolean): Int {
        val hsl = toHsl(argb)
        val s = hsl[1].coerceIn(0.25f, 0.80f)
        val l = if (dark) hsl[2].coerceIn(0.35f, 0.55f) else hsl[2].coerceIn(0.50f, 0.68f)
        return fromHsl(hsl[0], s, l)
    }

    private class Accumulator {
        var total = 0
        private var count = 0
        private var weight = 0f
        private var r = 0f
        private var g = 0f
        private var b = 0f

        fun add(red: Int, green: Int, blue: Int, w: Float) {
            count++
            weight += w
            r += red * w
            g += green * w
            b += blue * w
        }

        fun color(): Int? {
            if (total == 0 || weight <= 0f || count < total * MIN_COVERAGE) return null
            return argb((r / weight).roundToInt(), (g / weight).roundToInt(), (b / weight).roundToInt())
        }
    }

    private fun argb(r: Int, g: Int, b: Int): Int =
        (0xFF shl 24) or (r.coerceIn(0, 255) shl 16) or (g.coerceIn(0, 255) shl 8) or b.coerceIn(0, 255)

    /** [Farbton 0–360, Sättigung 0–1, Helligkeit 0–1]. */
    internal fun toHsl(argb: Int): FloatArray {
        val r = ((argb shr 16) and 0xFF) / 255f
        val g = ((argb shr 8) and 0xFF) / 255f
        val b = (argb and 0xFF) / 255f
        val max = maxOf(r, g, b)
        val min = minOf(r, g, b)
        val l = (max + min) / 2f
        if (max == min) return floatArrayOf(0f, 0f, l)
        val d = max - min
        val s = if (l > 0.5f) d / (2f - max - min) else d / (max + min)
        val h = when (max) {
            r -> ((g - b) / d + (if (g < b) 6f else 0f))
            g -> ((b - r) / d + 2f)
            else -> ((r - g) / d + 4f)
        } * 60f
        return floatArrayOf(h, s, l)
    }

    internal fun fromHsl(h: Float, s: Float, l: Float): Int {
        val c = (1f - abs(2f * l - 1f)) * s
        val hp = (h % 360f + 360f) % 360f / 60f
        val x = c * (1f - abs(hp % 2f - 1f))
        val (r1, g1, b1) = when {
            hp < 1f -> Triple(c, x, 0f)
            hp < 2f -> Triple(x, c, 0f)
            hp < 3f -> Triple(0f, c, x)
            hp < 4f -> Triple(0f, x, c)
            hp < 5f -> Triple(x, 0f, c)
            else -> Triple(c, 0f, x)
        }
        val m = l - c / 2f
        return argb(((r1 + m) * 255f).roundToInt(), ((g1 + m) * 255f).roundToInt(), ((b1 + m) * 255f).roundToInt())
    }
}
