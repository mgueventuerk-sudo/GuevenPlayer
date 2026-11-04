package com.mg.player.core.util

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class CancellableTest {

    @Test
    fun ordinaryErrorsBecomeFailure() {
        val result = runCatchingCancellable { throw IOException("weg") }
        assertTrue(result.exceptionOrNull() is IOException)
        assertEquals(3, runCatchingCancellable { 3 }.getOrNull())
    }

    @Test(expected = CancellationException::class)
    fun cancellationIsRethrown() {
        runCatchingCancellable { throw CancellationException("abgebrochen") }
    }

    /** Mit normalem runCatching liefe die Schleife nach dem Abbruch weiter und schriebe noch Ergebnisse. */
    @Test
    fun cancelledLoopStops() = runBlocking {
        var afterCancel = false
        val started = CompletableDeferred<Unit>()
        val job = launch {
            repeat(1_000) { i ->
                runCatchingCancellable {
                    if (i == 1) started.complete(Unit)
                    delay(10)
                }
                if (i > 5) afterCancel = true
            }
        }
        started.await()
        job.cancel()
        job.join()
        yield()
        assertFalse(afterCancel)
    }
}
