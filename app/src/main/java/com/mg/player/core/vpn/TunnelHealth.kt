package com.mg.player.core.vpn

/**
 * Entscheidet, ob der WireGuard-Tunnel noch trägt. Bewusst ohne Uhr, Netz und Coroutinen: [TunnelHealth.judge]
 * bekommt Handshake-Zeit und Zählerstände und liefert ein Urteil – damit ist der ganze Ablauf mit einer virtuellen
 * Uhr prüfbar (siehe TunnelHealthTest).
 */
internal object VpnWatchdog {
    const val MS_PER_S = 1_000L

    /** Kein Empfang mehr, obwohl gesendet wird (laufender Stream): schnellster Hinweis auf einen toten Server. */
    const val SILENCE_MS = 20_000L
    const val SILENCE_TX_BYTES = 1_024L

    /** Ab diesem Handshake-Alter wird nachgeprüft (WireGuard verwirft die Sitzungsschlüssel nach 180 s). */
    const val STALE_MS = 185_000L

    /** Größere Lücke zwischen zwei Durchgängen = das Gerät hat geschlafen (Standby am Fernseher). */
    const val SLEEP_GAP_MS = 20_000L
    const val RESUME_GRACE_MS = 20_000L

    /** So lange wartet der Wächter auf die Antwort zum Prüfpaket (drei Durchgänge). */
    const val PROBE_WAIT_MS = 9_000L
    const val PROBE_INTERVAL_MS = 60_000L

    /** Ohne Prüfmöglichkeit (kein Tunnel-DNS) erst nach dieser Zeit ohne Handshake abreißen. */
    const val DEAD_WITHOUT_PROBE_MS = 900_000L
}

/**
 * Beurteilt den Tunnel aus Handshake-Alter und Zählerständen – ohne eigene Uhr und ohne Netzzugriff, damit der
 * ganze Ablauf mit einer virtuellen Uhr testbar ist.
 *
 * Erfahrung vom Fire TV (Protokoll vom 27.10.–02.11.): 485 Neuverbindungen in fünf Tagen, fast alle mit
 * „letzter Handshake vor 185–187 s“ bei völlig ungenutztem Tunnel. Ein altes Handshake-Alter beweist nichts:
 * WireGuard handelt nur aus, wenn etwas zu senden ist; im Standby schickt auch der Keepalive nichts. Darum gilt
 * jetzt: Erst ein Prüfpaket ([VpnBackend.probe]), und nur wenn darauf nichts zurückkommt, ist der Tunnel tot.
 */
internal class TunnelHealth(private val clock: () -> Long) {

    sealed interface Verdict {
        /** Tunnel trägt (oder es ist noch nichts zu entscheiden). */
        data object Alive : Verdict

        /** Prüfpaket schicken und das Ergebnis mit [probed] zurückmelden. */
        data object Probe : Verdict

        data class Dead(val detail: String) : Verdict
    }

    private val silence = SilenceDetector(clock)
    private var lastPollAt = 0L
    private var graceUntil = 0L
    private var probeSentAt = -1L
    private var probeRx = -1L
    private var lastProbeAt = -1L
    private var probePossible = true

    fun judge(handshakeMs: Long, traffic: VpnTraffic?): Verdict {
        val now = clock()
        val gap = if (lastPollAt == 0L) 0L else now - lastPollAt
        lastPollAt = now
        // Gerät war im Standby: Die Zählerstände sind von vorher, das Handshake-Alter sagt nichts. Erst einlaufen lassen.
        if (gap > VpnWatchdog.SLEEP_GAP_MS) {
            resume(now)
            return Verdict.Alive
        }
        if (now < graceUntil) return Verdict.Alive
        val age = if (handshakeMs > 0) now - handshakeMs else 0L
        return openProbe(now, age, traffic)
            ?: silent(age, traffic)
            ?: staleHandshake(now, age, handshakeMs)
            ?: Verdict.Alive
    }

    private fun resume(now: Long) {
        silence.reset()
        probeSentAt = -1L
        graceUntil = now + VpnWatchdog.RESUME_GRACE_MS
    }

    /** Es geht etwas raus, aber nichts kommt zurück – der schnellste Hinweis bei laufendem Stream. */
    private fun silent(age: Long, traffic: VpnTraffic?): Verdict? {
        if (traffic == null || !silence.isSilent(traffic)) return null
        return Verdict.Dead(
            "${VpnWatchdog.SILENCE_MS / VpnWatchdog.MS_PER_S} s nichts empfangen, ${silence.lastSentBytes} Byte gesendet, " +
                "letzter Handshake vor ${age / VpnWatchdog.MS_PER_S} s",
        )
    }

