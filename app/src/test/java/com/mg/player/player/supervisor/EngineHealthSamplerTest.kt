package com.mg.player.player.supervisor

import com.mg.player.player.engine.HealthSnapshot
import com.mg.player.player.engine.PlaybackStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EngineHealthSamplerTest {

    @Test
    fun throwingHealthApiFallsBackAndLogsOnlyOnceUntilItRecovers() {
        val messages = mutableListOf<String>()
        val sampler = EngineHealthSampler { messages += it }
        val fallback = HealthSnapshot(PlaybackStatus.BUFFERING, false, 0)

        repeat(2) {
            assertEquals(fallback, sampler.sample({ error("engine health failed") }, { fallback }))
        }
        assertEquals(1, messages.size)
        assertTrue(messages.single().contains("IllegalStateException"))

        val measured = HealthSnapshot(PlaybackStatus.READY, true, 1_000, renderedFrames = 25)
        assertEquals(measured, sampler.sample({ measured }, { fallback }))
        assertTrue(messages.last().contains("wieder verfügbar"))
    }

    @Test
    fun missingHealthAndFallbackDoesNotFabricateAState() {
        val messages = mutableListOf<String>()
        val sampler = EngineHealthSampler { messages += it }

        assertNull(sampler.sample({ null }, { null }))
        assertTrue(messages.single().contains("Engine-Messwerte fehlen"))
    }
}
