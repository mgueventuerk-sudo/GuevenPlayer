package com.mg.player.ui.player

import com.mg.player.domain.model.AppSettings
import com.mg.player.domain.model.PlayRequest
import com.mg.player.domain.model.PlayerEngineType
import com.mg.player.player.engine.EngineState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EngineFallbackSecurityTest {

    @Test
    fun secureHttpsStreamStaysOnMedia3ForSubrequestChecks() {
        val playable = playable("https://cdn.example/live.m3u8")

        assertEquals(PlayerEngineType.MEDIA3, EngineFallback.engineFor(PlayerEngineType.LIBVLC, playable))
        assertEquals(
            PlayerEngineType.MEDIA3,
            EngineFallback.initialEngine(PlayRequest.Live("playlist:L:1", "all"), playable, AppSettings(liveEngine = PlayerEngineType.LIBVLC)),
        )
        assertFalse(EngineFallback.canSwitchTo(PlayerEngineType.EXTERNAL, playable))
        assertFalse(EngineFallback().shouldSwitch(EngineState(formatUnsupported = true), playable, PlayerEngineType.MEDIA3))
    }

    @Test
    fun explicitlyAllowedIptvCanUseOtherEngines() {
        val playable = playable("https://cdn.example/live.m3u8", allowInsecureHttp = true)

        assertEquals(PlayerEngineType.LIBVLC, EngineFallback.engineFor(PlayerEngineType.LIBVLC, playable))
        assertTrue(EngineFallback.canSwitchTo(PlayerEngineType.EXTERNAL, playable))
        assertTrue(EngineFallback().shouldSwitch(EngineState(formatUnsupported = true), playable, PlayerEngineType.MEDIA3))
    }

    private fun playable(url: String, allowInsecureHttp: Boolean = false) = Playable(
        kind = PlayableKind.LIVE,
        id = "playlist:L:1",
        title = "Test",
        subtitle = null,
        url = url,
        startPositionMs = 0,
        progressKey = null,
        allowInsecureHttp = allowInsecureHttp,
    )
}