    /** Handshake veraltet: nachfragen statt abreißen (ohne Verkehr handelt WireGuard nicht neu aus). */
    private fun staleHandshake(now: Long, age: Long, handshakeMs: Long): Verdict? {
        if (handshakeMs <= 0 || age <= VpnWatchdog.STALE_MS) return null
        // Ohne Prüfmöglichkeit (kein Tunnel-DNS) bleibt nur Geduld – aber nicht endlos.
        if (!probePossible) {
            val tooOld = age > VpnWatchdog.DEAD_WITHOUT_PROBE_MS
            return if (tooOld) Verdict.Dead("seit ${age / VpnWatchdog.MS_PER_S} s kein Handshake, Prüfpaket nicht möglich") else Verdict.Alive
        }
        if (lastProbeAt >= 0 && now - lastProbeAt < VpnWatchdog.PROBE_INTERVAL_MS) return Verdict.Alive
        return Verdict.Probe
    }

    /** Ergebnis des Prüfpakets: [sent] = es ging raus (die Antwort wird in den nächsten Durchgängen geprüft). */
    fun probed(sent: Boolean) {
        val now = clock()
        lastProbeAt = now
        probePossible = sent
        probeSentAt = if (sent) now else -1L
        probeRx = -1L
    }

    /** Läuft ein Prüfpaket, entscheidet dessen Ergebnis – sonst null. */
    private fun openProbe(now: Long, age: Long, traffic: VpnTraffic?): Verdict? {
        if (probeSentAt < 0) return null
        if (probeRx < 0) probeRx = traffic?.rxBytes ?: 0L
        val answered = age in 1..VpnWatchdog.STALE_MS || (traffic != null && traffic.rxBytes > probeRx)
        if (answered) {
            probeSentAt = -1L
            return Verdict.Alive
        }
        if (now - probeSentAt < VpnWatchdog.PROBE_WAIT_MS) return Verdict.Alive
        probeSentAt = -1L
        return Verdict.Dead("Prüfpaket ohne Antwort, letzter Handshake vor ${age / VpnWatchdog.MS_PER_S} s")
    }
}

/**
 * Erkennt einen toten Server, während die App Daten schickt: Seit [VpnWatchdog.SILENCE_MS] kam nichts zurück, gesendet wurde
 * in diesen letzten [VpnWatchdog.SILENCE_MS] aber mehr als [VpnWatchdog.SILENCE_TX_BYTES]. Reine Keepalives (32 Byte alle 25 s) bleiben
 * weit darunter.
 */
internal class SilenceDetector(private val clock: () -> Long) {
    private var lastRx = -1L
    private var since = 0L
    private val samples = ArrayDeque<Pair<Long, Long>>()

    /** Gesendete Bytes im Prüffenster beim letzten Aufruf (fürs Protokoll). */
    var lastSentBytes = 0L
        private set

    fun reset() {
        lastRx = -1L
        since = 0L
        samples.clear()
        lastSentBytes = 0L
    }

    fun isSilent(traffic: VpnTraffic): Boolean {
        val now = clock()
        if (traffic.rxBytes != lastRx) {
            lastRx = traffic.rxBytes
            since = now
            samples.clear()
        }
        samples.addLast(now to traffic.txBytes)
        // Ältester Wert, der gerade noch mindestens VpnWatchdog.SILENCE_MS zurückliegt, bleibt als Vergleich stehen.
        while (samples.size > 1 && now - samples[1].first >= VpnWatchdog.SILENCE_MS) samples.removeFirst()
        val (oldAt, oldTx) = samples.first()
        lastSentBytes = traffic.txBytes - oldTx
        return now - since >= VpnWatchdog.SILENCE_MS && now - oldAt >= VpnWatchdog.SILENCE_MS && traffic.txBytes - oldTx > VpnWatchdog.SILENCE_TX_BYTES
    }
}

/**
 * Erkennt, dass derselbe WireGuard-Schlüssel gleichzeitig auf einem anderen Gerät läuft: Der Server erkennt immer nur
 * das Gerät an, das sich zuletzt angemeldet hat – die Verbindung trägt dann jeweils nur bis zur nächsten
 * Schlüsselerneuerung (~2 min). Protokoll vom 04.11.2026: 30–40 Neuverbindungen je Stunde, jede nach 96–117 s.
 *
 * [onSessionEnded] meldet true, sobald mindestens [SHORT_LIMIT] Verbindungen in [WINDOW_MS] kürzer als [SHORT_MS]
 * waren. Danach wird mit [COOLDOWN_MS] Abstand neu verbunden statt sofort (Streams laufen dann über die normale
 * Leitung – ein Tunnel, der alle 2 Minuten reißt, hilft ohnehin nicht).
 */
internal class ShortSessions(private val clock: () -> Long) {
    private val ends = ArrayDeque<Long>()

    fun onSessionEnded(durationMs: Long): Boolean {
        val now = clock()
        while (ends.isNotEmpty() && now - ends.first() > WINDOW_MS) ends.removeFirst()
        if (durationMs < SHORT_MS) ends.addLast(now)
        return ends.size >= SHORT_LIMIT
    }

    companion object {
        const val SHORT_MS = 5 * 60_000L
        const val WINDOW_MS = 30 * 60_000L
        const val SHORT_LIMIT = 4
        const val COOLDOWN_MS = 5 * 60_000L
    }
}
