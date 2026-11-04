package com.mg.player.data.sync

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.EOFException
import java.io.IOException
import java.net.ConnectException
import java.net.ProtocolException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/**
 * Fall aus der Prüfung: Wurde das EPG angefordert, während nur die Inhalte luden (Länderauswahl beim
 * Einrichten), bekam man die laufende Inhalts-Aktualisierung zurück – das EPG wurde nie geladen.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SyncManagerTest {

    private val scope = TestScope(StandardTestDispatcher())
    private val log = mutableListOf<String>()
    private val contentGate = CompletableDeferred<Unit>()
    private val manager = SyncManager(
        scope,
        loadContent = { id, withEpg, _ ->
            log += "content:$id:$withEpg"
            contentGate.await()
            if (withEpg) log += "epg-in-all:$id"
        },
        loadEpg = { id, _ -> log += "epg:$id" },
    )

    @Test
    fun epgRequestedDuringContentLoadRunsAfterwards() {
        val content = manager.refreshContent(1)
        scope.runCurrent()
        val epg = manager.refreshEpg(1)
        assertNotSame(content, epg)
        scope.runCurrent()
        assertEquals(listOf("content:1:false"), log)
        contentGate.complete(Unit)
        scope.runCurrent()
        assertEquals(listOf("content:1:false", "epg:1"), log)
    }

    @Test
    fun fullRefreshAlreadyCoversEpg() {
        val all = manager.refreshAll(1)
        scope.runCurrent()
        assertSame(all, manager.refreshEpg(1))
        assertSame(all, manager.refreshContent(1))
        contentGate.complete(Unit)
        scope.runCurrent()
        assertEquals(listOf("content:1:true", "epg-in-all:1"), log)
    }

    @Test
    fun sameRequestTwiceRunsOnce() {
        val first = manager.refreshContent(1)
        assertSame(first, manager.refreshContent(1))
        contentGate.complete(Unit)
        scope.runCurrent()
        assertEquals(listOf("content:1:false"), log)
    }

    @Test
    fun otherAccountQueuesBehind() {
        manager.refreshContent(1)
        scope.runCurrent()
        manager.refreshEpg(2)
        scope.runCurrent()
        assertEquals(listOf("content:1:false"), log)
        contentGate.complete(Unit)
        scope.runCurrent()
        assertEquals(listOf("content:1:false", "epg:2"), log)
        assertEquals(false, manager.state.value.running)
    }

    @Test
    fun networkErrorsAreReadable() {
        assertEquals("Server nicht erreichbar – Adresse und Port prüfen", syncErrorText(ConnectException("Failed to connect to /127.0.0.1:9")))
        assertTrue(syncErrorText(IOException("x", UnknownHostException("a.invalid"))).startsWith("Server nicht gefunden"))
        assertTrue(syncErrorText(SocketTimeoutException("timeout")).contains("Zeitüberschreitung"))
        assertEquals("Anmeldung abgelehnt", syncErrorText(IllegalStateException("Anmeldung abgelehnt")))
    }

    /** Virtuelles Labor 3.23: abgebrochene bzw. kaputte Antworten zeigten die rohe englische Meldung. */
    @Test
    fun brokenResponsesAreReadable() {
        assertEquals(BROKEN_RESPONSE, syncErrorText(ProtocolException("unexpected end of stream")))
        assertEquals(BROKEN_RESPONSE, syncErrorText(IOException("x", EOFException("End of input at line 1 column 41"))))
        // Netzwerkursache geht vor: Zeitüberschreitung bleibt Zeitüberschreitung.
        assertTrue(syncErrorText(ProtocolException("x").apply { initCause(SocketTimeoutException("t")) }).contains("Zeitüberschreitung"))
    }
}
