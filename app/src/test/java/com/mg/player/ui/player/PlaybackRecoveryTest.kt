package com.mg.player.ui.player

import com.mg.player.core.network.NetworkStatus
import com.mg.player.player.engine.ErrorKind
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PlaybackRecoveryTest {

    private val scope = TestScope()
    private val network = MutableStateFlow(NetworkStatus(online = true))
    private var retries = 0
    private var state = RecoveryState()
    private val recovery = PlaybackRecovery(scope, network, { retries++ }) { state = it }

    @Test
    fun `Netzwerkfehler werden mit wachsender Pause wiederholt`() {
        recovery.onError(ErrorKind.NETWORK, null)
        assertTrue(state.reconnecting)
        scope.advanceTimeBy(999)
        assertEquals(0, retries)
        scope.advanceTimeBy(2)
        assertEquals(1, retries)
        recovery.onError(ErrorKind.NETWORK, null)
        scope.advanceTimeBy(1_500)
        assertEquals(1, retries)
        scope.advanceTimeBy(600)
        assertEquals(2, retries)
    }

    @Test
    fun `403 gibt nach zwei Versuchen mit klarer Meldung auf`() {
        repeat(3) {
            recovery.onError(ErrorKind.AUTH, 403)
            scope.advanceUntilIdle()
        }
        assertEquals(2, retries)
        assertNotNull(state.failure)
        assertTrue(state.failure!!.contains("403"))
    }

    @Test
    fun `ohne Netz wird gewartet statt gezählt`() {
        network.value = NetworkStatus(online = false)
        recovery.onError(ErrorKind.NETWORK, null)
        scope.advanceTimeBy(60_000)
        assertEquals(0, retries)
        assertTrue(state.waitingForNetwork)
        network.value = NetworkStatus(online = true)
        scope.advanceUntilIdle()
        assertEquals(1, retries)
    }

    @Test
    fun `hängendes Puffern löst einen Neuversuch aus`() {
        recovery.onBuffering(isLive = true)
        scope.advanceTimeBy(PlaybackRecovery.LIVE_STALL_MS + 1_100)
        assertEquals(1, retries)
    }

    @Test
    fun `laufendes Bild setzt alles zurück`() {
        recovery.onBuffering(isLive = true)
        scope.runCurrent()
        recovery.onPlaying()
        scope.advanceTimeBy(PlaybackRecovery.LIVE_STALL_MS * 2)
        assertEquals(0, retries)
        assertNull(state.failure)
    }

    @Test
    fun `nach dem Aufgeben startet ein Netzwechsel neu`() {
        repeat(PlaybackRecovery.maxAttempts(ErrorKind.NETWORK) + 1) {
            recovery.onError(ErrorKind.NETWORK, null)
            scope.advanceUntilIdle()
        }
        assertNotNull(state.failure)
        val before = retries
        network.value = NetworkStatus(online = true, vpnForApp = true)
        scope.advanceUntilIdle()
        assertEquals(before + 1, retries)
    }

    /** Fall aus der Prüfung: Ein Stream, der immer nur kurz anläuft, wurde endlos im Sekundentakt neu verbunden. */
    @Test
    fun shortPlaybackDoesNotResetAttempts() {
        repeat(PlaybackRecovery.maxAttempts(ErrorKind.NETWORK)) {
            recovery.onError(ErrorKind.NETWORK, null)
            scope.advanceUntilIdle()
            // Bild läuft 5 Sekunden, dann bricht es wieder ab.
            recovery.onPlaying()
            scope.advanceTimeBy(5_000)
        }
        recovery.onError(ErrorKind.NETWORK, null)
        scope.advanceUntilIdle()
        assertNotNull("nach den erlaubten Versuchen muss Schluss sein", state.failure)
        assertEquals(PlaybackRecovery.maxAttempts(ErrorKind.NETWORK), retries)
    }

    @Test
    fun stablePlaybackResetsAttempts() {
        recovery.onError(ErrorKind.NETWORK, null)
        scope.advanceUntilIdle()
        assertEquals(1, recovery.attemptCount)
        recovery.onPlaying()
        scope.advanceTimeBy(PlaybackRecovery.STABLE_PLAYBACK_MS + 1)
        assertEquals(0, recovery.attemptCount)
    }

    /** Fall aus der Prüfung: Jede Schwankung der WLAN-Geschwindigkeit galt als "Netzwechsel" und startete neu. */
    @Test
    fun linkSpeedChangeIsNoNetworkChange() {
        repeat(PlaybackRecovery.maxAttempts(ErrorKind.NETWORK) + 1) {
            recovery.onError(ErrorKind.NETWORK, null)
            scope.advanceUntilIdle()
        }
        assertNotNull(state.failure)
        val before = retries
        network.value = NetworkStatus(online = true, wifiLinkMbps = 433)
        scope.advanceUntilIdle()
        network.value = NetworkStatus(online = true, wifiLinkMbps = 300, linkDownKbps = 20_000)
        scope.advanceUntilIdle()
        assertEquals(before, retries)
    }
}
