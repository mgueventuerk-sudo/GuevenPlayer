package com.mg.player.ui.detail

import android.annotation.SuppressLint
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.mg.player.core.log.AppLog
import com.mg.player.ui.common.ActionButton
import com.mg.player.ui.common.glass
import com.mg.player.ui.navigation.LocalNavigator
import com.mg.player.ui.theme.AppColors
import com.mg.player.ui.theme.AppIcons
import com.mg.player.ui.theme.StaticIcon
import java.io.IOException
import java.io.InputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Trailer-Vorschlag bei Film/Serie: YouTubes eigene, schlanke Embed-Seite läuft eingebettet in GuevenPlayer weiter -
 * kein Website-Drumherum (keine Kommentare, keine „Nächstes Video“-Vorschläge), kein Wechsel zur YouTube-App und
 * kein externer Browser. Bleibt technisch YouTubes Player (eine eigene Stream-Wiedergabe verstieße gegen YouTubes
 * Nutzungsbedingungen und bräche bei jeder YouTube-Änderung neu). „Zurück“ (Fernbedienung oder Handy-Geste, über
 * den globalen BackHandler in AppRoot) kehrt sofort zum Titel zurück.
 *
 * JavaScript ist für YouTubes Embed-Player zwingend - unbedenklich, weil dieses WebView nie eine fremde oder
 * nutzergesteuerte Adresse lädt, nur die feste, selbst erzeugte Seite unten.
 *
 * Weder loadUrl() direkt auf die Embed-Adresse noch loadDataWithBaseURL() mit youtube.com als Basis funktionieren
 * (an echten YouTube-Servern nachgewiesen, Fehler 153 bzw. 152): Eine Seite, die nie wirklich über eine Adresse
 * navigiert wurde, schickt für das eingebettete Iframe gar keinen Referer, egal welcher Origin-Wert angegeben wird -
 * YouTubes Einbettungs-Prüfung lehnt das ab. [TrailerLocalServer] liefert die Wrapper-Seite deshalb wirklich über
 * HTTP aus (nur Loopback), genau wie ein echter Browser-Tab.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun TrailerScreen(youtubeKey: String) {
    val navigator = LocalNavigator.current
    var failed by remember { mutableStateOf(false) }
    var failDetail by remember { mutableStateOf<String?>(null) }
    var baseUrl by remember { mutableStateOf<String?>(null) }

    val server = remember(youtubeKey) { TrailerLocalServer(youtubeKey) }
    // start() wartet bis zu BIND_TIMEOUT_MS auf das Binden - nicht im Hauptthread.
    LaunchedEffect(server) {
        val url = try {
            withContext(Dispatchers.IO) { server.start() }
        } catch (e: CancellationException) {
            // Das Blockieren in start() ist nicht abbrechbar: Der Socket steht dann schon und muss wieder zu.
            server.stop()
            throw e
        }
        if (url == null) {
            failed = true
            failDetail = server.lastFailureReason
        } else {
            baseUrl = url
        }
    }
    DisposableEffect(server) { onDispose { server.stop() } }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        val url = baseUrl
        if (failed) {
            Column(Modifier.align(Alignment.Center).padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("Trailer konnte nicht geladen werden", color = Color.White, style = MaterialTheme.typography.titleMedium)
                // Vorübergehend sichtbar, bis die Ursache auf echten Geräten bestätigt ist - siehe Klassenkommentar.
                failDetail?.let {
                    Box(Modifier.height(8.dp))
                    Text(it, color = Color.Gray, style = MaterialTheme.typography.bodySmall)
                }
                Box(Modifier.height(16.dp))
                ActionButton("Zurück", onClick = navigator::pop, primary = true)
            }
        } else if (url != null) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { context ->
                    WebView(context).apply {
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        // Ohne Tipp im eingebetteten Player starten - "nahtlos", kein zweiter Schritt nach dem Laden.
                        settings.mediaPlaybackRequiresUserGesture = false
                        webViewClient = object : WebViewClient() {
                            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                                // Nur die eigene Wrapper-Seite zählt als Fehlschlag - ein vom Video selbst
                                // abgelehntes Iframe zeigt YouTubes eigene, verständliche Fehlerseite darin an.
                                if (request.isForMainFrame) {
                                    failed = true
                                    failDetail = "WebView-Fehler ${error.errorCode}: ${error.description}"
                                }
                            }
                        }
                        loadUrl(url)
                    }
                },
                onRelease = { it.stopLoading(); it.destroy() },
            )
        }
        Box(
            Modifier
                .align(Alignment.TopEnd)
                .padding(20.dp)
                .size(40.dp)
                .glass(CircleShape, raised = true)
                .clickable(onClickLabel = "Zurück", onClick = navigator::pop),
            contentAlignment = Alignment.Center,
        ) {
            StaticIcon(AppIcons.Close, tint = AppColors.TextPrimary, size = 20.dp, contentDescription = "Zurück")
        }
    }
}

/**
 * Winziger Server nur auf Loopback (127.0.0.1) - liefert für jede Anfrage dieselbe feste Seite mit dem
 * YouTube-Iframe aus. Anders als [com.mg.player.core.net.MiniHttpServer] (für die Fernseher-Übergabe absichtlich
 * auf die Heimnetz-Adresse gebunden): Hier reicht Loopback, und dessen strenge Content-Security-Policy würde das
 * YouTube-Iframe ohnehin blockieren (kein frame-src erlaubt).
 */
