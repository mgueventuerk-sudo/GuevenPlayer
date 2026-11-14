package com.mg.player.data.sport.proof

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ActiveProbesTest {

    @Test
    fun stoppingAProbeFreesTheAccountAndTheLoopGoesOn() = runBlocking {
        val probes = ActiveProbes()
        val started = CompletableDeferred<Unit>()
        var released = false
        val look = async(start = CoroutineStart.UNDISPATCHED) {
            probes.run(7L) {
                started.complete(Unit)
                try {
                    awaitCancellation()
                } finally {
                    released = true
                }
            }
        }
        started.await()
        assertTrue(probes.isRunning(7L))
        assertFalse(probes.stop(8L))
        assertTrue(probes.stop(7L))
        // Gestoppt von außen: null statt Abbruch – die Suchschleife des Aufrufers läuft weiter.
        assertNull(look.await())
        assertTrue(released)
        assertFalse(probes.isRunning(7L))
    }

    @Test
    fun finishedProbeReturnsItsResult() = runBlocking {
        val probes = ActiveProbes()
        assertEquals("bild", probes.run(3L) { yield(); "bild" })
        assertFalse(probes.isRunning(3L))
        assertFalse(probes.stop(3L))
    }

    @Test(expected = kotlinx.coroutines.CancellationException::class)
    fun cancellingTheCallerStillCancels() = runBlocking {
        val probes = ActiveProbes()
        val started = CompletableDeferred<Unit>()
        val caller = async(start = CoroutineStart.UNDISPATCHED) {
            probes.run(1L) {
                started.complete(Unit)
                awaitCancellation()
            }
        }
        started.await()
        caller.cancel()
        caller.await()
        Unit
    }
}
