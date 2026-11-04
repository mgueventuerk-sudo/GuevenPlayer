package com.mg.player

import android.os.SystemClock
import androidx.annotation.OptIn
import androidx.lifecycle.Lifecycle
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.decoder.ffmpeg.FfmpegLibrary
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.mg.player.core.PlayerApp
import com.mg.player.domain.model.BufferProfile
import com.mg.player.domain.model.HwDecoding
import com.mg.player.player.engine.BufferLimit
import com.mg.player.player.engine.Media3Build
import com.mg.player.player.engine.Media3Config
import com.mg.player.player.engine.SharedLibVlc
import com.mg.player.ui.MainActivity
import com.wireguard.android.backend.GoBackend
import okhttp3.OkHttpClient
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Rauchtests auf echtem Android (Emulator API 24/28/34 in der CI): Was Robolectric nicht prüfen kann –
 * Start der App, echte SQLite, native Bibliotheken (LibVLC, FFmpeg, WireGuard) und Media3 auf dem jeweiligen API-Level.
 */
@RunWith(AndroidJUnit4::class)
class DeviceSmokeTest {

    private val app get() = ApplicationProvider.getApplicationContext<PlayerApp>()

    @Test
    fun appStartsWithoutCrash() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.moveToState(Lifecycle.State.RESUMED)
            // Startarbeit (Datenbank, Worker, erste Oberfläche) laufen lassen – ein Absturz beendet den Testprozess.
            SystemClock.sleep(STARTUP_MS)
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            scenario.onActivity { assertFalse("Aktivität wurde beendet", it.isFinishing) }
        }
    }

    @Test
    fun databaseOpensOnDeviceSqlite() {
        val db = app.container.database
        db.openHelper.writableDatabase
        assertTrue(db.isOpen)
    }

    @Test
    fun libVlcNativeLibrariesLoad() {
        try {
            assertNotNull(SharedLibVlc.acquire(app))
        } finally {
            SharedLibVlc.releaseUse()
        }
    }

    @Test
    fun ffmpegDecoderLoads() {
        assertTrue("FFmpeg-Erweiterung nicht ladbar", FfmpegLibrary.isAvailable())
    }

    @OptIn(UnstableApi::class)
    @Test
    fun media3PlayerBuildsAndReleases() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val config = Media3Config(BufferProfile.entries.first(), HwDecoding.entries.first(), tunneling = false, passthrough = false)
            val player = Media3Build.player(app, config, BufferLimit.forDevice(heapMb = 192, lowRamDevice = false), OkHttpDataSource.Factory(OkHttpClient()))
            player.release()
        }
    }

    /** Lädt die Go-Bibliothek; auf Android 7–11 fiel eine frühere Version an einer Android-12-API. */
    @Test
    fun wireGuardBackendLoads() {
        assertNotNull(GoBackend(app).runningTunnelNames)
    }

    private companion object {
        const val STARTUP_MS = 4_000L
    }
}
