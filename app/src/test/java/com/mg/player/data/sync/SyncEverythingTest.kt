package com.mg.player.data.sync

import com.mg.player.domain.repository.ImportProgress
import com.mg.player.ui.home.syncLine
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

/** „Alle Zugänge & EPG aktualisieren“: nacheinander, ein Fehler stoppt die übrigen nicht. */
@OptIn(ExperimentalCoroutinesApi::class)
class SyncEverythingTest {

    private val scope = TestScope(StandardTestDispatcher())
    private val log = mutableListOf<String>()
    private val gate = CompletableDeferred<Unit>()
    private val failing = mutableSetOf<Long>()
    private val failOnce = mutableSetOf<Long>()
    private val rejected = mutableSetOf<Long>()
    private val finished = mutableListOf<Long>()
    private val manager = SyncManager(
        scope,
        loadContent = { id, withEpg, progress ->
            log += "content:$id:$withEpg"
            progress(ImportProgress("Lade Sender…", 42))
            gate.await()
            if (id in failing) throw IOException("kaputt")
            if (failOnce.remove(id)) throw java.net.SocketException("Software caused connection abort")
            if (id in rejected) error("Anmeldung abgelehnt")
        },
        loadEpg = { id, _ -> log += "epg:$id" },
    ).apply { onFinished = { finished += it } }

    private val all = listOf(1L to "Anbieter A", 2L to "Anbieter B", 3L to "Anbieter C")

    @Test
    fun allAccountsInOrderWithEpg() {
        manager.refreshEverything(all)
        scope.runCurrent()
        val running = manager.state.value
        assertEquals("1/3", running.step)
        assertEquals("Anbieter A: Lade Sender…", running.message)
        assertEquals("1/3 · Anbieter A: Lade Sender… · 42", syncLine(running))
        gate.complete(Unit)
        scope.runCurrent()
        assertEquals(listOf("content:1:true", "content:2:true", "content:3:true"), log)
        assertEquals(listOf(1L, 2L, 3L), finished)
        val done = manager.state.value
        assertFalse(done.running)
        assertNull(done.error)
        assertNull(done.step)
        assertEquals("Alle 3 Zugänge aktualisiert", done.message)
    }

    @Test
    fun failureOfOneDoesNotStopOthers() {
        failing += 2L
        manager.refreshEverything(all)
        gate.complete(Unit)
        scope.advanceUntilIdle()
        // Netzfehler: zwei weitere Versuche (nach 20 s und 60 s), dann gibt es auf.
        assertEquals(listOf("content:1:true", "content:2:true", "content:2:true", "content:2:true", "content:3:true"), log)
        assertEquals("2 von 3 aktualisiert, Fehler bei: Anbieter B", manager.state.value.error)
    }

    @Test
    fun droppedConnectionIsRetriedAndSucceeds() {
        failOnce += 2L
        manager.refreshEverything(all)
        gate.complete(Unit)
        scope.runCurrent()
        assertEquals("Anbieter B: Verbindung abgerissen – neuer Versuch …", manager.state.value.message)
        scope.advanceTimeBy(20_001)
        scope.advanceUntilIdle()
        assertNull(manager.state.value.error)
        assertEquals(4, log.size)
    }

    @Test
    fun rejectedLoginIsNotRetried() {
        rejected += 2L
        manager.refreshEverything(all)
        gate.complete(Unit)
        scope.advanceUntilIdle()
        assertEquals(3, log.size)
    }

    @Test
    fun singleRefreshDuringEverythingIsCovered() {
        val job = manager.refreshEverything(all)
        scope.runCurrent()
        assertSame(job, manager.refreshAll(2))
        gate.complete(Unit)
        scope.runCurrent()
        assertEquals(3, log.size)
    }

    @Test
    fun epgOnlyQueuesBehindEverything() {
        manager.refreshEverything(all)
        scope.runCurrent()
        manager.refreshEpg(1)
        gate.complete(Unit)
        scope.runCurrent()
        assertEquals("epg:1", log.last())
    }

    @Test
    fun nothingToDoWithoutAccounts() {
        assertNull(manager.refreshEverything(emptyList()))
    }
}
