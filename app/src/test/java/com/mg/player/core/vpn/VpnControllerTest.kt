package com.mg.player.core.vpn

import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** Ablauf des eingebauten VPN ohne echten Tunnel: Aufbau, Abbruch, Wiederverbinden mit Pausen, Erlaubnis, kein Netz. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class VpnControllerTest {

    private class FakeBackend(private val clock: () -> Long) : VpnBackend {
        var permission: Intent? = null
        var answers = true
        var failWith: VpnDropReason? = null
        var failCause: Throwable? = null
        val upCalls = mutableListOf<Long>()
        var downCalls = 0
        var up = false
        var handshake = 0L
        var trafficOf: () -> VpnTraffic? = { null }

        override fun permissionIntent(): Intent? = permission
        override suspend fun up(configText: String) {
            upCalls += clock() - BASE
            failWith?.let { throw VpnStartException(it, failCause) }
            up = true
            if (answers) handshake = clock()
        }
        override suspend fun down() {
            downCalls++
            up = false
            handshake = 0L
        }
        override fun isUp(): Boolean = up
        override fun latestHandshakeMs(): Long = handshake

        /** Prüfpaket: [probeAnswers] = der Server antwortet mit einem frischen Handshake. */
        var probeAnswers = true
        var probeWorks = true
        var probes = 0

        override suspend fun probe(): Boolean {
            probes++
            if (probeAnswers) handshake = clock()
            return probeWorks
        }
        override fun traffic(): VpnTraffic? = trafficOf()
    }

    private class Alerts : VpnAlerts {
        val events = mutableListOf<String>()
        override fun dropped(profile: String, reason: VpnDropReason) {
            events += "getrennt:${reason.name}"
        }
        override fun restored(profile: String) {
            events += "wieder da"
        }
    }

    private class Setup(val vpn: VpnController, val backend: FakeBackend, val alerts: Alerts, val online: MutableStateFlow<Boolean>, val store: VpnStore)

    private fun TestScope.setup(
        enabled: Boolean = true,
        online: Boolean = true,
        foreground: () -> Boolean = { true },
        configure: FakeBackend.() -> Unit = {},
    ): Setup {
        val clock = { BASE + testScheduler.currentTime }
        val prefs = ApplicationProvider.getApplicationContext<Context>().getSharedPreferences("vpn-test", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        val store = VpnStore(prefs, encrypt = { "x:$it" }, decrypt = { it.removePrefix("x:") })
        store.add("Deutschland · Frankfurt", "DE", "[Interface]\nPrivateKey = test")
        store.setEnabled(enabled)
        val backend = FakeBackend(clock).apply(configure)
        val alerts = Alerts()
        val net = MutableStateFlow(online)
        val vpn = VpnController(backend, store, backgroundScope, net, alerts, clock, StandardTestDispatcher(testScheduler), foreground)
        return Setup(vpn, backend, alerts, net, store)
    }

    @Test
    fun connectsOnStart() = runTest {
        val s = setup()
        s.vpn.start()
        runCurrent()
        val state = s.vpn.state.value
        assertTrue(state is VpnState.Connected)
        assertEquals("DE", (state as VpnState.Connected).country)
        assertEquals(listOf(0L), s.backend.upCalls)
        assertTrue(s.alerts.events.isEmpty())
    }

    /**
     * 3.14 am Fire TV: Nach dem Update startete Android die App im Hintergrund, der VPN-Dienst durfte nicht starten
     * (IllegalStateException) – fünf Fehlversuche, Meldung „VPN ließ sich nicht starten“. Jetzt: still warten und beim
     * Öffnen der App verbinden.
     */
    @Test
    fun backgroundStartWaitsSilentlyForForeground() = runTest {
        var visible = false
        val s = setup(foreground = { visible }) {
            failWith = VpnDropReason.START_FAILED
            failCause = IllegalStateException("Not allowed to start service Intent: app is in background")
        }
        s.vpn.start()
        runCurrent()
        advanceTimeBy(10 * 60_000L)
        runCurrent()
        assertEquals(listOf(0L), s.backend.upCalls)
        assertTrue(s.vpn.state.value is VpnState.Connecting)
        assertTrue(s.alerts.events.isEmpty())

        visible = true
        s.backend.failWith = null
        s.vpn.onForeground()
        runCurrent()
        assertTrue(s.vpn.state.value is VpnState.Connected)
        assertTrue(s.alerts.events.isEmpty())
    }

    /** „Durchgehend aktives VPN“: Android startet den Dienst selbst, während die Schleife auf die App wartet. */
    @Test
    fun alwaysOnWakeConnectsWithoutOpeningTheApp() = runTest {
        val s = setup(foreground = { false }) {
            failWith = VpnDropReason.START_FAILED
            failCause = IllegalStateException("Not allowed to start service Intent: app is in background")
        }
        s.vpn.start()
        runCurrent()
        assertTrue(s.vpn.state.value is VpnState.Connecting)

        // Dienst läuft jetzt (vom System gestartet) – der Aufbau gelingt auch ohne sichtbare App.
        s.backend.failWith = null
        s.vpn.wake()
        runCurrent()
        assertTrue(s.vpn.state.value is VpnState.Connected)
        assertEquals(2, s.backend.upCalls.size)
    }

    @Test
    fun startFailureInForegroundIsStillReported() = runTest {
        val s = setup {
            failWith = VpnDropReason.START_FAILED
            failCause = IllegalStateException("kaputt")
        }
        s.vpn.start()
        runCurrent()
        val state = s.vpn.state.value
        assertTrue(state is VpnState.Disconnected && state.reason == VpnDropReason.START_FAILED)
    }

    @Test
    fun switchedOffStaysOffAndNeverStartsTunnel() = runTest {
        val s = setup(enabled = false)
        s.vpn.start()
        runCurrent()
        assertEquals(VpnState.Off, s.vpn.state.value)
        assertTrue(s.backend.upCalls.isEmpty())
    }

    @Test
    fun staleTunnelIsTornDownShownAndReconnectedImmediately() = runTest {
        val s = setup()
        s.backend.probeAnswers = false
        s.vpn.start()
        runCurrent()
        // Kein neuer Handshake mehr und das Prüfpaket bleibt unbeantwortet: Tunnel ist tot.
        advanceTimeBy(VpnWatchdog.STALE_MS + VpnWatchdog.PROBE_WAIT_MS + 2 * VpnController.POLL_MS + 1)
        runCurrent()
        assertEquals(listOf("getrennt:STALE", "wieder da"), s.alerts.events)
        assertEquals(2, s.backend.upCalls.size)
        assertTrue("Tunnel muss abgebaut werden (Wiedergabe läuft über die normale Leitung)", s.backend.downCalls >= 1)
        assertTrue(s.vpn.state.value is VpnState.Connected)
    }

    /** Der Fall aus dem Fire-TV-Protokoll: fünf Tage ungenutzter Tunnel, 485-mal grundlos neu verbunden. */
    @Test
    fun idleTunnelIsKeptAliveByProbe() = runTest {
        val s = setup()
        s.vpn.start()
        runCurrent()
        // Zwei Stunden ohne Verkehr; der Handshake altert, das Prüfpaket erneuert ihn.
        advanceTimeBy(2 * 3_600_000L)
        runCurrent()
        assertTrue("Keine Meldung, kein Abbau: ${s.alerts.events}", s.alerts.events.isEmpty())
        assertEquals(1, s.backend.upCalls.size)
        assertEquals(0, s.backend.downCalls)
        assertTrue("Prüfpakete erwartet", s.backend.probes > 0)
        assertTrue(s.vpn.state.value is VpnState.Connected)
    }

    @Test
    fun unreachableServerRetriesWithGrowingPauses() = runTest {
        val s = setup { answers = false }
        s.vpn.start()
        advanceTimeBy(170_000)
        runCurrent()
        // Je 10 s Warten auf den Server, dazwischen sofort, 2, 5, 10, 30, 60 s Pause.
        assertEquals(listOf(0L, 10_000L, 22_000L, 37_000L, 57_000L, 97_000L, 167_000L), s.backend.upCalls)
        val state = s.vpn.state.value as VpnState.Disconnected
        assertEquals(VpnDropReason.SERVER_UNREACHABLE, state.reason)
        // „Getrennt seit“ bleibt der erste Fehlschlag (nach 10 s), nicht der letzte Versuch.
        assertEquals(BASE + 10_000L, state.sinceMs)
        // Nur eine Meldung, nicht bei jedem Versuch.
        assertEquals(listOf("getrennt:SERVER_UNREACHABLE"), s.alerts.events)
    }

    @Test
    fun missingPermissionAsksAndConnectsAfterApproval() = runTest {
        val s = setup { permission = Intent("vpn.prepare") }
        s.vpn.start()
        runCurrent()
        assertEquals(VpnDropReason.PERMISSION, (s.vpn.state.value as VpnState.Disconnected).reason)
        assertNotNull(s.vpn.permissionRequest.value)
        s.vpn.consumePermissionRequest()
        s.backend.permission = null
        s.vpn.onPermissionResult(true)
        runCurrent()
        assertTrue(s.vpn.state.value is VpnState.Connected)
        assertNull(s.vpn.permissionRequest.value)
        // Einrichten ist kein Abbruch: keine Benachrichtigung, keine Meldung „wieder verbunden“.
        assertTrue(s.alerts.events.isEmpty())
        assertNull(s.store.lastDrop.value)
    }

    @Test
    fun permissionTakenByOtherVpnIsAnnounced() = runTest {
        val s = setup()
        s.vpn.start()
        runCurrent()
        s.backend.up = false
        s.backend.permission = Intent("vpn.prepare")
        advanceTimeBy(VpnController.POLL_MS + 1)
        runCurrent()
        assertEquals(VpnDropReason.PERMISSION, (s.vpn.state.value as VpnState.Disconnected).reason)
        assertEquals(listOf("getrennt:REVOKED"), s.alerts.events)
        s.backend.permission = null
        s.vpn.onPermissionResult(true)
        runCurrent()
        assertEquals(listOf("getrennt:REVOKED", "wieder da"), s.alerts.events)
    }

    @Test
    fun silentServerDuringStreamIsDetectedFast() = runTest {
        val s = setup()
        var tx = 0L
        s.backend.trafficOf = { VpnTraffic(5_000, tx) }
        s.vpn.start()
        runCurrent()
        // Der Stream schickt weiter (ACKs), aber es kommt nichts mehr zurück.
        repeat(8) {
            tx += 500
            advanceTimeBy(VpnController.POLL_MS)
        }
        runCurrent()
        assertEquals(listOf("getrennt:STALE", "wieder da"), s.alerts.events)
    }

    @Test
    fun keepalivesAloneAreNotSilence() = runTest {
        val s = setup()
        var tx = 0L
        s.backend.trafficOf = { VpnTraffic(5_000, tx) }
        s.vpn.start()
        runCurrent()
        repeat(40) {
            tx += 32
            advanceTimeBy(VpnController.POLL_MS)
        }
        runCurrent()
        assertTrue(s.alerts.events.isEmpty())
        assertTrue(s.vpn.state.value is VpnState.Connected)
    }

    @Test
    fun deniedPermissionIsNotAskedAgainOnEveryReturn() = runTest {
        val s = setup { permission = Intent("vpn.prepare") }
        s.vpn.start()
        runCurrent()
        s.vpn.consumePermissionRequest()
        s.vpn.onPermissionResult(false)
        s.vpn.onForeground()
        runCurrent()
        assertNull(s.vpn.permissionRequest.value)
        // Knopf „Neu verbinden“ fragt wieder.
        s.vpn.restart()
        runCurrent()
        assertNotNull(s.vpn.permissionRequest.value)
    }

    @Test
    fun waitsForNetworkThenConnects() = runTest {
        val s = setup(online = false)
        s.vpn.start()
        advanceTimeBy(VpnController.OFFLINE_GRACE_MS + 1)
        runCurrent()
        assertEquals(VpnDropReason.NO_NETWORK, (s.vpn.state.value as VpnState.Disconnected).reason)
        assertTrue(s.backend.upCalls.isEmpty())
        s.online.value = true
        runCurrent()
        assertTrue(s.vpn.state.value is VpnState.Connected)
    }

    @Test
    fun shortNetworkBlipIsIgnored() = runTest {
        val s = setup()
        s.vpn.start()
        runCurrent()
        s.online.value = false
        advanceTimeBy(VpnController.POLL_MS + 1_000)
        s.online.value = true
        advanceTimeBy(VpnController.POLL_MS)
        runCurrent()
        assertTrue(s.vpn.state.value is VpnState.Connected)
        assertTrue(s.alerts.events.isEmpty())
    }

    @Test
    fun revokedByAndroidIsReportedAndReconnected() = runTest {
        val s = setup()
        s.vpn.start()
        runCurrent()
        s.backend.up = false
        advanceTimeBy(VpnController.POLL_MS + 1)
        runCurrent()
        assertEquals(listOf("getrennt:REVOKED", "wieder da"), s.alerts.events)
        assertTrue(s.vpn.state.value is VpnState.Connected)
    }

    @Test
    fun badConfigWaitsForUser() = runTest {
        val s = setup { failWith = VpnDropReason.BAD_CONFIG }
        s.vpn.start()
        advanceTimeBy(300_000)
        runCurrent()
        assertEquals(VpnDropReason.BAD_CONFIG, (s.vpn.state.value as VpnState.Disconnected).reason)
        assertEquals(1, s.backend.upCalls.size)
    }

    @Test
    fun stopSwitchesOffAndTearsDown() = runTest {
        val s = setup()
        s.vpn.start()
        runCurrent()
        s.vpn.setEnabled(false)
        runCurrent()
        assertEquals(VpnState.Off, s.vpn.state.value)
        assertTrue(s.backend.downCalls >= 1)
        assertTrue(!s.store.enabled.value)
    }

    @Test
    fun tunnelUpsFireOnEveryReconnectButNotOnSubscribe() = runTest {
        val s = setup()
        val ups = mutableListOf<Long>()
        backgroundScope.launch { s.vpn.tunnelUps().collect { ups += testScheduler.currentTime } }
        runCurrent()
        assertTrue("kein Signal beim Abonnieren", ups.isEmpty())
        s.vpn.start()
        runCurrent()
        assertEquals(1, ups.size)
        s.backend.up = false
        advanceTimeBy(VpnController.POLL_MS + 1)
        runCurrent()
        assertEquals("nach dem Wiederverbinden erneut – Streams wechseln in den Tunnel", 2, ups.size)
    }

    @Test
    fun backoffSteps() {
        assertEquals(listOf(0L, 2_000L, 5_000L, 10_000L, 30_000L, 60_000L, 60_000L), (0..6).map(VpnBackoff::delayFor))
    }

    private companion object {
        const val BASE = 1_700_000_000_000L
    }
}
