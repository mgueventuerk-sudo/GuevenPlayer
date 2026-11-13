package com.mg.player.data.parser

import androidx.test.ext.junit.runners.AndroidJUnit4
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Misst (nicht vermutet): XtreamDetailParser.parseSeriesInfo() ist keine suspend-Funktion und hat innerhalb des
 * Parse-Vorgangs keinen einzigen Suspension-Point (anders als XmltvParser/M3uParser mit ihrem yield() alle N
 * Zeilen/Ereignisse, siehe ParserFuzzTest.cancellingTheParseStopsItEarly, und anders als JsonExt.forEachObject,
 * das genau wegen dieser Lücke ein eigenes yield() hat - parseSeriesInfo nutzt forEachObject aber nicht, sondern
 * eigene Schleifen in readEpisodes()/readSeasonArray()). Eine Coroutine-Cancellation kann eine laufende Berechnung
 * nur an einem Suspension-Point abfangen - ohne einen solchen läuft die Funktion unabhängig vom Abbruchsignal bis
 * zum Ende durch.
 *
 * Dieser Test cancelt den Job, während der Parse auf einem echten Hintergrundthread (Dispatchers.Default) mitten
 * in einer grossen, synthetischen Serie läuft, und prüft: Trotz Cancel kommt das VOLLSTÄNDIGE Ergebnis zurück -
 * die Berechnung wurde nicht verworfen.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class XtreamDetailParserCancellationTest {

    @Test
    fun cancellingMidParseDoesNotStopIt() = runBlocking {
        val episodeCount = 200_000
        val doc = buildString {
            append("""{"info":{"plot":"x"},"episodes":{"1":[""")
            repeat(episodeCount) { i ->
                if (i > 0) append(",")
                append("""{"id":"$i","episode_num":"$i","title":"E$i","container_extension":"mp4"}""")
            }
            append("]}}")
        }

        val job = Job()
        val scope = CoroutineScope(coroutineContext + job + Dispatchers.Default)
        var result: XtreamDetailParser.XSeriesInfo? = null
        // Ohne dieses Signal wäre ein scharfes Rennen möglich: cancel() könnte den Job schon treffen, bevor
        // Dispatchers.Default die Coroutine überhaupt startet - das würde nur belegen, dass ein noch gar nicht
        // gestarteter Launch abbrechbar ist, nicht dass ein LAUFENDER Parse das Abbruchsignal ignoriert.
        val started = AtomicBoolean(false)
        val work = scope.launch {
            started.set(true)
            result = XtreamDetailParser.parseSeriesInfo(doc.byteInputStream())
        }
        while (!started.get()) {
            // Busy-Wait ist hier bewusst: Jede suspend-Wartefunktion (delay/yield) würde selbst einen
            // Suspension-Point einführen und das Timing verfälschen, das genau geprüft werden soll.
        }
        job.cancel()
        work.join()

        assertTrue(
            "Erwartet: trotz Abbruch mitten im Parse liegt das VOLLSTÄNDIGE Ergebnis vor ($episodeCount Folgen) " +
                "- Beleg dafür, dass parseSeriesInfo() keinen Suspension-Point hat und Cancellation ignoriert. " +
                "Tatsächlich erhalten: ${result?.episodes?.size ?: 0}",
            result?.episodes?.size == episodeCount,
        )
    }
}
