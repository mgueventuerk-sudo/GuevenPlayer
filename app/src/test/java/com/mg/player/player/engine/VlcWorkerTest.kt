package com.mg.player.player.engine

import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Ein hängendes Beenden (toter Stream) darf die Freigabe anderer Engines nicht aufhalten. */
class VlcWorkerTest {

    @Test
    fun nativeUseRemainsHeldUntilQueuedReleaseFinishes() {
        val entered = CountDownLatch(1)
        val complete = CountDownLatch(1)
        val uses = AtomicInteger(1)
        val worker = VlcWorker()

        worker.finish {
            entered.countDown()
            assertTrue(complete.await(1, TimeUnit.SECONDS))
            uses.decrementAndGet()
        }

        assertTrue(entered.await(1, TimeUnit.SECONDS))
        assertEquals(1, uses.get())
        complete.countDown()
        assertTrue(worker.awaitIdle(1_000))
        assertEquals(0, uses.get())
    }

    @Test
    fun hangingReleaseDoesNotBlockOtherEngines() {
        val logs = Collections.synchronizedList(ArrayList<String>())
        val hang = CountDownLatch(1)
        val stuck = VlcWorker { logs += it }
        stuck.finish { hang.await() } // stop() hängt

        val released = CountDownLatch(1)
        val other = VlcWorker { logs += it }
        other.run { }
        other.finish { released.countDown() }
        assertTrue("andere Engine wurde nicht freigegeben", released.await(1, TimeUnit.SECONDS))
        assertTrue(other.awaitIdle(1_000))

        // Nach der Wartezeit meldet der Wächter die hängende Engine.
        Thread.sleep(VlcWorker.WATCHDOG_MS + 300)
        assertTrue(logs.any { "hängt beim Beenden" in it })
        assertTrue(logs.size == 1)
        hang.countDown()
        // Nach finish nimmt der Worker nichts mehr an – kein Absturz.
        stuck.run { }
    }
}
