package com.mg.player.core.network

import com.mg.player.BuildConfig
import java.io.BufferedInputStream
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPInputStream
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.Request

/** User-Agent, den praktisch alle IPTV-Panels akzeptieren. */
const val IPTV_USER_AGENT = "VLC/3.0.20 LibVLC/3.0.20"

/** Für Dienste außerhalb der IPTV-Anbieter (Sport-Daten): Die App gibt sich als das aus, was sie ist. */
val APP_USER_AGENT = "GuevenPlayer/${BuildConfig.VERSION_NAME} (Android)"

/** HTTP-Fehlercode einer API-Anfrage; [retryAfterSec] aus dem Kopf „Retry-After“ (bei 429). */
class HttpStatusException(val code: Int, val retryAfterSec: Long?) : IOException("Server antwortet mit HTTP $code")

/**
 * Blockierende HTTP-Downloads als Stream. Nur auf Dispatchers.IO aufrufen.
 * Der Aufrufer muss den zurückgegebenen Stream schließen.
 */
class HttpFetcher(val client: OkHttpClient) {

    /** Getrennter Client für API-Aufrufe, deren Zugangsdaten nicht per Redirect weitergereicht werden dürfen. */
    private val noRedirectClient by lazy {
        clientWithoutRedirects(client)
    }

    /** Erlaubt sichere Weiterleitungen, blockiert aber HTTPS -> HTTP-Downgrades. */
    private val noDowngradeClient by lazy { client.newBuilder().followSslRedirects(false).build() }

    /** Kleine Text-Antworten (z. B. Sport-APIs) mit ehrlichem User-Agent. Fehlercodes als [HttpStatusException]. */
    fun getText(
        url: String,
        headers: Map<String, String> = emptyMap(),
        allowRedirects: Boolean = true,
        allowInsecureHttp: Boolean = false,
    ): String {
        requireHttpPermission(url, allowInsecureHttp)
        val request = Request.Builder().url(url).header("User-Agent", APP_USER_AGENT)
            .apply { headers.forEach { (name, value) -> header(name, value) } }
            .build()
        val requestClient = when {
            !allowRedirects -> noRedirectClient
            allowInsecureHttp -> client
            else -> noDowngradeClient
        }
        // Gesamtzeit begrenzen: readTimeout gilt nur je Lesevorgang – ein tröpfelnder Server hielte den Aufruf sonst endlos.
        requestClient.newCall(request).apply { timeout().timeout(API_CALL_TIMEOUT_S, TimeUnit.SECONDS) }.execute().use { response ->
            if (!response.isSuccessful) throw HttpStatusException(response.code, response.header("Retry-After")?.toLongOrNull())
            return readLimited(response.body)
        }
    }

    fun openStream(url: String): InputStream = openStream(url, allowInsecureHttp = false)

    fun openStream(url: String, allowInsecureHttp: Boolean): InputStream {
        requireHttpPermission(url, allowInsecureHttp)
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", IPTV_USER_AGENT)
            .build()
        val requestClient = if (allowInsecureHttp) client else noDowngradeClient
        val response = requestClient.newCall(request).execute()
        if (!response.isSuccessful) {
            val code = response.code
            response.close()
            throw IOException("Server antwortet mit HTTP $code")
        }
        // OkHttp 5: body ist nie null (leere Antwort = leerer Datenstrom).
        val body = response.body
        // Große Downloads (M3U, EPG) dürfen lange dauern, aber nicht tröpfeln: unter MIN_BYTES je Minute wird abgebrochen.
        return BufferedInputStream(ThroughputGuard(body.byteStream()), BUFFER_SIZE)
    }

    /** Erkennt gzip anhand der Magic Bytes (für .xml.gz-EPG-Dateien). */
    fun openMaybeGzip(url: String): InputStream = openMaybeGzip(url, allowInsecureHttp = false)

    fun openMaybeGzip(url: String, allowInsecureHttp: Boolean): InputStream {
        val stream = openStream(url, allowInsecureHttp)
        stream.mark(4)
        val b1 = stream.read()
        val b2 = stream.read()
        stream.reset()
        return if (b1 == 0x1f && b2 == 0x8b) {
            BufferedInputStream(GZIPInputStream(stream, BUFFER_SIZE), BUFFER_SIZE)
        } else {
            stream
        }
    }

    /** Kleine Antworten vollständig lesen, aber nie mehr als [MAX_TEXT_BYTES] in den Speicher (Audit A-15). */
    private fun readLimited(body: okhttp3.ResponseBody): String {
        if (body.contentLength() > MAX_TEXT_BYTES) throw IOException("Antwort zu groß (${body.contentLength()} Bytes)")
        val source = body.source()
        if (source.request(MAX_TEXT_BYTES + 1)) throw IOException("Antwort zu groß (über $MAX_TEXT_BYTES Bytes)")
        val charset = body.contentType()?.charset() ?: Charsets.UTF_8
        return source.buffer.readString(charset)
    }

    companion object {
        private const val BUFFER_SIZE = 64 * 1024

        /** Obergrenze für Text-Antworten (Sport-APIs, TMDB-Details …); große Listen laufen über [openStream]. */
        const val MAX_TEXT_BYTES = 8L * 1024 * 1024
        const val API_CALL_TIMEOUT_S = 120L

        private fun requireHttpPermission(url: String, allowed: Boolean) {
            if (url.substringBefore(':').equals("http", ignoreCase = true) && !allowed) {
                throw IOException("Unverschlüsseltes HTTP ist für diesen Zugang nicht freigegeben")
            }
        }

        /** TMDB-v3-Schlüssel können in URLs stehen; diese Client-Variante folgt deshalb keinen Redirects. */
        fun clientWithoutRedirects(client: OkHttpClient): OkHttpClient = client.newBuilder()
            .followRedirects(false)
            .followSslRedirects(false)
            .build()

        fun createClient(dns: Dns = Dns.SYSTEM): OkHttpClient = OkHttpClient.Builder()
            .dns(dns)
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .retryOnConnectionFailure(true)
            .build()
    }
}

/**
 * Bricht einen Download ab, der zwar Daten liefert, aber zu wenige: weniger als [minBytes] in [windowMs].
 * (readTimeout greift dort nicht, weil immer wieder ein paar Bytes kommen.)
 */
class ThroughputGuard(
    input: InputStream,
    private val minBytes: Long = 16 * 1024,
    private val windowMs: Long = 60_000,
    private val clock: () -> Long = System::currentTimeMillis,
) : java.io.FilterInputStream(input) {
    private var windowStart = clock()
    private var bytesInWindow = 0L

    private fun count(n: Int) {
        if (n > 0) bytesInWindow += n
        val now = clock()
        if (now - windowStart >= windowMs) {
            if (bytesInWindow < minBytes && n >= 0) {
                throw IOException("Server liefert zu langsam (unter ${minBytes / 1024} KB pro Minute) – abgebrochen")
            }
            windowStart = now
            bytesInWindow = 0
        }
    }

    override fun read(): Int = super.read().also { count(if (it < 0) -1 else 1) }

    override fun read(b: ByteArray, off: Int, len: Int): Int = super.read(b, off, len).also { count(it) }
}
