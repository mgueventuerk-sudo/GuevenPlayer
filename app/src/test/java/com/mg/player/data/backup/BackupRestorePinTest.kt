package com.mg.player.data.backup

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.mg.player.core.security.PinHasher
import com.mg.player.data.db.AppDatabase
import com.mg.player.domain.model.AppSettings
import com.mg.player.domain.repository.PlaylistRepository
import com.mg.player.domain.repository.SettingsRepository
import java.lang.reflect.Proxy
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** Eine hochgeladene Sicherung ohne PIN darf Kindersicherung und App-Sperre nicht abschalten. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class BackupRestorePinTest {

    private class FakeSettings(initial: AppSettings) : SettingsRepository {
        val state = MutableStateFlow(initial)
        override val settings: StateFlow<AppSettings> = state
        override suspend fun current() = state.value
        override suspend fun update(transform: (AppSettings) -> AppSettings) {
            state.value = transform(state.value)
        }
    }

    private val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AppDatabase::class.java).build()

    /** Leere Datenbank: Restore legt nur den einen Zugang aus der Sicherung an. */
    private val noPlaylists = Proxy.newProxyInstance(
        PlaylistRepository::class.java.classLoader,
        arrayOf(PlaylistRepository::class.java),
    ) { _, method, _ ->
        if (method.name == "addPlaylist") 1L else error("Unerwarteter Aufruf: ${method.name}")
    } as PlaylistRepository

    private fun backup(settings: String) =
        """{"format":"${BackupData.FORMAT}","playlists":[{"id":7,"name":"A","type":"M3U","m3uUrl":"http://x/a.m3u"}],"settings":$settings}"""

    private val pin = PinHasher.hash("1234")

    @After
    fun tearDown() = db.close()

    @Test
    fun backupWithoutPinKeepsPinAndAppLock() = runBlocking {
        val settings = FakeSettings(AppSettings(parentalPinHash = pin, appLock = true))
        BackupManager(db, noPlaylists, settings).restore(backup("""{"appLock":false}"""), null)
        assertEquals(pin, settings.current().parentalPinHash)
        assertTrue(settings.current().appLock)
    }

    @Test
    fun backupWithPinStillReplacesIt() = runBlocking {
        val other = PinHasher.hash("9876")
        val settings = FakeSettings(AppSettings(parentalPinHash = pin, appLock = true))
        BackupManager(db, noPlaylists, settings).restore(backup("""{"parentalPinHash":"$other","appLock":false}"""), null)
        assertEquals(other, settings.current().parentalPinHash)
        assertFalse(settings.current().appLock)
    }
}
