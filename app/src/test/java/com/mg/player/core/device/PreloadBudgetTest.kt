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
        storage: Long = 5_000,
    ) = MemoryReading(
        availMb = avail,
        thresholdMb = threshold,
        lowMemory = low,
        heapFreeMb = heapFree,
        trimmedRecently = trimmed,
        storageFreeMb = storage,
    )

    private fun decide(tier: DeviceTier, memory: MemoryReading, enabled: Boolean = true) = PreloadPolicyRules.decide(tier, memory, enabled)

    @Test
    fun plentyOfMemoryOnANormalDeviceGivesTheFullBudget() {
        val normal = PreloadBudget(PreloadLevel.FULL, 30, warmMemory = true, keepBehind = 15, diskAhead = 120, streamsOk = true)
        val high = PreloadBudget(PreloadLevel.FULL, 60, warmMemory = true, keepBehind = 30, diskAhead = 120, streamsOk = true)
        assertEquals(normal, decide(DeviceTier.NORMAL, memory()))
        assertEquals(high, decide(DeviceTier.HIGH, memory()))
    }

    @Test
    fun weakDevicesNeverGetMoreThanTheLightBudget() {
        // Mit viel Platz darf die Platte bis 30 voraus (Rechenzeit auf schwachen Geräten), der Arbeitsspeicher bleibt unberührt.
        assertEquals(PreloadBudget(PreloadLevel.LIGHT, 12, diskAhead = 30), decide(DeviceTier.LOW, memory(avail = 4_000, heapFree = 400)))
    }

    @Test
    fun lessRoomMeansLessPreloadingInsteadOfNone() {
        // 250 MB über der Schwelle reichen für „leicht“, aber nicht für „voll“.
        val budget = decide(DeviceTier.NORMAL, memory(avail = 450, threshold = 200))
        assertEquals(PreloadBudget(PreloadLevel.LIGHT, 18, diskAhead = 120, streamsOk = true), budget)
        // Genug System-Speicher, aber wenig freier Heap: ebenfalls nur „leicht“.
        assertEquals(PreloadLevel.LIGHT, decide(DeviceTier.HIGH, memory(heapFree = 60)).level)
    }

    @Test
    fun systemWarningsTurnPreloadingOffOnEveryDevice() {
        DeviceTier.entries.forEach { tier ->
            assertFalse("Android meldet wenig Speicher", decide(tier, memory(low = true)).active)
            assertFalse("kürzlich Speicherwarnung", decide(tier, memory(trimmed = true)).active)
            assertFalse("Heap fast voll", decide(tier, memory(heapFree = 20)).active)
            assertFalse("Platz im internen Speicher fast voll", decide(tier, memory(storage = 499)).active)
        }
    }

    @Test
    fun tightRamFallsBackToTheDiskInsteadOfOff() {
        // Unter Schwelle + Reserve: kein Arbeitsspeicher, aber der interne Speicher lädt wenige Bilder voraus.
        DeviceTier.entries.forEach { tier ->
            val budget = decide(tier, memory(avail = 380, threshold = 200))
            assertEquals(PreloadLevel.LIGHT, budget.level)
            assertFalse(budget.warmMemory)
            assertTrue(budget.diskAhead > 0)
        }
        // Heap knapp (unter 48 MB, über 24 MB): ebenfalls nur Platte, und nur das kleine Fenster.
        val budget = decide(DeviceTier.NORMAL, memory(heapFree = 40))
        assertEquals(PreloadBudget(PreloadLevel.LIGHT, 18, diskAhead = 18), budget)
    }

    @Test
    fun theDiskReachesFurtherTheMoreSpaceIsFree() {
        val tight = decide(DeviceTier.NORMAL, memory(storage = 500))
        val roomy = decide(DeviceTier.NORMAL, memory(storage = 1_024))
        val plenty = decide(DeviceTier.NORMAL, memory(storage = 3_072))
        assertEquals(listOf(30, 60, 120), listOf(tight.diskAhead, roomy.diskAhead, plenty.diskAhead))
        // Grenze 500 MB ist inklusive, 499 MB ist aus (siehe oben).
        assertTrue(tight.active)
    }

    @Test
    fun theDiskNeverReachesLessThanTheMemoryWindow() {
        DeviceTier.entries.forEach { tier ->
            listOf(500L, 1_024L, 3_072L, 50_000L).forEach { storage ->
                val budget = decide(tier, memory(storage = storage))
                assertTrue("$tier/$storage", budget.diskAhead >= budget.imagesAhead)
            }
        }
    }

    @Test
    fun diskOnlyNeverTouchesTheMemoryEvenWhenThereIsPlenty() {
        DeviceTier.entries.forEach { tier ->
            val budget = PreloadPolicyRules.decide(tier, memory(avail = 4_000, heapFree = 400), enabled = true, diskOnly = true)
            assertEquals(PreloadLevel.LIGHT, budget.level)
            assertFalse(tier.name, budget.warmMemory)
            assertTrue(tier.name, budget.diskAhead >= budget.imagesAhead)
        }
        // Mit viel Platz reicht die Platte weit, auch wenn der Arbeitsspeicher knapp ist (er wird ja nicht gebraucht).
        val tightRam = PreloadPolicyRules.decide(DeviceTier.NORMAL, memory(avail = 380, threshold = 200), enabled = true, diskOnly = true)
        assertEquals(PreloadBudget(PreloadLevel.LIGHT, 18, diskAhead = 120), tightRam)
    }

    @Test
    fun diskOnlyStillRespectsTheSafetyStops() {
        fun decideDisk(m: MemoryReading, enabled: Boolean = true) = PreloadPolicyRules.decide(DeviceTier.HIGH, m, enabled, diskOnly = true)
        assertFalse(decideDisk(memory(low = true)).active)
        assertFalse(decideDisk(memory(trimmed = true)).active)
        assertFalse(decideDisk(memory(storage = 499)).active)
        assertFalse(decideDisk(memory(heapFree = 20)).active)
        assertFalse(decideDisk(memory(), enabled = false).active)
    }

    @Test
    fun theDiskCacheGrowsWithFreeSpaceButStaysBounded() {
        val mb = 1024L * 1024L
        assertEquals(64 * mb, DiskCachePlan.maxBytes(100))
        assertEquals(128 * mb, DiskCachePlan.maxBytes(500))
        assertEquals(256 * mb, DiskCachePlan.maxBytes(1_024))
        assertEquals(512 * mb, DiskCachePlan.maxBytes(3_072))
        assertEquals(512 * mb, DiskCachePlan.maxBytes(1_000_000))
        assertEquals(128 * mb, DiskCachePlan.maxBytes(1_023))
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

    @Test
    fun onlyTheFullBudgetWarmsTheMemory() {
        assertTrue(decide(DeviceTier.NORMAL, memory()).warmMemory)
        // „leicht“ und „aus“ belegen keinen Arbeitsspeicher für Vorgeladenes.
        assertFalse(decide(DeviceTier.NORMAL, memory(avail = 450, threshold = 200)).warmMemory)
        assertFalse(decide(DeviceTier.NORMAL, memory(avail = 380, threshold = 200)).warmMemory)
        assertFalse(decide(DeviceTier.LOW, memory(avail = 4_000, heapFree = 400)).warmMemory)
        assertFalse(decide(DeviceTier.HIGH, memory(low = true)).warmMemory)
        assertEquals(0, decide(DeviceTier.HIGH, memory(low = true)).keepBehind)
    }

    @Test
    fun anExtraStreamNeedsRamButNotTheFullBudgetAndNeverOnWeakDevices() {
        // Fire TV (mittel): zusätzlicher Stream, sobald der Arbeitsspeicher „gut“ ist – nicht erst bei der großen Stufe.
        assertTrue(decide(DeviceTier.NORMAL, memory(avail = 450, threshold = 200)).streamsOk)
        assertTrue(decide(DeviceTier.HIGH, memory()).streamsOk)
        // Schwache Geräte nie, auch nicht bei viel RAM.
        assertFalse(decide(DeviceTier.LOW, memory(avail = 4_000, heapFree = 400)).streamsOk)
        // Knapper RAM oder Heap, Speicherwarnung, Aus: kein Stream auf Vorrat.
        assertFalse(decide(DeviceTier.NORMAL, memory(avail = 380, threshold = 200)).streamsOk)
        assertFalse(decide(DeviceTier.NORMAL, memory(heapFree = 40)).streamsOk)
        assertFalse(decide(DeviceTier.HIGH, memory(trimmed = true)).streamsOk)
        assertFalse(decide(DeviceTier.HIGH, memory(), enabled = false).streamsOk)
    }

    @Test
    fun diskOnlyNeverPreloadsStreams() {
        // „Nur interner Speicher“ betrifft nur Bilder, aber auch es lädt keinen zusätzlichen Stream – ein Decoder ist Arbeitsspeicher.
        val budget = PreloadPolicyRules.decide(DeviceTier.HIGH, memory(), enabled = true, diskOnly = true)
        assertFalse(budget.streamsOk)
    }
}
