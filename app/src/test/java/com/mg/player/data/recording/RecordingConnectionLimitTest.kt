package com.mg.player.data.recording

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.mg.player.core.network.HttpFetcher
import com.mg.player.data.db.AppDatabase
import com.mg.player.data.remote.StreamUrlResolver
import com.mg.player.data.remote.XtreamApi
import com.mg.player.domain.model.Channel
import com.mg.player.domain.model.ConnectionStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Das Verbindungslimit des Anbieters (max_connections/active_cons, siehe XtreamAccountParser, zwischengespeichert
 * in ConnectionMonitor.status) wurde vor dem Start einer Aufnahme nie geprüft. Eine Aufnahme braucht eine eigene,
 * zweite Verbindung (auch während man denselben Sender schon schaut) - ohne diese Prüfung kam erst der rohe
 * Serverfehler, wenn der Anbieter die zusätzliche Verbindung ablehnt, statt einer verständlichen Meldung.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class RecordingConnectionLimitTest {

    private val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AppDatabase::class.java)
        .allowMainThreadQueries()
        .build()

    private val channel = Channel(
        id = "7:L:9",
        name = "Testsender",
        logoUrl = null,
        streamUrl = "http://127.0.0.1:0/nichts",
        categoryId = "1",
        epgKey = "k",
        nowTitle = null,
        nowStartMs = null,
        nowEndMs = null,
        playlistId = 7,
    )

    @After
    fun tearDown() = db.close()

    private fun manager(status: ConnectionStatus?): RecordingManager = RecordingManager(
        context = ApplicationProvider.getApplicationContext<Context>(),
        scope = CoroutineScope(Dispatchers.Default + SupervisorJob()),
        recorder = StreamRecorder(OkHttpClient()),
        urls = StreamUrlResolver(db, XtreamApi(HttpFetcher(OkHttpClient()))),
        channelLookup = { channel },
        connectionStatus = { id -> status.takeIf { id == channel.playlistId } },
    )

    @Test
    fun recordingIsBlockedWhenTheAccountsConnectionLimitIsReached() = runBlocking {
        val manager = manager(ConnectionStatus(active = 1, max = 1))
        manager.awaitReady()
        val error = manager.start(channel, "Testaufnahme", System.currentTimeMillis() + 60_000)
        assertEquals("Verbindungslimit des Zugangs erreicht (1/1 belegt)", error)
    }

    @Test
    fun recordingStartsNormallyWhenAConnectionIsFree() = runBlocking {
        val manager = manager(ConnectionStatus(active = 0, max = 1))
        manager.awaitReady()
        val error = manager.start(channel, "Testaufnahme", System.currentTimeMillis() + 60_000)
        assertNull(error)
    }
}
