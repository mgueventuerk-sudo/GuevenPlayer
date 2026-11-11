package com.mg.player.data.remote

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.mg.player.core.PlayerApp
import com.mg.player.domain.model.PlaylistType
import com.mg.player.domain.repository.PlaylistInput
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * checkNow() startete die Abfrage auf dem langlebigen Monitor-Scope, nicht auf dem Scope des Aufrufers - beim
 * schnellen Senderwechsel (jeder Zapp ruft checkNow() auf) blieb die vorherige Abfrage nach der Zeitüberschreitung
 * als Kind von scope bestehen, statt abgebrochen zu werden (Audit-Fund).
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class ConnectionMonitorTest {

    private val slow = MockWebServer()
    private val container get() = ApplicationProvider.getApplicationContext<PlayerApp>().container
    private var monitorScope: CoroutineScope? = null

    @After
    fun tearDown() {
        monitorScope?.cancel()
        slow.shutdown()
    }

    @Test
    fun checkNowCancelsTheBackgroundFetchInsteadOfLeavingItOrphaned() = runBlocking {
        // Die Antwort kommt erst nach 5 s - lange nach der 200-ms-Frist, die checkNow() vor dem Abspielen setzt.
        slow.enqueue(MockResponse().setBody("{}").setBodyDelay(5, TimeUnit.SECONDS))
        slow.start()
        val pid = container.playlistRepository.addPlaylist(
            PlaylistInput(
                name = "Test",
                type = PlaylistType.XTREAM,
                serverUrl = slow.url("/").toString().trimEnd('/'),
                username = "u",
                password = "p",
                allowInsecureHttp = true,
            ),
        )
        val scope = CoroutineScope(Job() + Dispatchers.Default)
        monitorScope = scope
        val monitor = ConnectionMonitor(container.database, container.xtreamApi, scope)

        // ConnectionMonitor startet beim Konstruieren selbst schon einen Dauerläufer (das "live"-StateFlow) als
        // Kind von scope - der zählt hier nicht, nur der von checkNow() selbst erzeugte Hintergrund-Job.
        val before = scope.coroutineContext.job.children.toSet()
        val result = withTimeout(2_000) { monitor.checkNow(pid, timeoutMs = 200) }
        assertEquals(null, result)
        val ownFetch = scope.coroutineContext.job.children.toSet() - before
        assertTrue("checkNow() sollte einen eigenen Hintergrund-Job erzeugt haben", ownFetch.isNotEmpty())
        // Der blockierende Netzaufruf selbst lässt sich dadurch nicht sofort stoppen (dieselbe Grenze wie bei
        // jedem blockierenden HttpFetcher-Aufruf in dieser App) - aber die Job-Buchführung muss nach checkNow()
        // sofort "abgebrochen" zeigen. Sonst häuft schnelles Umschalten unbegrenzt viele Kinder auf scope an.
        assertFalse(
            "Die Hintergrundabfrage von checkNow() sollte nach der Rückkehr nicht mehr aktiv sein",
            ownFetch.any { it.isActive },
        )
    }
}
