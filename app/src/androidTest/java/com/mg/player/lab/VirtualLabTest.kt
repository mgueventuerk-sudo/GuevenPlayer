package com.mg.player.lab

import android.graphics.SurfaceTexture
import android.net.http.X509TrustManagerExtensions
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.Surface
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.mg.player.core.PlayerApp
import com.mg.player.domain.model.BufferProfile
import com.mg.player.domain.model.HwDecoding
import com.mg.player.domain.model.PlaylistType
import com.mg.player.domain.repository.PlaylistInput
import com.mg.player.player.engine.BufferLimit
import com.mg.player.player.engine.Media3Build
import com.mg.player.player.engine.Media3Config
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.security.KeyStore
import java.security.cert.CertificateException
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

/**
 * Virtuelles Testlabor (Overhaul A/B, 3.23): läuft nur mit `-e lab true` gegen den Mock-Anbieter aus
 * tools/virtual-lab/mock_iptv.py (per `adb reverse tcp:8090 tcp:8090`). Ergebnisse als Logzeilen „LAB|Schlüssel|Wert“.
 */
@RunWith(AndroidJUnit4::class)
class VirtualLabTest {

    private val app get() = ApplicationProvider.getApplicationContext<PlayerApp>()
    private val base = "http://127.0.0.1:8090"

    @Before
    fun onlyInLab() = assumeTrue(InstrumentationRegistry.getArguments().getString("lab") == "true")

    private fun report(key: String, value: Any) {
        Log.i("LAB", "LAB|$key|$value")
    }

    // ---- Sicherheit: Zertifikats-Pinning auf echtem Android ----

    private fun chain(asset: String): Array<X509Certificate> {
        val factory = CertificateFactory.getInstance("X.509")
        return InstrumentationRegistry.getInstrumentation().context.assets.open("pinning/$asset").use { input ->
            factory.generateCertificates(input).map { it as X509Certificate }.toTypedArray()
        }
    }

    private fun platformTrust(): X509TrustManagerExtensions {
        val factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
        factory.init(null as KeyStore?)
        return X509TrustManagerExtensions(factory.trustManagers.first { it is X509TrustManager } as X509TrustManager)
    }

    /** Fremde, aber gültige Kette (DigiCert) für den TMDB-Host: muss am Pin scheitern – ohne Pin ginge sie durch. */
    @Test
    fun pinningRejectsValidChainOfOtherCa() {
        val trust = platformTrust()
        val digicert = chain("digicert_chain.pem")
        trust.checkServerTrusted(digicert, "GENERIC", "example.org") // Gegenprobe: Kette selbst ist vertrauenswürdig
        val rejected = try {
            trust.checkServerTrusted(digicert, "GENERIC", "api.themoviedb.org")
            false
        } catch (e: CertificateException) {
            report("pin.fremdeCA.meldung", e.message.orEmpty())
            true
        }
        report("pin.fremdeCA.abgelehnt", rejected)
        assertTrue("Pinning greift nicht: fremde CA für api.themoviedb.org akzeptiert", rejected)
    }

    @Test
    fun pinningAcceptsRealChain() {
        platformTrust().checkServerTrusted(chain("amazon_chain.pem"), "GENERIC", "api.themoviedb.org")
        report("pin.echteKette.akzeptiert", true)
    }

    /** Echte Verbindungen (Emulator mit -http-proxy): jede gepinnte Domain muss erreichbar bleiben. */
    @Test
    fun pinnedHostsReachableThroughAppClient() {
        val client = app.container.httpFetcher.client
        val hosts = listOf("api.themoviedb.org", "sports.bzzoiro.com", "api.sportmonks.com", "api.surfshark.com", "cloudflare-dns.com", "dns.quad9.net")
        val failures = hosts.mapNotNull { host ->
            runCatching { client.newCall(Request.Builder().url("https://$host/").head().build()).execute().use { it.code } }
                .onSuccess { report("pin.live.$host", "HTTP $it") }
                .onFailure { report("pin.live.$host", "FEHLER ${it.javaClass.simpleName}: ${it.message}") }
                .exceptionOrNull()?.let { host }
        }
        assertEquals("Gepinnte Hosts nicht erreichbar", emptyList<String>(), failures)
    }

    // ---- Geschwindigkeit: Import (Testfall 4) mit Hauptthread-Wächter ----

    /** Misst, wie lange der Hauptthread höchstens nicht reagiert (ANR ab 5 s). */
    private class MainThreadWatch {
        private val handler = Handler(Looper.getMainLooper())
        val worstMs = AtomicLong(0)

        @Volatile private var running = true
        private val thread = Thread {
            while (running) {
                val posted = SystemClock.uptimeMillis()
                val done = CountDownLatch(1)
                handler.post { done.countDown() }
                done.await(30, TimeUnit.SECONDS)
                worstMs.accumulateAndGet(SystemClock.uptimeMillis() - posted, ::maxOf)
                SystemClock.sleep(50)
            }
        }.apply { start() }

        fun stop(): Long {
            running = false
            thread.join()
            return worstMs.get()
        }
    }

