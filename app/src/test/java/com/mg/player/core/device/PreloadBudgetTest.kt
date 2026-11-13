package com.mg.player.core.device

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Wann vorgeladen werden darf – und wie viel. Die Grenzwerte sind Startwerte (nicht am Gerät gemessen). */
class PreloadBudgetTest {

    private fun memory(
        avail: Long = 1_200,
        threshold: Long = 200,
        low: Boolean = false,
        heapFree: Long = 200,
        trimmed: Boolean = false,
    ) = MemoryReading(availMb = avail, thresholdMb = threshold, lowMemory = low, heapFreeMb = heapFree, trimmedRecently = trimmed)

    private fun decide(tier: DeviceTier, memory: MemoryReading, enabled: Boolean = true) = PreloadPolicyRules.decide(tier, memory, enabled)

    @Test
    fun plentyOfMemoryOnANormalDeviceGivesTheFullBudget() {
        assertEquals(PreloadBudget(PreloadLevel.FULL, 30), decide(DeviceTier.NORMAL, memory()))
        assertEquals(PreloadBudget(PreloadLevel.FULL, 60), decide(DeviceTier.HIGH, memory()))
    }

    @Test
    fun weakDevicesNeverGetMoreThanTheLightBudget() {
        assertEquals(PreloadBudget(PreloadLevel.LIGHT, 12), decide(DeviceTier.LOW, memory(avail = 4_000, heapFree = 400)))
    }

    @Test
    fun lessRoomMeansLessPreloadingInsteadOfNone() {
        // 250 MB über der Schwelle reichen für „leicht“, aber nicht für „voll“.
        val budget = decide(DeviceTier.NORMAL, memory(avail = 450, threshold = 200))
        assertEquals(PreloadBudget(PreloadLevel.LIGHT, 18), budget)
        // Genug System-Speicher, aber wenig freier Heap: ebenfalls nur „leicht“.
        assertEquals(PreloadLevel.LIGHT, decide(DeviceTier.HIGH, memory(heapFree = 60)).level)
    }

    @Test
    fun tightMemoryTurnsPreloadingOffOnEveryDevice() {
        DeviceTier.entries.forEach { tier ->
            assertFalse("Android meldet wenig Speicher", decide(tier, memory(low = true)).active)
            assertFalse("kürzlich Speicherwarnung", decide(tier, memory(trimmed = true)).active)
            assertFalse("unter Schwelle + Reserve", decide(tier, memory(avail = 380, threshold = 200)).active)
            assertFalse("Heap fast voll", decide(tier, memory(heapFree = 40)).active)
        }
    }

    @Test
    fun theSettingTurnsEverythingOff() {
        assertEquals(PreloadBudget.OFF, decide(DeviceTier.HIGH, memory(), enabled = false))
        assertTrue(decide(DeviceTier.HIGH, memory(), enabled = true).active)
    }

    @Test
    fun boundariesAreInclusiveWhereTheyAreDocumented() {
        // Genau Schwelle + 200 MB und 48 MB Heap: gerade noch erlaubt (leicht).
        assertEquals(PreloadLevel.LIGHT, decide(DeviceTier.NORMAL, memory(avail = 400, threshold = 200, heapFree = 48)).level)
        // Genau Schwelle + 500 MB und 96 MB Heap: voll.
        assertEquals(PreloadLevel.FULL, decide(DeviceTier.NORMAL, memory(avail = 700, threshold = 200, heapFree = 96)).level)
    }
}
