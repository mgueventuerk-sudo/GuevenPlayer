package com.mg.player.data.sport.guide

import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import java.lang.management.ManagementFactory
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.zip.GZIPInputStream
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Speichertest mit einer echten Programmdatei von epgshare01 (nur von Hand: GUIDE_FILE=/pfad/CH1.xml.gz).
 * Misst den größten lebenden Speicher während des Einlesens (nach erzwungener Speicherbereinigung alle 100 ms),
 * den Speicher des gefilterten Ergebnisses und die Größe des Caches. Ohne GUIDE_FILE wird der Test übersprungen.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class GuideMemoryTest {

    /** Belegter Heap nach vollständiger Speicherbereinigung (mehrfach, bis sich der Wert nicht mehr ändert). */
    private fun liveBytes(): Long {
        val bean = ManagementFactory.getMemoryMXBean()
        var last = Long.MAX_VALUE
        repeat(5) {
            System.gc()
            Thread.sleep(20)
            val used = bean.heapMemoryUsage.used
            if (used >= last - 64 * 1024) return used
            last = used
        }
        return last
    }

    @Test
    fun largestGuideFileStaysSmall() = runBlocking {
        val path = System.getenv("GUIDE_FILE")
        assumeTrue("GUIDE_FILE nicht gesetzt", path != null && File(path).exists())
        val file = File(path!!)
        val now = System.currentTimeMillis()
        val from = now - 12 * 3_600_000L
        val to = now + 8 * 24 * 3_600_000L

        // Erst ohne Messung: reine Dauer (das Ergebnis wird sofort verworfen).
        val t0 = System.nanoTime()
        val warm = GZIPInputStream(file.inputStream().buffered()).use { SportGuideStore.parse(it, "ch", from, to) }.programs.size
        val plainMs = (System.nanoTime() - t0) / 1_000_000
        assertTrue(warm > 0)

        val base = liveBytes()
        val peakRef = AtomicLong(base)
        val running = AtomicBoolean(true)
        val sampler = Thread {
            while (running.get()) {
                val live = liveBytes()
                peakRef.updateAndGet { maxOf(it, live) }
                Thread.sleep(100)
            }
        }.apply { start() }
        val guide = GZIPInputStream(file.inputStream().buffered()).use { SportGuideStore.parse(it, "ch", from, to) }
        running.set(false)
        sampler.join()
        val peak = peakRef.get()

        val cache = File.createTempFile("guide", ".tsv.gz")
        SportGuideStore.writeCache(cache, guide)
        val readBase = liveBytes()
        val reread = SportGuideStore.readCache(cache)!!
        val readRetained = liveBytes() - readBase
        val mb = { b: Long -> "%.1f MB".format(b / 1_048_576.0) }
        val report = buildString {
            appendLine("Datei: ${file.name}, ${mb(file.length())} gepackt")
            appendLine("Einlesen: $plainMs ms, ${guide.channels.size} Sport-Sender, ${guide.programs.size} Sendungen")
            appendLine("Größter lebender Speicher beim Einlesen: +${mb(peak - base)}")
            appendLine("Cache-Datei: ${mb(cache.length())}, nach dem Lesen im Speicher: ${mb(readRetained)} (${reread.programs.size} Sendungen)")
        }
        println(report)
        File("build/guide-memory-${file.name.substringBefore('.')}.txt").writeText(report)
        cache.delete()
        assertTrue("zu viel Speicher beim Einlesen: ${mb(peak - base)}", peak - base < 48L * 1_048_576)
    }
}