    private fun importAndMeasure(label: String, input: PlaylistInput, withEpg: Boolean) = runBlocking {
        val c = app.container
        val id = c.playlistRepository.addPlaylist(input)
        val watch = MainThreadWatch()
        val phases = LinkedHashMap<String, Long>()
        val start = SystemClock.elapsedRealtime()
        val error = runCatching {
            c.refreshPlaylist(id, withEpg) { phases.putIfAbsent(it.message, SystemClock.elapsedRealtime() - start) }
        }.exceptionOrNull()
        val contentMs = SystemClock.elapsedRealtime() - start
        val indexStart = SystemClock.elapsedRealtime()
        if (error == null) c.searchIndexer.rebuild(id, force = true)
        val indexMs = SystemClock.elapsedRealtime() - indexStart
        val worst = watch.stop()
        report("import.$label.ms", contentMs)
        report("import.$label.suchindex.ms", indexMs)
        report("import.$label.hauptthread.maxMs", worst)
        report("import.$label.phasen", phases.entries.joinToString { "${it.key}@${it.value}" })
        report("import.$label.sender", c.database.channelDao().count(id))
        report("import.$label.fehler", error?.message ?: "-")
        id to error
    }

    @Test
    fun importM3u10k() {
        val (_, error) = importAndMeasure("m3u10k", PlaylistInput("Lab M3U", PlaylistType.M3U, m3uUrl = "$base/ok/m3u", allowInsecureHttp = true), withEpg = false)
        assertEquals(null, error)
    }

    @Test
    fun importXtreamFullWithEpg() {
        val input = PlaylistInput("Lab Xtream", PlaylistType.XTREAM, serverUrl = "$base/ok", username = "lab", password = LAB_PASSWORD, allowInsecureHttp = true)
        val (_, error) = importAndMeasure("xtream", input, withEpg = true)
        assertEquals(null, error)
    }

    // ---- Stabilität: Ausfallpfade (Testfall 5) – Fehlermeldung statt Absturz ----

    @Test
    fun failurePathsGiveMessagesNotCrashes() {
        val modes = listOf("expired", "badpass", "err500", "empty", "broken", "drop", "slow")
        modes.forEach { mode ->
            val input = PlaylistInput("Lab $mode", PlaylistType.XTREAM, serverUrl = "$base/$mode", username = "lab", password = LAB_PASSWORD, allowInsecureHttp = true)
            val (id, error) = importAndMeasure("ausfall.$mode", input, withEpg = false)
            report("ausfall.$mode.ergebnis", error?.let { "${it.javaClass.simpleName}: ${it.message}" } ?: "kein Fehler")
            runBlocking { app.container.playlistRepository.deletePlaylist(id) }
        }
        val dead = PlaylistInput("Lab tot", PlaylistType.M3U, m3uUrl = "http://127.0.0.1:9/tot.m3u", allowInsecureHttp = true)
        val (deadId, deadError) = importAndMeasure("ausfall.tot", dead, withEpg = false)
        report("ausfall.tot.ergebnis", deadError?.let { "${it.javaClass.simpleName}: ${it.message}" } ?: "kein Fehler")
        runBlocking { app.container.playlistRepository.deletePlaylist(deadId) }
    }

    // ---- Geschwindigkeit: Zapping auf Engine-Ebene (Testfall 3), ein Player wird wiederverwendet ----

    @OptIn(UnstableApi::class)
    @Test
    fun zappingFirstFrame20Switches() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val texture = SurfaceTexture(0)
        val surface = Surface(texture)
        var player: Player? = null
        val firstFrame = arrayOf(CountDownLatch(1))
        instrumentation.runOnMainSync {
            val config = Media3Config(BufferProfile.entries.first(), HwDecoding.entries.first(), tunneling = false, passthrough = false)
            player = Media3Build.player(app, config, BufferLimit.forDevice(heapMb = 192, lowRamDevice = true), OkHttpDataSource.Factory(OkHttpClient())).apply {
                setVideoSurface(surface)
                addListener(object : Player.Listener {
                    override fun onRenderedFirstFrame() = firstFrame[0].countDown()
                })
                playWhenReady = true
            }
        }
        val times = ArrayList<Long>()
        repeat(ZAPS) { i ->
            firstFrame[0] = CountDownLatch(1)
            val start = SystemClock.elapsedRealtime()
            instrumentation.runOnMainSync {
                player!!.setMediaItem(MediaItem.fromUri("$base/ok/live/lab/x/${i % 2}.ts"))
                player!!.prepare()
            }
            val ok = firstFrame[0].await(ZAP_TIMEOUT_S, TimeUnit.SECONDS)
            val took = SystemClock.elapsedRealtime() - start
            times += if (ok) took else ZAP_TIMEOUT_S * 1000
            report("zap.$i.ms", if (ok) took else "Zeitüberschreitung")
        }
        instrumentation.runOnMainSync { player!!.release() }
        surface.release()
        texture.release()
        val sorted = times.sorted()
        report("zap.median.ms", sorted[sorted.size / 2])
        report("zap.max.ms", sorted.last())
        report("zap.ueber2500", times.count { it > 2_500 })
        report("zap.ueber5000", times.count { it > 5_000 })
    }

    private companion object {
        const val LAB_PASSWORD = "Geheim#Lab-2026!"
        const val ZAPS = 20
        const val ZAP_TIMEOUT_S = 30L
    }
}
