package com.mg.player.core.net

import androidx.core.net.toUri
import com.mg.player.core.log.AppLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.net.SocketTimeoutException
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.Semaphore

/**
 * Winziger HTTP-Server im lokalen Netz, über den das Handy mit dem Fernseher spricht
 * (Einrichtung, Sicherung, Fehlerprotokoll). Jede URL enthält ein Einmal-Token.
 */
class MiniHttpServer(
    private val maxBodyBytes: Int = 16 * 1024,
    private val requestDeadlineMs: Long = 20_000,
    private val handler: suspend (Request) -> Response,
) {

    data class Request(val method: String, val path: String, val query: Map<String, String>, val body: String) {
        fun param(name: String): String? = query[name]
    }

    class Response(val code: Int, val body: ByteArray, val contentType: String = "text/html; charset=utf-8", val headers: Map<String, String> = emptyMap()) {
        companion object {
            fun html(code: Int, html: String) = Response(code, html.toByteArray(Charsets.UTF_8))
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val slots = Semaphore(MAX_CLIENTS)

    @Volatile
    private var localNet: Pair<InetAddress, Int>? = null
    private var serverSocket: ServerSocket? = null

    val token: String = SecureRandom().let { random ->
        (1..TOKEN_LENGTH).map { TOKEN_ALPHABET[random.nextInt(TOKEN_ALPHABET.length)] }.joinToString("")
    }

    /** Token-Vergleich in konstanter Zeit (kein Rückschluss über die Antwortzeit, wie viele Zeichen stimmen). */
    fun isAuthorized(candidate: String?): Boolean =
        candidate != null && MessageDigest.isEqual(candidate.toByteArray(Charsets.UTF_8), token.toByteArray(Charsets.UTF_8))

    /** Startet den Server und liefert die Basis-Adresse (z. B. http://192.168.1.20:8765) oder null ohne Netzwerk. */
    fun start(): String? {
        val ip = localIpAddress() ?: return null
        for (port in PORTS) {
            // Ein fehlgeschlagenes bind() schließt den Socket – daher pro Versuch ein neuer.
            val socket = ServerSocket()
            socket.reuseAddress = true
            // Nur an die Heimnetz-Adresse binden, nicht an alle Schnittstellen (Mobilfunk, VPN …).
            val address = InetAddress.getByName(ip)
            localNet = address to (
                runCatching {
                    NetworkInterface.getByInetAddress(address)?.interfaceAddresses?.firstOrNull { it.address == address }?.networkPrefixLength?.toInt()
                }.getOrNull() ?: 24
                )
            if (runCatching { socket.bind(InetSocketAddress(address, port)) }.isFailure) {
                runCatching { socket.close() }
                continue
            }
            serverSocket = socket
            scope.launch { acceptLoop(socket) }
            return "http://$ip:$port"
        }
        return null
    }

    fun stop() {
        runCatching { serverSocket?.close() }
        serverSocket = null
        scope.cancel()
    }

    private fun acceptLoop(socket: ServerSocket) {
        while (!socket.isClosed) {
            val client = try {
                socket.accept()
            } catch (e: SocketException) {
                break
            }
            // Nur Geräte aus dem eigenen Netz; höchstens [MAX_CLIENTS] gleichzeitig – sonst könnten ein paar
            // Anfragen aus dem WLAN einen schwachen TV-Stick mit offenen Verbindungen lahmlegen.
            if (!isLocal(client.inetAddress) || !slots.tryAcquire()) {
                runCatching { client.close() }
                continue
            }
            scope.launch {
                try {
                    handle(client)
                } finally {
                    slots.release()
                }
            }
        }
    }

    private suspend fun handle(client: Socket) {
        client.use { s ->
            s.soTimeout = 15_000
            try {
                val input = s.getInputStream()
                // Gesamtfrist statt nur soTimeout: sonst hält ein Byte alle 14 s einen der vier Plätze für immer.
                val deadline = System.nanoTime() + java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(requestDeadlineMs)
                val requestLine = readLine(input, deadline) ?: return
                val parts = requestLine.split(' ')
                val uri = ("http://x" + parts.getOrNull(1).orEmpty()).toUri()
                val query = uri.queryParameterNames.associateWith { uri.getQueryParameter(it).orEmpty() }
                // Token prüfen, BEVOR Kopfzeilen und Inhalt gelesen werden (kein Speicher für fremde Anfragen).
                if (!isAuthorized(query["t"])) {
                    respond(s, Response.html(403, "<h1>Abgelehnt</h1><p>Bitte den QR-Code am Fernseher erneut scannen.</p>"))
                    discardRest(s, input)
                    return
                }
                var contentLength = 0
                for (i in 0 until MAX_HEADERS) {
                    val header = readLine(input, deadline) ?: break
                    if (header.isEmpty()) break
                    if (header.startsWith("Content-Length:", ignoreCase = true)) {
                        contentLength = header.substringAfter(':').trim().toIntOrNull()?.coerceAtLeast(0) ?: 0
                    }
                }
                // Zu groß: ablehnen statt abschneiden – ein abgeschnittenes Backup darf nie beim Handler ankommen.
                if (contentLength > maxBodyBytes) {
                    respond(s, Response.html(HTTP_PAYLOAD_TOO_LARGE, "<h1>Zu groß</h1><p>Die Datei ist zu groß für den Fernseher.</p>"))
                    discardRest(s, input)
                    return
                }
                val body = ByteArray(contentLength)
                var read = 0
                while (read < contentLength) {
                    if (System.nanoTime() > deadline) throw SocketTimeoutException("Anfrage zu langsam")
                    val n = input.read(body, read, contentLength - read)
                    if (n < 0) break
                    read += n
                }
                // Verbindung vor dem Ende des Inhalts abgebrochen: nie ein abgeschnittenes Backup/Formular weitergeben.
                if (read < contentLength) {
                    respond(s, Response.html(HTTP_BAD_REQUEST, "<h1>Unvollständig</h1><p>Die Übertragung wurde abgebrochen.</p>"))
                    return
                }
                val request = Request(parts.getOrNull(0).orEmpty(), uri.path.orEmpty().ifEmpty { "/" }, query, String(body, 0, read, Charsets.UTF_8))
                respond(s, handler(request))
            } catch (e: Exception) {
                AppLog.debug("Heimnetz", "Anfrage fehlgeschlagen (${e.javaClass.simpleName})")
            }
        }
    }

    /**
     * Antwort ist raus: Den Rest der Anfrage verwerfen (ohne ihn zu speichern), höchstens 64 KB und 0,5 s.
     * Sonst setzt das System die Verbindung beim Schließen zurück und der Browser sieht die Antwort nicht.
     */
    private fun discardRest(s: Socket, input: InputStream) {
        runCatching {
            s.shutdownOutput()
            s.soTimeout = 500
            val buffer = ByteArray(4_096)
            var total = 0
            while (total < 64 * 1024) {
                val n = input.read(buffer)
                if (n < 0) break
                total += n
            }
        }
    }

    /** Heimnetz: private Adressbereiche oder dasselbe Teilnetz wie der Fernseher (z. B. bei ungewöhnlichen Routern). */
    private fun isLocal(address: InetAddress?): Boolean {
        if (address == null) return false
        if (address.isSiteLocalAddress || address.isLinkLocalAddress || address.isLoopbackAddress) return true
        val (own, prefix) = localNet ?: return false
        return sameSubnet(own.address, address.address, prefix)
    }

    private fun respond(s: Socket, response: Response) {
        val header = StringBuilder()
            .append("HTTP/1.1 ${response.code} ${reasonPhrase(response.code)}\r\n")
            .append("Content-Type: ${response.contentType}\r\n")
            .append("Content-Length: ${response.body.size}\r\n")
            .append("Cache-Control: no-store\r\n")
            .append("Connection: close\r\n")
        SECURITY_HEADERS.forEach { (k, v) -> header.append("$k: $v\r\n") }
        // Nur sichere Kopfzeilen des Handlers (kein Zeilenumbruch = keine eingeschleusten Kopfzeilen).
        response.headers.filter { (k, v) -> isSafeHeader(k, v) }.forEach { (k, v) -> header.append("$k: $v\r\n") }
        header.append("\r\n")
        s.getOutputStream().apply {
            write(header.toString().toByteArray(Charsets.UTF_8))
            write(response.body)
            flush()
        }
    }

    /** Liest eine Kopfzeile byteweise (der Body kann beliebige UTF-8-Daten enthalten). */
    private fun readLine(input: InputStream, deadline: Long): String? {
        val buffer = ByteArrayOutputStream()
        while (true) {
            if (System.nanoTime() > deadline) throw SocketTimeoutException("Anfrage zu langsam")
            val b = input.read()
            if (b < 0) return if (buffer.size() == 0) null else buffer.toString("UTF-8")
            if (b == '\n'.code) break
            if (b != '\r'.code) buffer.write(b)
            if (buffer.size() > 8_192) break
        }
        return buffer.toString("UTF-8")
    }

    private fun localIpAddress(): String? = try {
        NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
            .filter { it.isUp && !it.isLoopback }
            .sortedBy { if (it.name.startsWith("wlan") || it.name.startsWith("eth")) 0 else 1 }
            .flatMap { it.inetAddresses.toList() }
            .firstOrNull { it is Inet4Address && !it.isLoopbackAddress }
            ?.hostAddress
    } catch (e: SocketException) {
        null
    }

    private companion object {
        const val TOKEN_LENGTH = 16
        const val TOKEN_ALPHABET = "abcdefghjkmnpqrstuvwxyz23456789"
        val PORTS = listOf(8765, 8766, 8767, 8768, 8769)
        const val MAX_CLIENTS = 4
        const val MAX_HEADERS = 64
    }
}

/** Liegen beide Adressen im selben Teilnetz (Präfixlänge in Bit)? */
internal fun sameSubnet(a: ByteArray, b: ByteArray, prefix: Int): Boolean {
    if (a.size != b.size) return false
    var bits = prefix.coerceIn(0, a.size * 8)
    for (i in a.indices) {
        if (bits <= 0) return true
        val mask = if (bits >= 8) 0xFF else (0xFF shl (8 - bits)) and 0xFF
        if ((a[i].toInt() and mask) != (b[i].toInt() and mask)) return false
        bits -= 8
    }
    return true
}

private const val HTTP_PAYLOAD_TOO_LARGE = 413
private const val HTTP_BAD_REQUEST = 400

/**
 * Schutz für die Seiten im Handy-Browser: Das Token steht in der Adresse – kein Referer an fremde Seiten,
 * keine fremden Skripte/Verbindungen (CSP), kein Einbetten in fremde Seiten, kein MIME-Raten.
 */
internal val SECURITY_HEADERS = linkedMapOf(
    "Referrer-Policy" to "no-referrer",
    "X-Content-Type-Options" to "nosniff",
    "X-Frame-Options" to "DENY",
    "Content-Security-Policy" to
        "default-src 'none'; script-src 'unsafe-inline'; style-src 'unsafe-inline'; connect-src 'self'; " +
        "img-src 'self' data:; form-action 'self'; base-uri 'none'; frame-ancestors 'none'",
)

private val REASON_PHRASES = mapOf(
    200 to "OK",
    400 to "Bad Request",
    403 to "Forbidden",
    404 to "Not Found",
    409 to "Conflict",
    HTTP_PAYLOAD_TOO_LARGE to "Payload Too Large",
    500 to "Internal Server Error",
)

internal fun reasonPhrase(code: Int): String = REASON_PHRASES[code] ?: "Error"

/** RFC 7230: Name nur aus Token-Zeichen, Wert ohne Steuerzeichen (verhindert eingeschleuste Kopfzeilen). */
internal fun isSafeHeader(name: String, value: String): Boolean =
    name.isNotEmpty() && name.all { it in HEADER_TOKEN_CHARS || it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' } &&
        value.none { it.isISOControl() && it != '\t' }

private const val HEADER_TOKEN_CHARS = "!#$%&'*+-.^_`|~"
