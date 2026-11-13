package com.mg.player.data.recording

import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.mg.player.core.network.HttpFetcher
import com.mg.player.data.db.AppDatabase
import com.mg.player.data.remote.StreamUrlResolver
import com.mg.player.data.remote.XtreamApi
import com.mg.player.domain.model.Channel
import com.mg.player.domain.model.RecordingStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Misst (nicht vermutet): RecordingManager.begin() (RecordingManager.kt:204-210) persistiert den Status
 * RECORDING, BEVOR RecordingService.start() aufgerufen wird; RecordingService.start() selbst (RecordingService.kt:
 * 62-69) fängt jeden Fehler beim Start des Vordergrund-Dienstes nur mit AppLog.error() ab und gibt Unit zurück.
 * Dieser Test erzwingt einen echten Fehlschlag (ein ContextWrapper, dessen startForegroundService() wirft - wie
 * es auf Android 12+ real passieren kann, z. B. ForegroundServiceStartNotAllowedException) und prüft: start()
 * meldet trotzdem keinen Fehler, und der gespeicherte Status bleibt RECORDING.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class RecordingManagerSilentFailureTest {

    private val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AppDatabase::class.java)
        .allowMainThreadQueries()
        .build()

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun foregroundServiceFailureLeavesStatusRecordingWithoutVisibleError() = runBlocking {
        val baseContext = ApplicationProvider.getApplicationContext<Context>()
        val throwingContext = object : ContextWrapper(baseContext) {
            override fun startForegroundService(service: Intent): ComponentName? {
                throw SecurityException("Simuliert: Vordergrunddienst darf nicht gestartet werden (z. B. Android 12+)")
            }
        }
        val channel = Channel(
            id = "test-channel",
            name = "Testsender",
            logoUrl = null,
            streamUrl = "http://127.0.0.1:0/nichts",
            categoryId = "1",
            epgKey = "k",
            nowTitle = null,
            nowStartMs = null,
            nowEndMs = null,
        )
        val manager = RecordingManager(
            context = throwingContext,
            scope = CoroutineScope(Dispatchers.Default + SupervisorJob()),
            recorder = StreamRecorder(OkHttpClient()),
            urls = StreamUrlResolver(db, XtreamApi(HttpFetcher(OkHttpClient()))),
            channelLookup = { channel },
        )
        manager.awaitReady()

        val error = manager.start(channel, "Testaufnahme", System.currentTimeMillis() + 60_000)

        assertNull(
            "start() meldet keinen Fehler zurück, obwohl der Vordergrunddienst nachweislich nicht starten konnte",
            error,
        )
        val recording = manager.recordings.value.firstOrNull { it.channelId == channel.id }
        assertNotNull("Aufnahme wurde nicht gespeichert", recording)
        assertEquals(
            "Status bleibt RECORDING, obwohl der schützende Vordergrund-Dienst nachweislich nicht lief - der " +
                "Nutzer sieht eine 'läuft'-Aufnahme ohne jede sichtbare Fehlermeldung",
            RecordingStatus.RECORDING,
            recording?.status,
        )
    }
}
