package com.mg.player.core.device

import com.mg.player.player.engine.SharedLibVlc
import com.mg.player.domain.model.AppSettings
import com.mg.player.domain.model.BufferProfile
import com.mg.player.domain.model.HwDecoding
import com.mg.player.domain.model.PlayerEngineType
import com.mg.player.domain.model.forAutomaticOptimization
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceTierTest {
    @Test
    fun tiers() {
        assertEquals(DeviceTier.LOW, DeviceTier.of(lowRam = true, largeHeapMb = 512, totalRamMb = 4_096))
        assertEquals(DeviceTier.LOW, DeviceTier.of(lowRam = false, largeHeapMb = 384, totalRamMb = 1_024)) // Fire TV Stick 2. Gen.
        assertEquals(DeviceTier.NORMAL, DeviceTier.of(lowRam = false, largeHeapMb = 512, totalRamMb = 2_048))
        assertEquals(DeviceTier.HIGH, DeviceTier.of(lowRam = false, largeHeapMb = 512, totalRamMb = 4_096))
        assertTrue(DeviceTier.LOW.imageCacheBytes < DeviceTier.NORMAL.imageCacheBytes)
    }

    @Test
    fun deblockingOnlyOffOnWeakDevices() {
        assertTrue("--avcodec-skiploopfilter=1" in SharedLibVlc.options(DeviceTier.LOW))
        assertFalse("--avcodec-skiploopfilter=1" in SharedLibVlc.options(DeviceTier.NORMAL))
    }

    @Test
    fun automaticPlaybackPolicyAdaptsToTierWithoutChangingSecurityOrUserData() {
        val baseline = AppSettings(
            liveEngine = PlayerEngineType.LIBVLC,
            vodEngine = PlayerEngineType.NATIVE,
            bufferProfile = BufferProfile.FAST,
            hwDecoding = HwDecoding.OFF,
            tunneling = true,
            audioPassthrough = true,
            playbackSupervisor = false,
            onlineExtras = false,
            dohFallback = false,
        )

        val weak = baseline.forAutomaticOptimization(DeviceTier.LOW)
        val strong = baseline.forAutomaticOptimization(DeviceTier.HIGH)
        assertEquals(BufferProfile.STANDARD, weak.bufferProfile)
        assertEquals(BufferProfile.STABLE, strong.bufferProfile)
        listOf(weak, strong).forEach { settings ->
            assertEquals(PlayerEngineType.MEDIA3, settings.liveEngine)
            assertEquals(PlayerEngineType.MEDIA3, settings.vodEngine)
            assertEquals(HwDecoding.AUTO, settings.hwDecoding)
            assertFalse(settings.tunneling)
            assertFalse(settings.audioPassthrough)
            assertTrue(settings.playbackSupervisor)
            assertFalse(settings.onlineExtras)
            assertFalse(settings.dohFallback)
        }
    }

    @Test
    fun manualModePreservesAllPlaybackChoices() {
        val manual = AppSettings(
            automaticOptimization = false,
            liveEngine = PlayerEngineType.LIBVLC,
            bufferProfile = BufferProfile.FAST,
            tunneling = true,
        )
        assertEquals(manual, manual.forAutomaticOptimization(DeviceTier.LOW))
    }
}
