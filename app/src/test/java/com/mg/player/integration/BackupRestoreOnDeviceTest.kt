package com.mg.player.integration

import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.mg.player.core.PlayerApp
import com.mg.player.domain.model.PlaylistType
import com.mg.player.domain.repository.PlaylistInput
import com.mg.player.ui.settings.ShareMode
import com.mg.player.ui.settings.ShareViewModel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Am Handy braucht eine Sicherung kein Zweitgerät mehr - ein echter Datei-Dialog ersetzt das Scannen des eigenen
 * Bildschirms mit der eigenen Kamera. ShareViewModel(touch = true) darf dafür (a) nie den Heimnetz-Server starten
 * und (b) eine direkt ausgewählte Datei genauso verarbeiten wie eine vom Fernseher empfangene.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class BackupRestoreOnDeviceTest {

    private val container get() = ApplicationProvider.getApplicationContext<PlayerApp>().container

    @Test
    fun touchBackupModesNeverStartTheHomeNetworkServer() {
        val vm = ShareViewModel(container, ShareMode.BACKUP_IMPORT, touch = true)
        shadowOf(Looper.getMainLooper()).idle()
        assertNull("Am Handy braucht es keinen Heimnetz-Server", vm.url.value)
        assertNull(vm.qr.value)
    }

    @Test
    fun pickedBackupFileGoesThroughTheSamePassphraseFlowAsTheNetworkPath() = runBlocking {
        container.playlistRepository.addPlaylist(
            PlaylistInput("Alt", PlaylistType.XTREAM, "https://anbieter.example", "user", "pw"),
        )
        val encrypted = container.backupManager.export("mein-testkennwort")

        val vm = ShareViewModel(container, ShareMode.BACKUP_IMPORT, touch = true)
        shadowOf(Looper.getMainLooper()).idle()

        vm.loadPickedBackup(encrypted)
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue("Verschlüsselte Sicherung muss zuerst nach dem Kennwort fragen", vm.awaitingPassphrase.value)
        assertEquals(false, vm.restored.value)

        vm.restorePending("mein-testkennwort")
        // restore() läuft auf Dispatchers.IO (echter Hintergrund-Thread) - das Robolectric-Main-Looper-idle()
        // wartet darauf nicht. Begrenztes Abfragen statt fester Pause: Die eigentliche Arbeit ist lokales
        // Room/JSON und in der Praxis in wenigen Millisekunden fertig.
        awaitUntil { vm.restored.value }
        assertTrue("Mit dem richtigen Kennwort muss die Sicherung übernommen werden", vm.restored.value)
    }

    /** Pumpt bei jedem Versuch den (simulierten) Haupt-Looper - sonst bleibt eine fertige Hintergrundarbeit dort liegen. */
    private fun awaitUntil(timeoutMs: Long = 5_000, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!condition() && System.currentTimeMillis() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(10)
        }
    }
}
