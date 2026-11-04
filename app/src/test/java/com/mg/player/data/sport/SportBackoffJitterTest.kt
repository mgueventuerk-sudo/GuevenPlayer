package com.mg.player.data.sport

import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/** Plan A3 (3.24): Backoff mit ±20 % Jitter, damit Ligen und Geräte nach einem Netzausfall nicht im Gleichtakt fragen. */
class SportBackoffJitterTest {

    @Test
    fun jitterStaysWithinTwentyPercent() {
        val random = Random(42)
        val waits = (1..1_000).map { SportRepository.jittered(60_000L, random) }
        assertTrue(waits.all { it in 48_000L..72_000L })
        // Streuung tatsächlich vorhanden (kein fester Wert)
        assertTrue(waits.toSet().size > 500)
    }
}
