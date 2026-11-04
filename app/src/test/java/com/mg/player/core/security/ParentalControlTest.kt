package com.mg.player.core.security

import com.mg.player.domain.model.AppSettings
import com.mg.player.domain.repository.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Kindersicherung: Umstellung alter PIN-Werte und PIN-Kopie bei beschädigter Einstellungsdatei. */
class ParentalControlTest {

    private class FakeSettings(initial: AppSettings = AppSettings()) : SettingsRepository {
        val state = MutableStateFlow(initial)
        override val settings: StateFlow<AppSettings> = state
        override suspend fun current() = state.value
        override suspend fun update(transform: (AppSettings) -> AppSettings) {
            state.value = transform(state.value)
        }
    }

    private class MemoryBackup(var value: String? = null) : PinBackup {
        override fun read() = value
        override fun write(value: String?) {
            this.value = value
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @After
    fun tearDown() = scope.cancel()

    @Test
    fun legacyPinIsUpgradedAfterCorrectEntry() = runBlocking {
        val settings = FakeSettings(AppSettings(parentalPinHash = PinHasher.legacy("1234")))
        val backup = MemoryBackup()
        val control = ParentalControl(settings, scope, backup)
        delay(200)
        assertFalse(control.verify("9999"))
        assertTrue(PinHasher.isLegacy(settings.state.value.parentalPinHash!!))
        assertTrue(control.verify("1234"))
        val upgraded = settings.state.value.parentalPinHash!!
        assertTrue(upgraded.startsWith("pbkdf2:"))
        assertTrue(PinHasher.matches("1234", upgraded))
        assertEquals("Kopie folgt dem neuen Wert", upgraded, backup.value)
    }

    @Test
    fun pinSurvivesResetSettingsFile() = runBlocking {
        val stored = PinHasher.hash("1234", iterations = 1_000)
        // Einstellungsdatei war beschädigt und ist jetzt leer – die Kopie hat die PIN noch.
        val settings = FakeSettings(AppSettings(parentalPinHash = null))
        val control = ParentalControl(settings, scope, MemoryBackup(stored), settingsWereReset = { true })
        withTimeout(5_000) { while (control.hasPin.value != true) delay(10) }
        assertEquals(stored, settings.state.value.parentalPinHash)
        assertTrue(control.pinRequired())
        assertFalse(control.verify("0000"))
        assertTrue(control.verify("1234"))
    }

    @Test
    fun removedPinStaysRemoved() = runBlocking {
        val settings = FakeSettings()
        val backup = MemoryBackup()
        val control = ParentalControl(settings, scope, backup)
        control.setPin("2468")
        assertTrue(PinHasher.matches("2468", backup.value!!))
        control.removePin()
        assertNull(backup.value)
        // Ohne Beschädigung wird nichts aus einer (leeren) Kopie zurückgeholt.
        val again = ParentalControl(settings, scope, backup, settingsWereReset = { false })
        delay(200)
        assertFalse(again.hasPin.value)
    }

    @Test
    fun lockoutSurvivesRestart() = runBlocking {
        val stored = PinHasher.hash("1234", iterations = 1_000)
        val settings = FakeSettings(AppSettings(parentalPinHash = stored))
        val attempts = PinAttempts.Memory()
        var wall = 1_000_000L
        val control = ParentalControl(settings, scope, MemoryBackup(stored), attempts = attempts, wallClock = { wall })
        repeat(ParentalControl.MAX_FAILURES) { assertFalse(control.verify("0000")) }
        assertTrue(control.secondsBlocked() > 0)
        // App beendet und neu gestartet: weiter gesperrt, auch die richtige PIN geht nicht.
        val restarted = ParentalControl(settings, scope, MemoryBackup(stored), attempts = attempts, wallClock = { wall })
        assertTrue(restarted.secondsBlocked() in 1..30)
        assertFalse(restarted.verify("1234"))
        // Nach Ablauf (Uhrzeit weiter) wieder möglich; richtige PIN setzt den Zähler zurück.
        wall += 31_000L
        val later = ParentalControl(settings, scope, MemoryBackup(stored), attempts = attempts, wallClock = { wall })
        assertEquals(0, later.secondsBlocked())
        assertTrue(later.verify("1234"))
        assertEquals(0, attempts.read().first)
    }
}