internal class TrailerLocalServer(private val youtubeKey: String) {
    @Volatile
    private var running = false
    private var socket: ServerSocket? = null

    /**
     * Nur gesetzt, wenn [start] zuletzt mit `null` zurückkam - der Grund dafür, zur Anzeige in der Fehlerkarte
     * und zur Fehlersuche. Reicht ein gemeldetes "Trailer konnte nicht geladen werden" nicht zur Diagnose
     * (wie beim Fund vom 2026-11-12: lief auf allen drei CI-Emulatoren, aber nicht auf einem bestimmten
     * echten Gerät), zeigt das hier den tatsächlichen Grund, statt erneut zu raten.
     */
    var lastFailureReason: String? = null
        private set

    /**
     * ServerSocket(...) bindet - das ist für Android ab API 11 immer eine Netzwerkoperation, auch auf Loopback.
     * Direkt auf dem Hauptthread (TrailerScreen ruft start() aus DisposableEffect auf) wirft das eine
     * NetworkOnMainThreadException - lautlos verschluckt vom runCatching, start() lieferte dann immer null
     * zurück (Fehlerkarte bei jedem Titel, nicht nur manchen; auf Robolectric nicht nachstellbar, das erzwingt
     * diese Regel nicht). Bind UND Annahme-Schleife laufen deshalb beide im selben Hintergrund-Thread; start()
     * wartet nur auf das Ergebnis des Bindens, nicht auf Netzwerk-E/A.
     *
     * Bindet jetzt ausdrücklich auf die IPv4-Loopback-Adresse (vorher InetAddress.getLoopbackAddress() - deren
     * Rückgabe ist laut Javadoc Implementierungssache, IPv4 oder IPv6) und baut die URL aus der tatsächlich
     * gebundenen Adresse, nicht aus einem festen "127.0.0.1"-String: Ein Auseinanderlaufen beider Werte würde
     * den WebView auf eine Adresse zugreifen lassen, an der gar nichts horcht - ununterscheidbar von jedem
     * anderen Fehlschlag, ohne dass eine Exception auftritt. Ob das die Ursache auf dem gemeldeten Gerät war,
     * ist unbestätigt [Vermutung] - deshalb bleibt [lastFailureReason] bis zur Bestätigung sichtbar.
     */
    fun start(): String? {
        lastFailureReason = null
        val result = AtomicReference<String?>(null)
        val bound = CountDownLatch(1)
        Thread {
            val server = runCatching {
                ServerSocket(0, BACKLOG, InetAddress.getByName(LOOPBACK_IP))
            }.onFailure { e ->
                lastFailureReason = "Binden gescheitert: ${e.javaClass.simpleName}: ${e.message}"
            }.getOrNull()
            if (server == null) {
                bound.countDown()
                return@Thread
            }
            socket = server
            running = true
            result.set("http://${server.inetAddress.hostAddress}:${server.localPort}/")
            bound.countDown()
            while (running) {
                val client = try {
                    server.accept()
                } catch (e: IOException) {
                    // Erwartet beim Beenden (stop() schliesst den Socket) - alles andere waere ungewoehnlich genug fuers Protokoll.
                    AppLog.debug("Trailer", "Loopback-Server beendet (${e.javaClass.simpleName})")
                    break
                }
                runCatching { respond(client) }
            }
        }.apply { isDaemon = true }.start()
        val inTime = bound.await(BIND_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        if (!inTime && result.get() == null) {
            lastFailureReason = "Zeitüberschreitung beim Starten (> ${BIND_TIMEOUT_MS}ms)"
        }
        return result.get()
    }

    fun stop() {
        running = false
        runCatching { socket?.close() }
    }

    private fun respond(client: Socket) {
        client.use { s ->
            s.soTimeout = REQUEST_TIMEOUT_MS
            runCatching { discardRequest(s.getInputStream()) }
            val body = html().toByteArray(Charsets.UTF_8)
            val header = "HTTP/1.1 200 OK\r\nContent-Type: text/html; charset=utf-8\r\n" +
                "Content-Length: ${body.size}\r\nConnection: close\r\n\r\n"
            s.getOutputStream().apply {
                write(header.toByteArray(Charsets.UTF_8))
                write(body)
                flush()
            }
        }
    }

    /** Nur bis zur Leerzeile lesen - der Inhalt der Antwort ist immer gleich, es gibt nur die eine Seite. */
    private fun discardRequest(input: InputStream) {
        var sawNewline = false
        while (true) {
            val b = input.read()
            if (b < 0) return
            when (b) {
                '\n'.code -> {
                    if (sawNewline) return
                    sawNewline = true
                }
                '\r'.code -> Unit
                else -> sawNewline = false
            }
        }
    }

    private fun html() =
        """
        <!doctype html><html><body style="margin:0;padding:0;background:#000;">
        <iframe width="100%" height="100%" style="position:fixed;top:0;left:0;border:0;"
            src="https://www.youtube.com/embed/$youtubeKey?autoplay=1&playsinline=1"
            allow="autoplay; encrypted-media" allowfullscreen></iframe>
        </body></html>
        """.trimIndent()

    private companion object {
        const val LOOPBACK_IP = "127.0.0.1"
        const val BACKLOG = 8
        const val REQUEST_TIMEOUT_MS = 5_000
        const val BIND_TIMEOUT_MS = 2_000L
    }
}
