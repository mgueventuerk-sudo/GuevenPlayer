package com.mg.player.player.engine

import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertTrue
import org.junit.Test

/** Ein hängendes Beenden (toter Stream) darf die Freigabe anderer Engines nicht aufhalten. */
class VlcWorkerTest {

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
