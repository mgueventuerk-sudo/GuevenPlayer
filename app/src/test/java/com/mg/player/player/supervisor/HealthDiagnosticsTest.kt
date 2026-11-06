package com.mg.player.player.supervisor

import com.mg.player.domain.model.PlayerEngineType
import com.mg.player.player.engine.HealthSnapshot
import com.mg.player.player.engine.PlaybackStatus
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HealthDiagnosticsTest {

    @Test
    fun reportsPlaybackProgressWithoutStreamMetadata() {
        val diagnostics = HealthDiagnostics(intervalMs = 1_000)
        val first = HealthSnapshot(PlaybackStatus.READY, true, 1_000, bytesLoaded = 4_000, renderedFrames = 30, audioUnits = 40)
        val second = first.copy(positionMs = 2_000, bytesLoaded = 6_000, renderedFrames = 60, audioUnits = 80)

        val start = diagnostics.sample(PlayerEngineType.MEDIA3, first, 0).orEmpty()
        assertTrue(start.contains("Player-Zustand MEDIA3"))
        assertTrue(start.contains("Videobilder 30"))
        assertNull(diagnostics.sample(PlayerEngineType.MEDIA3, second, 999))
        val later = diagnostics.sample(PlayerEngineType.MEDIA3, second, 1_000).orEmpty()

        assertTrue(later.contains("Position 2000 ms (+1000 ms)"))
        assertTrue(later.contains("Videobilder 60 (+30, Standbildprüfung aktiv)"))
        assertTrue(later.contains("Tonblöcke 80 (+40)"))
        assertTrue(later.contains("Bytes 6000 (+2000)"))
        assertFalse(later.contains("http"))
    }

    @Test
    fun missingEngineCountersAreReportedAsUnknown() {
        val diagnostics = HealthDiagnostics(intervalMs = 1)
        val line = diagnostics.sample(
            PlayerEngineType.NATIVE,
            HealthSnapshot(PlaybackStatus.READY, true, 0),
            0,
        ).orEmpty()

        assertTrue(line.contains("Videobilder n/a (Δ n/a, Standbildprüfung eingeschränkt)"))
        assertTrue(line.contains("Standbildprüfung eingeschränkt"))
        assertTrue(line.contains("Tonblöcke n/a (Δ n/a)"))
        assertTrue(line.contains("Bytes n/a (Δ n/a)"))
        assertTrue(line.contains("Videodecoder-Warnungen n/a"))
    }

    @Test
    fun engineSwitchLogsAnImmediateSampleWithoutComparingUnrelatedCounters() {
        val diagnostics = HealthDiagnostics(intervalMs = 30_000)
        val media3 = HealthSnapshot(PlaybackStatus.READY, true, 10_000, renderedFrames = 500)
        val native = HealthSnapshot(PlaybackStatus.READY, true, 5_000)

        diagnostics.sample(PlayerEngineType.MEDIA3, media3, 0)
        val switched = diagnostics.sample(PlayerEngineType.NATIVE, native, 100).orEmpty()

        assertTrue(switched.contains("Player-Zustand NATIVE"))
        assertTrue(switched.contains("Position 5000 ms (Δ n/a)"))
        assertTrue(switched.contains("Videobilder n/a (Δ n/a"))
    }
}
