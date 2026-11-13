package com.mg.player.data.sync

import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.ListenableWorker
import androidx.work.testing.TestListenableWorkerBuilder
import com.mg.player.core.PlayerApp
import com.mg.player.domain.model.PlaylistType
import com.mg.player.domain.repository.PlaylistInput
import com.mg.player.screenshots.ShowcaseServer
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class NightlyWorkTest {

    @Test
    fun vacuumOnlyWhenWorthIt() {
        val page = 4096L
        // 40 % frei, 40 MB frei, genug Platz: ja.
        assertTrue(DbMaintenance.shouldVacuum(pageCount = 25_000, freePages = 10_000, pageSize = page, usableBytes = 1_000_000_000))
        // Nur 10 % frei: nein.
        assertFalse(DbMaintenance.shouldVacuum(pageCount = 25_000, freePages = 2_500, pageSize = page, usableBytes = 1_000_000_000))
        // Kleine Datenbank (unter 16 MB frei): nein.
        assertFalse(DbMaintenance.shouldVacuum(pageCount = 2_000, freePages = 1_000, pageSize = page, usableBytes = 1_000_000_000))
        // Zu wenig Speicher für die temporäre Kopie: nein.
        assertFalse(DbMaintenance.shouldVacuum(pageCount = 25_000, freePages = 10_000, pageSize = page, usableBytes = 100_000_000))
    }

    @Test
    fun workerRunsWithoutPlaylists() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<PlayerApp>()
        val worker = TestListenableWorkerBuilder<NightlyWorker>(app).build()
        assertEquals(ListenableWorker.Result.success(), worker.doWork())
    }

    /**
     * Vorher lief die Stichwort-Autoaufnahme (AutoRecorder.keepPlanned()) nur, solange die App offen war
     * (AppRoot.kt) – ohne den Nachtjob frieren Regeln ein, sobald man die App tagelang nicht öffnet.
     *
     * doWork() hängt an zwei Stellen von appScope ab (SyncManager, RecordingManager - beide auf
     * Dispatchers.Main.immediate). Läuft der Test selbst in runBlocking() auf dem simulierten Hauptthread,
     * blockiert das gegenseitig (Robolectric pumpt den Looper sonst nirgends). Deshalb hier derselbe Kniff wie
     * in BackupRestoreOnDeviceTest.awaitUntil(): doWork() auf einem eigenen Thread laufen lassen und den
     * Hauptthread von außen leer laufen lassen ("Looper.idle()"), statt alles in ein runBlocking zu packen.
     */
    @Test
    fun autoRecorderPlansFromNightlyWorkerWithoutOpeningTheApp() {
        val app = ApplicationProvider.getApplicationContext<PlayerApp>()
        val container = app.container
        val server = ShowcaseServer()
        try {
            runBlocking {
                val playlistId = container.playlistRepository.addPlaylist(
                    PlaylistInput(
                        name = "Nacht",
                        type = PlaylistType.XTREAM,
                        serverUrl = server.start(),
                        username = "u",
                        password = "p",
                        allowInsecureHttp = true,
                    ),
                )
                container.playlistRepository.refreshContent(playlistId) {}
                // EPG schon vorab laden: Sonst hält der Worker selbst für shouldAutoLoad == true und ruft
                // syncManager.refreshEpg(...).join() auf - ein zweiter appScope-Join, hier nicht Gegenstand des Tests.
                container.epgRepository.refresh(container.playlistRepository.getPlaylist(playlistId)!!) {}
                container.settingsRepository.update { it.copy(activePlaylistId = playlistId) }
                container.autoRecorder.add("Bergdoktor")
            }

            val worker = TestListenableWorkerBuilder<NightlyWorker>(app).build()
            val result = java.util.concurrent.atomic.AtomicReference<ListenableWorker.Result>()
            Thread {
                result.set(runBlocking { worker.doWork() })
            }.apply { isDaemon = true; start() }

            awaitUntil { result.get() != null }
            assertEquals(ListenableWorker.Result.success(), result.get())

            assertTrue(
                "NightlyWorker sollte die Stichwortregel einplanen, ohne dass die App je geöffnet wurde",
                container.recordingManager.recordings.value.any { it.title.contains("Bergdoktor") && it.isScheduled },
            )
        } finally {
            server.shutdown()
        }
    }

    /** Pumpt bei jedem Versuch den (simulierten) Haupt-Looper - sonst bleibt eine fertige Hintergrundarbeit dort liegen. */
    private fun awaitUntil(timeoutMs: Long = 10_000, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!condition() && System.currentTimeMillis() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(10)
        }
    }
}
