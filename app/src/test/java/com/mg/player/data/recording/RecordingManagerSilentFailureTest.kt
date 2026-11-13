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
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Regressionsschutz für den Fund "stiller Recording-Fehler": RecordingService.start() gab früher immer Unit
 * zurück, auch wenn ContextCompat.startForegroundService() scheiterte (nur AppLog.error(), kein Signal an den
 * Aufrufer) - RecordingManager.begin() persistierte den Status trotzdem als RECORDING, ohne sichtbaren Fehler.
 * Jetzt gibt RecordingService.start() zurück, ob der Dienst wirklich lief, und begin() markiert die Aufnahme bei
 * false als FAILED mit einer Fehlermeldung, die über GuideViewModel.toggleRecording()/onResult() beim Nutzer
 * ankommt.
 *
 * Erzwingt einen echten Fehlschlag (ein ContextWrapper, dessen startForegroundService() wirft - wie es auf
 * Android 12+ real passieren kann, z. B. ForegroundServiceStartNotAllowedException) und prüft: start() meldet
 * jetzt einen Fehler, und der gespeicherte Status wird FAILED statt RECORDING.
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
    fun foregroundServiceFailureIsNowSurfacedAsFailedWithAnError() = runBlocking {
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

        assertNotNull(
            "Regression: start() meldet wieder keinen Fehler, obwohl der Vordergrunddienst nicht starten konnte",
            error,
        )
        val recording = manager.recordings.value.firstOrNull { it.channelId == channel.id }
        assertNotNull("Aufnahme wurde nicht gespeichert", recording)
        assertEquals(
            "Regression: Status ist nicht mehr FAILED, obwohl der schützende Vordergrund-Dienst nicht lief",
            RecordingStatus.FAILED,
            recording?.status,
        )
    }
}
