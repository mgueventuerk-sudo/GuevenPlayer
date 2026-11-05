package com.mg.player.data.sport.proof

import kotlin.math.abs

/**
 * Standbild-Erkennung (Plan „Spiel-Finder“ C): Platzhalter wie „Kein Event“ mit Musik zeigen über Sekunden dasselbe
 * Bild. Verglichen werden kleine Graustufen-Raster (z. B. 32×18) mehrerer Aufnahmen; ändert sich praktisch nichts, ist es
 * ein Standbild. Rein, damit testbar.
 */
object FrameDiff {

    /** Mittlere Helligkeitsänderung je Bildpunkt (0–255), unter der zwei Aufnahmen als gleich gelten. */
    private const val STATIC_THRESHOLD = 2.0

    /** Graustufen aus ARGB-Pixeln (Gewichte 0,3 / 0,6 / 0,1 für Rot, Grün, Blau). */
    fun gray(argb: IntArray): IntArray = IntArray(argb.size) { i ->
        val c = argb[i]
        val r = c shr RED_SHIFT and CHANNEL
        val g = c shr GREEN_SHIFT and CHANNEL
        val b = c and CHANNEL
        (r * RED_WEIGHT + g * GREEN_WEIGHT + b * BLUE_WEIGHT) / WEIGHT_SUM
    }

    private const val RED_SHIFT = 16
    private const val GREEN_SHIFT = 8
    private const val CHANNEL = 0xFF
    private const val RED_WEIGHT = 3
    private const val GREEN_WEIGHT = 6
    private const val BLUE_WEIGHT = 1
    private const val WEIGHT_SUM = 10

    /** true, wenn sich zwischen allen aufeinanderfolgenden Aufnahmen kaum etwas ändert (mindestens 2 Aufnahmen). */
    fun isStatic(frames: List<IntArray>): Boolean {
        if (frames.size < 2) return false
        return frames.zipWithNext().all { (a, b) -> meanDiff(a, b) < STATIC_THRESHOLD }
    }

    internal fun meanDiff(a: IntArray, b: IntArray): Double {
        if (a.size != b.size || a.isEmpty()) return Double.MAX_VALUE
        var sum = 0L
        for (i in a.indices) sum += abs(a[i] - b[i])
        return sum.toDouble() / a.size
    }
}
