package com.mg.player.data.sync

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.ListenableWorker
import androidx.work.testing.TestListenableWorkerBuilder
import com.mg.player.core.PlayerApp
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
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
}
