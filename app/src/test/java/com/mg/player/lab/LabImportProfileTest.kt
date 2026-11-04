package com.mg.player.lab

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.mg.player.core.PlayerApp
import com.mg.player.domain.model.PlaylistType
import com.mg.player.domain.repository.PlaylistInput
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Virtuelles Labor (Plan B4): kompletter M3U-Import von 10 000 Sendern auf der JVM mit Stichproben-Profiler.
 * Ein Hilfsthread liest alle 5 ms die Ablaufspur des Import-Threads; die häufigsten App-Methoden zeigen, wo die
 * Zeit bleibt. Nur mit GUEVEN_LAB=true.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class LabImportProfileTest {

    private val server = MockWebServer()
    private val container get() = ApplicationProvider.getApplicationContext<PlayerApp>().container

    @Before
    fun onlyInLab() = assumeTrue(System.getenv("GUEVEN_LAB") == "true")

    @After
    fun tearDown() = server.shutdown()

    private fun m3u(n: Int) = buildString {
        appendLine("#EXTM3U")
        for (i in 1..n) {
            appendLine("#EXTINF:-1 tvg-id=\"ch$i.de\" tvg-name=\"Sender $i\" tvg-logo=\"\" group-title=\"Gruppe ${i % 40}\",DE| Sender $i HD")
            appendLine("http://127.0.0.1:8090/ok/media/$i.ts")
        }
    }

    @Test
    fun profileM3uImport() = runBlocking {
        server.enqueue(MockResponse().setBody(m3u(N)))
        server.start()
        val pid = container.playlistRepository.addPlaylist(
            PlaylistInput("Lab", PlaylistType.M3U, m3uUrl = server.url("/list.m3u").toString(), allowInsecureHttp = true),
        )
        val samples = HashMap<String, Int>()
        val running = java.util.concurrent.atomic.AtomicBoolean(true)
        val sampler = Thread {
            while (running.get()) {
                // Import läuft auf Dispatchers.IO: alle Worker-Threads prüfen, je Spur die oberste App-Methode zählen.
                Thread.getAllStackTraces().forEach { (thread, trace) ->
                    if ("DefaultDispatcher" !in thread.name) return@forEach
                    trace.firstOrNull { it.className.startsWith("com.mg.player") && ".lab." !in it.className }
                        ?.let { samples.merge("${it.className.substringAfterLast('.')}.${it.methodName}", 1, Int::plus) }
                }
                Thread.sleep(SAMPLE_MS)
            }
        }.apply { start() }
        val phases = LinkedHashMap<String, Long>()
        val start = System.nanoTime()
        container.playlistRepository.refreshContent(pid) { phases.putIfAbsent(it.message, (System.nanoTime() - start) / 1_000_000) }
        val totalMs = (System.nanoTime() - start) / 1_000_000
        running.set(false)
        sampler.join()
        println("LAB|jvm.import.m3u$N.ms|$totalMs")
        println("LAB|jvm.import.m3u$N.phasen|${phases.entries.joinToString { "${it.key}@${it.value}" }}")
        samples.entries.sortedByDescending { it.value }.take(TOP).forEach { println("LAB|jvm.import.profil|${it.key}=${it.value * SAMPLE_MS} ms") }
    }

    private companion object {
        const val N = 10_000
        const val SAMPLE_MS = 5L
        const val TOP = 15
    }
}
