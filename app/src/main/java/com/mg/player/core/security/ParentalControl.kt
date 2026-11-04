package com.mg.player.core.security

import android.os.SystemClock
import com.mg.player.core.log.AppLog
import com.mg.player.domain.model.Category
import com.mg.player.domain.repository.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Kindersicherung: Gesperrte Kategorien sind erst nach Eingabe der PIN zugänglich.
 *
 * - Die Freischaltung gilt, bis die App in den Hintergrund geht (Home-Taste, Standby) oder die Sperre manuell
 *   wieder aktiviert wird. Vorher galt sie, bis der Prozess endete – auf einem Fernseher oft tagelang.
 * - Solange beim Start noch nicht feststeht, ob eine PIN gesetzt ist, gilt "gesperrt" (vorher war das kurze
 *   Fenster nach dem Start offen).
 * - Nach [MAX_FAILURES] Fehlversuchen wird die Eingabe kurz gesperrt (30 s, dann länger) – 4 Ziffern lassen
 *   sich sonst in wenigen Minuten durchprobieren.
 */
class ParentalControl(
    private val settings: SettingsRepository,
    scope: CoroutineScope,
    private val backup: PinBackup = PinBackup.None,
    private val settingsWereReset: () -> Boolean = { false },
    private val clock: () -> Long = { SystemClock.elapsedRealtime() },
    /** Fehlversuche über Neustarts hinweg (Standard: nur im Speicher, z. B. in Tests). */
    private val attempts: PinAttempts = PinAttempts.Memory(),
    private val wallClock: () -> Long = System::currentTimeMillis,
) {

    private val _unlocked = MutableStateFlow(false)
    val unlocked: StateFlow<Boolean> = _unlocked.asStateFlow()

    /** Erst nach dem Abgleich mit der PIN-Kopie gilt der Stand als geladen (bis dahin: „gesperrt“). */
    private val checked = MutableStateFlow(false)

    /** null = noch nicht geladen. */
    private val pinState: StateFlow<Boolean?> = combine(settings.settings, checked) { s, ready -> if (ready) s.parentalPinHash != null else null }
        .stateIn(scope, SharingStarted.Eagerly, null)

    init {
        scope.launch {
            val stored = settings.current().parentalPinHash
            val copy = backup.read()
            when {
                // Einstellungsdatei war beschädigt und wurde geleert: PIN aus der Kopie zurückholen.
                stored == null && copy != null && settingsWereReset() -> {
                    settings.update { it.copy(parentalPinHash = copy) }
                    AppLog.log("Kindersicherung", "Einstellungsdatei beschädigt – zurückgesetzt, Kindersicherung bleibt aktiv")
                }

                // Kopie nachziehen (z. B. nach dem Update oder nach einer Wiederherstellung aus der Sicherung).
                stored != copy -> backup.write(stored)
            }
            checked.value = true
        }
    }

    val hasPin: StateFlow<Boolean> = pinState
        .map { it == true }
        .stateIn(scope, SharingStarted.Eagerly, false)

    private var failures = 0
    private var blockedUntil = 0L

    init {
        // Gespeicherte Sperre übernehmen. Höchstens [MAX_BLOCK_MS] – eine verstellte Uhr sperrt nicht für Tage.
        val (storedFailures, untilWall) = attempts.read()
        failures = storedFailures
        val remaining = (untilWall - wallClock()).coerceIn(0, MAX_BLOCK_MS)
        if (remaining > 0) blockedUntil = clock() + remaining
    }

    fun isAccessible(category: Category): Boolean = !category.locked || _unlocked.value || pinState.value == false

    /** Geschützte Einstellungen: Muss (möglicherweise) die PIN abgefragt werden? */
    fun pinRequired(): Boolean = pinState.value != false

    /** Sekunden, die nach zu vielen Fehlversuchen noch gewartet werden muss (0 = Eingabe möglich). */
    fun secondsBlocked(): Int = ((blockedUntil - clock()).coerceAtLeast(0) / 1000).toInt()

    suspend fun verify(pin: String): Boolean {
        if (secondsBlocked() > 0) return false
        val stored = settings.current().parentalPinHash ?: return true.also { _unlocked.value = true }
        val ok = withContext(Dispatchers.Default) { PinHasher.matches(pin, stored) }
        if (ok) {
            failures = 0
            attempts.write(0, 0L)
            _unlocked.value = true
            // Alten Wert ohne Salz beim ersten richtigen Eingeben ersetzen.
            if (PinHasher.isLegacy(stored)) storePin(withContext(Dispatchers.Default) { PinHasher.hash(pin) })
        } else {
            failures++
            var blockMs = 0L
            if (failures >= MAX_FAILURES) {
                val rounds = failures - MAX_FAILURES
                // 30 s, 1, 2, 4, 8, dann 16 min je weiterem Fehlversuch.
                blockMs = (BLOCK_MS shl rounds.coerceAtMost(5)).coerceAtMost(MAX_BLOCK_MS)
                blockedUntil = clock() + blockMs
            }
            attempts.write(failures, if (blockMs > 0) wallClock() + blockMs else 0L)
        }
        return ok
    }

    suspend fun setPin(pin: String) {
        storePin(withContext(Dispatchers.Default) { PinHasher.hash(pin) })
        _unlocked.value = false
    }

    suspend fun removePin() {
        storePin(null)
        _unlocked.value = false
    }

    private suspend fun storePin(value: String?) {
        settings.update { it.copy(parentalPinHash = value) }
        backup.write(value)
    }

    fun lockAgain() {
        _unlocked.value = false
    }

    companion object {
        const val PIN_LENGTH = 4
        const val MAX_FAILURES = 5
        private const val BLOCK_MS = 30_000L
        private const val MAX_BLOCK_MS = 16 * 60_000L
    }
}
