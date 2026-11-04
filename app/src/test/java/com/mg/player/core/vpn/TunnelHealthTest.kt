package com.mg.player.core.vpn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Der Fall aus dem Fire-TV-Protokoll (27.10.–02.11.): 485 Neuverbindungen, fast alle wegen „letzter Handshake
 * vor 185–187 s“ bei ungenutztem Tunnel. Hier abgesichert, dass genau das nicht mehr passiert.
 */
class TunnelHealthTest {

    private var now = 1_000_000L
    private val health = TunnelHealth { now }

    private fun poll(handshakeAgeMs: Long, rx: Long = 1_000, tx: Long = 1_000, stepMs: Long = VpnController.POLL_MS): TunnelHealth.Verdict {
        now += stepMs
        return health.judge(if (handshakeAgeMs < 0) 0L else now - handshakeAgeMs, VpnTraffic(rx, tx))
    }

    /**
     * Lässt [ms] in normalen Durchgängen vergehen (kein Zeitsprung – der gilt als Standby) und liefert die Urteile.
     * Prüfpakete werden wie im echten Ablauf sofort zurückgemeldet ([answers] = der Server antwortet).
     */
    private fun idle(ms: Long, answers: Boolean = true, handshakeAgeAt: (Long) -> Long): List<TunnelHealth.Verdict> {
        val verdicts = mutableListOf<TunnelHealth.Verdict>()
        val until = now + ms
        var answeredAt = -1L
        while (now < until) {
            val age = if (answeredAt >= 0) now + VpnController.POLL_MS - answeredAt else handshakeAgeAt(now + VpnController.POLL_MS)
            val verdict = poll(handshakeAgeMs = age)
            verdicts += verdict
            if (verdict == TunnelHealth.Verdict.Probe) {
                health.probed(sent = true)
                if (answers) answeredAt = now
            }
        }
        return verdicts
    }

    @Test
    fun idleTunnelWithOldHandshakeIsProbedNotTornDown() {
        repeat(60) { assertEquals(TunnelHealth.Verdict.Alive, poll(handshakeAgeMs = 30_000)) }
        // Handshake veraltet, aber nichts gesendet: erst nachfragen.
        assertEquals(TunnelHealth.Verdict.Probe, poll(handshakeAgeMs = VpnWatchdog.STALE_MS + 1_000))
        health.probed(sent = true)
        // Der Server antwortet mit einem neuen Handshake → Tunnel lebt, kein Abbau.
        repeat(5) { assertEquals(TunnelHealth.Verdict.Alive, poll(handshakeAgeMs = 2_000)) }
    }

    @Test
    fun probeWithoutAnswerEndsTheTunnel() {
        assertEquals(TunnelHealth.Verdict.Probe, poll(handshakeAgeMs = VpnWatchdog.STALE_MS + 1_000))
        health.probed(sent = true)
        val verdicts = (1..5).map { poll(handshakeAgeMs = VpnWatchdog.STALE_MS + 1_000 + it * VpnController.POLL_MS) }
        val dead = verdicts.filterIsInstance<TunnelHealth.Verdict.Dead>()
        assertEquals(1, dead.size)
        assertTrue(dead.single().detail.startsWith("Prüfpaket ohne Antwort"))
    }

    @Test
    fun answerAsIncomingDataAlsoCounts() {
        assertEquals(TunnelHealth.Verdict.Probe, poll(handshakeAgeMs = VpnWatchdog.STALE_MS + 1_000))
        health.probed(sent = true)
        // Kein neuer Handshake, aber die Antwort auf das Prüfpaket kommt an.
        assertEquals(TunnelHealth.Verdict.Alive, poll(handshakeAgeMs = VpnWatchdog.STALE_MS + 4_000, rx = 1_200))
        assertEquals(TunnelHealth.Verdict.Alive, poll(handshakeAgeMs = VpnWatchdog.STALE_MS + 7_000, rx = 1_200))
    }

    @Test
    fun deviceStandbyIsNoReasonToReconnect() {
        assertEquals(TunnelHealth.Verdict.Alive, poll(handshakeAgeMs = 10_000))
        // Fernseher war 40 Minuten im Standby: Zählerstände und Handshake-Alter stammen von vorher.
        val afterSleep = poll(handshakeAgeMs = 2_400_000, stepMs = 2_400_000)
        assertEquals(TunnelHealth.Verdict.Alive, afterSleep)
        // Danach noch eine Schonzeit, damit WireGuard neu aushandeln kann – erst dann wird nachgefragt.
        val start = now
        val grace = idle(VpnWatchdog.RESUME_GRACE_MS - VpnController.POLL_MS, answers = false) { 2_400_000 + (it - start) }
        assertTrue("während der Schonzeit nichts tun: $grace", grace.all { it == TunnelHealth.Verdict.Alive })
        val afterGrace = idle(3 * VpnController.POLL_MS, answers = false) { 2_400_000 + (it - start) }
        assertTrue("danach nachfragen: $afterGrace", afterGrace.contains(TunnelHealth.Verdict.Probe))
        assertEquals(0, afterGrace.count { it is TunnelHealth.Verdict.Dead })
    }

    @Test
    fun silentServerDuringStreamIsStillDetectedFast() {
        var tx = 1_000L
        var verdict: TunnelHealth.Verdict = TunnelHealth.Verdict.Alive
        repeat(10) {
            tx += 2_000
            if (verdict !is TunnelHealth.Verdict.Dead) verdict = poll(handshakeAgeMs = 30_000, rx = 5_000, tx = tx)
        }
        val dead = verdict as TunnelHealth.Verdict.Dead
        assertTrue(dead.detail, dead.detail.contains("nichts empfangen"))
    }

    /** Nicht bei jedem Durchgang (alle 3 s) ein Prüfpaket, sondern höchstens einmal je Mindestabstand. */
    /** Nicht bei jedem Durchgang (alle 3 s) ein Prüfpaket: In einer Stunde Leerlauf nur eine Handvoll. */
    @Test
    fun probeIsNotRepeatedEverySecond() {
        val start = now
        val verdicts = idle(3_600_000L, answers = false) { VpnWatchdog.STALE_MS + (it - start) }
        val probes = verdicts.count { it == TunnelHealth.Verdict.Probe }
        assertTrue("Prüfpakete in einer Stunde: $probes", probes in 1..70)
    }

    @Test
    fun withoutProbeSupportTheTunnelStillEndsEventually() {
        assertEquals(TunnelHealth.Verdict.Probe, poll(handshakeAgeMs = VpnWatchdog.STALE_MS + 1_000))
        health.probed(sent = false)
        val start = now
        val verdicts = idle(VpnWatchdog.DEAD_WITHOUT_PROBE_MS + 60_000, answers = false) { VpnWatchdog.STALE_MS + (it - start) }
        assertEquals(0, verdicts.take(VpnWatchdog.STALE_MS.toInt() / VpnController.POLL_MS.toInt()).count { it is TunnelHealth.Verdict.Dead })
        assertTrue("irgendwann muss der Tunnel als tot gelten", verdicts.any { it is TunnelHealth.Verdict.Dead })
    }
}
