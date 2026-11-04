package com.mg.player.player

/**
 * Automatischer Stream-Reconnect bei kurzen Verbindungsabbrüchen:
 * Wartezeiten 1 s, 2 s, 4 s, 8 s, 8 s, 8 s – danach wird aufgegeben.
 */
class ReconnectPolicy(private val maxAttempts: Int = 6) {

    var attempts: Int = 0
        private set

    /** Wartezeit bis zum nächsten Versuch oder null, wenn aufgegeben werden soll. */
    fun nextDelayMs(): Long? {
        if (attempts >= maxAttempts) return null
        val delay = 1_000L shl attempts.coerceAtMost(3)
        attempts++
        return delay
    }

    fun reset() {
        attempts = 0
    }
}
