package com.mg.player.integration

import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.mg.player.core.PlayerApp
import com.mg.player.domain.model.EpgProgram
import com.mg.player.domain.model.PlaylistType
import com.mg.player.domain.model.Reminder
import com.mg.player.domain.repository.PlaylistInput
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Vorher sicherte BackupManager.exportPlain() nur Playlists/Favoriten/Fortschritt/Intro-Profile/Kategorien -
 * eine geplante Aufnahme oder eine Erinnerung war nach dem Wiederherstellen auf einem neuen Gerät kommentarlos
 * weg. Dieser Test beweist den ganzen Weg: planen, sichern, auf denselben Zugriff wiederherstellen (bekommt dabei
 * eine neue Playlist-ID wie bei einem echten Gerätewechsel) - beides muss mit umgerechneter Sender-ID wieder da sein.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class BackupRecordingsRemindersTest {

    private val server = FakeXtreamServer()
    private val container get() = ApplicationProvider.getApplicationContext<PlayerApp>().container

    @After
    fun tearDown() = server.shutdown()

    @Test
    fun scheduledRecordingAndReminderSurviveBackupRoundTrip() {
        val playlistId = runBlocking {
            val id = container.playlistRepository.addPlaylist(
                PlaylistInput(
                    name = "Alt",
                    type = PlaylistType.XTREAM,
                    serverUrl = server.start(),
                    username = "demo",
                    password = "demo",
                    allowInsecureHttp = true,
                ),
            )
            container.playlistRepository.refreshContent(id) {}
            id
        }
        val oldChannelId = "$playlistId:L:101"
        val channel = runBlocking { container.liveRepository.channel(oldChannelId)!! }

        val now = System.currentTimeMillis()
        val start = now + 2 * 3_600_000L
        val end = start + 1_800_000L

        // schedule()/restoreScheduled() warten auf RecordingManager.awaitReady() (appScope, Dispatchers.Main.immediate) -
        // das braucht hier einen gepumpten Haupt-Looper von außen (gleiches Muster wie BackupRestoreOnDeviceTest.awaitUntil).
        val scheduleProblem = runOnBackgroundThread { container.recordingManager.schedule(channel, EpgProgram("Tatort", null, start, end)) }
        assertTrue("Planen sollte ohne Konflikt klappen: $scheduleProblem", scheduleProblem == null)
        container.reminders.toggle(Reminder(oldChannelId, channel.name, "Tatort", start))

        val encrypted = runBlocking { container.backupManager.export("testkennwort123") }
        val restoredPlaylistId = runOnBackgroundThread { container.backupManager.restore(encrypted, "testkennwort123") }
        assertTrue("Wiederherstellen sollte eine aktive Playlist liefern", restoredPlaylistId != null)

        val newPlaylistId = runBlocking { container.playlistRepository.observePlaylists().first().single().id }
        val newChannelId = "$newPlaylistId:L:101"

        assertTrue(
            "Geplante Aufnahme sollte mit neuer Playlist-ID wiederhergestellt sein",
            container.recordingManager.recordings.value.any { it.channelId == newChannelId && it.title == "Tatort" && it.isScheduled },
        )
        assertTrue(
            "Erinnerung sollte mit neuer Playlist-ID wiederhergestellt sein",
            container.reminders.items.value.any { it.channelId == newChannelId && it.title == "Tatort" },
        )
    }

    /** Lässt [block] auf einem eigenen Thread laufen und pumpt bis dahin den simulierten Haupt-Looper. */
    private fun <T> runOnBackgroundThread(block: suspend () -> T): T {
        val result = AtomicReference<Result<T>?>()
        Thread { result.set(runCatching { runBlocking { block() } }) }.apply { isDaemon = true; start() }
        val deadline = System.currentTimeMillis() + 10_000
        while (result.get() == null && System.currentTimeMillis() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(10)
        }
        return (result.get() ?: error("Zeitüberschreitung beim Warten auf den Hintergrund-Thread")).getOrThrow()
    }
}
