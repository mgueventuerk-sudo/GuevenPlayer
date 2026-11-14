package com.mg.player.data.handoff

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import com.mg.player.core.log.AppLog
import com.mg.player.core.net.MiniHttpServer
import com.mg.player.core.network.HttpFetcher
import com.mg.player.data.sport.proof.HubAuth
import com.mg.player.domain.model.PlayRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.net.URLEncoder
import kotlin.coroutines.resume

/**
 * „Auf dem Fernseher weiterschauen“ (README 3.56: *„braucht eine sichere Kopplung beider Geräte, eigener Schritt“*).
 * Diese Kopplung gibt es seit 3.57 – dieselbe wie beim Prüfgerät: Gerätefindung im Heimnetz (NSD), ein Geheimnis mit
 * 80 Bit Zufall und signierte Anfragen ([HubAuth]). Hier kommt nur die Route dazu.
 *
 * Der Fernseher lauscht (Schalter „Wiedergabe vom Handy übernehmen“), das Handy schickt eine signierte Anfrage mit
 * **Kennung und Position** – keine Stream-Adresse, keine Zugangsdaten. Der Fernseher löst den Inhalt über die eigene
 * Datenbank und den eigenen Zugang auf und startet ihn wie jeden anderen Start, samt Kindersicherung.
 *
 * Eigener Dienstname und eigener Port, getrennt vom Prüfgerät: Der Fernseher übernimmt Wiedergabe, auch wenn er nicht
 * das Gerät ist, das im Hintergrund Sport-Beweise sucht.
 */
class HandoffServer(private val context: Context, private val onPlay: (PlayRequest) -> Unit) {

    private var server: MiniHttpServer? = null
    private var registration: NsdManager.RegistrationListener? = null

    @Volatile
    private var key: ByteArray? = null

    @Volatile
    private var keyFor: String? = null

    /** Zufallswerte der letzten Anfragen – jede signierte Anfrage gilt nur einmal. */
    private val seen = java.util.concurrent.ConcurrentHashMap<String, Long>()

    /**
     * Lauschen. Ein geändertes Geheimnis wird sofort übernommen (Server neu anmelden).
     *
     * Synchronisiert: AppContainer.handoffLoop() ruft start() aus zwei Coroutinen (Einstellungsänderung und
     * Minuten-Nachprüfung) auf Dispatchers.IO – ohne Sperre sähen beide „kein Server“ und bauten zwei Server auf,
     * von denen der erste nie wieder beendet würde (offener Port, doppelte Anmeldung im Heimnetz).
     */
    @Synchronized
    fun start(secret: String) {
        if (secret.isBlank()) {
            // Kein Kennwort (mehr): Ein noch laufender Server mit dem alten Schlüssel muss weg – sonst bliebe sein Port offen.
            stop()
            return
        }
        // Nicht nur "ein Server existiert", sondern "er nimmt noch Verbindungen an": Ein Netzwechsel kann den
        // gebundenen Socket unter accept() wegziehen, ohne dass das Feld selbst null wird (MiniHttpServer.isAccepting).
        if (server?.isAccepting() == true && keyFor == secret) return
        if (server != null) stop()
        key = HubAuth.key(secret)
        keyFor = secret
        val http = MiniHttpServer(authorize = ::authorized) { request -> handle(request) }
        val base = http.start() ?: return
        server = http
        val port = base.substringAfterLast(':').toIntOrNull() ?: return
        val info = NsdServiceInfo().apply {
            serviceName = SERVICE_NAME
            serviceType = SERVICE_TYPE
            setPort(port)
        }
        val listener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(info: NsdServiceInfo) = AppLog.log("Übergabe", "Fernseher bereit (Port $port)")
            override fun onRegistrationFailed(info: NsdServiceInfo, code: Int) = AppLog.log("Übergabe", "Anmeldung im Heimnetz fehlgeschlagen ($code)")
            override fun onServiceUnregistered(info: NsdServiceInfo) = Unit
            override fun onUnregistrationFailed(info: NsdServiceInfo, code: Int) = Unit
        }
        runCatching { context.getSystemService(NsdManager::class.java)?.registerService(info, NsdManager.PROTOCOL_DNS_SD, listener) }
            .onSuccess { registration = listener }
    }

    @Synchronized
    fun stop() {
        registration?.let { listener -> runCatching { context.getSystemService(NsdManager::class.java)?.unregisterService(listener) } }
        registration = null
        server?.stop()
        server = null
        key = null
        keyFor = null
        seen.clear()
    }

    /** Signatur über Pfad, Zufallswert, Uhrzeit und Inhalt prüfen; Wiederholungen ablehnen. */
    private fun authorized(path: String, query: Map<String, String>): Boolean {
        val k = key ?: return false
        val nonce = query["n"]?.takeIf { it.length in NONCE_RANGE } ?: return false
        val ts = query["ts"]?.toLongOrNull()
        val now = System.currentTimeMillis()
        if (!signed(k, path, query, nonce, ts)) return false
        seen.entries.removeIf { it.value < now - KEEP_SKEWS * HubAuth.MAX_SKEW_MS }
        return seen.putIfAbsent(nonce, now) == null
    }

    /** Uhrzeit im erlaubten Fenster und Signatur gültig (Inhalt eingeschlossen). */
    private fun signed(key: ByteArray, path: String, query: Map<String, String>, nonce: String, ts: Long?): Boolean {
        if (!HubAuth.fresh(ts, System.currentTimeMillis())) {
            // Wie beim Prüfgerät: Abweichende Uhren sind die haeufigste Ursache und scheiterten frueher hinweislos.
            AppLog.log("Übergabe", "Anfrage mit abweichender Uhrzeit abgelehnt (erlaubt sind ${HubAuth.MAX_SKEW_MINUTES} Minuten)")
            return false
        }
        return HubAuth.verify(key, query["s"], path, nonce, ts.toString(), payload(query))
    }

    private fun handle(request: MiniHttpServer.Request): MiniHttpServer.Response = when (request.path) {
        PATH -> {
            val play = HandoffRequest.decode(request.query)
            if (play == null) {
                MiniHttpServer.Response.html(HTTP_BAD_REQUEST, "")
            } else {
                AppLog.log("Übergabe", "Vom Handy übernommen: ${play.javaClass.simpleName}")
                onPlay(play)
                MiniHttpServer.Response(HTTP_OK, "{}".toByteArray(), "application/json")
            }
        }
        else -> MiniHttpServer.Response.html(HTTP_NOT_FOUND, "")
    }

    companion object {
        const val SERVICE_TYPE = "_guevenhandoff._tcp."
        const val PATH = "/play"
        private const val SERVICE_NAME = "GuevenPlayer-Fernseher"
        private val NONCE_RANGE = 16..64
        private const val KEEP_SKEWS = 2
        private const val HTTP_OK = 200
        private const val HTTP_BAD_REQUEST = 400
        private const val HTTP_NOT_FOUND = 404

        /** Der Inhalt gehört in die Signatur, sonst liesse sich ein mitgeschnittener Aufruf auf etwas anderes biegen. */
        internal fun payload(query: Map<String, String>): String =
            HandoffRequest.encode(HandoffRequest.decode(query) ?: return "").orEmpty().toSortedMap()
                .entries.joinToString("&") { "${it.key}=${it.value}" }
    }
}

/** Gegenstück auf dem Handy: Fernseher im Heimnetz finden und die laufende Wiedergabe dorthin geben. */
class HandoffClient(private val context: Context, private val http: HttpFetcher) {

    @Volatile
    private var base: String? = null

    @Volatile
    private var keyFor: Pair<String, ByteArray>? = null

    private fun keyOf(secret: String): ByteArray =
        keyFor?.takeIf { it.first == secret }?.second ?: HubAuth.key(secret).also { keyFor = secret to it }

    /** true, wenn der Fernseher die Übergabe angenommen hat. */
    suspend fun send(secret: String, request: PlayRequest): Boolean = withContext(Dispatchers.IO) {
        if (secret.isBlank()) return@withContext false
        val fields = HandoffRequest.encode(request) ?: return@withContext false
        val url = (base ?: discover()?.also { base = it }) ?: return@withContext false
        val key = keyOf(secret)
        val nonce = HubAuth.nonce()
        val ts = System.currentTimeMillis().toString()
        val payload = fields.toSortedMap().entries.joinToString("&") { "${it.key}=${it.value}" }
        val signature = HubAuth.sign(key, HandoffServer.PATH, nonce, ts, payload)
        val query = fields.entries.joinToString("&") { "${it.key}=${enc(it.value)}" } + "&n=$nonce&ts=$ts&s=$signature"
        runCatching { http.getText("$url${HandoffServer.PATH}?$query", allowRedirects = false, allowInsecureHttp = true) }
            .onFailure { base = null }
            .isSuccess
    }

    private suspend fun discover(): String? {
        val manager = context.getSystemService(NsdManager::class.java) ?: return null
        val found = withTimeoutOrNull(DISCOVERY_MS) { find(manager) } ?: return null
        return withTimeoutOrNull(DISCOVERY_MS) { resolve(manager, found) }
    }

    private suspend fun find(manager: NsdManager): NsdServiceInfo = suspendCancellableCoroutine { cont ->
        val listener = object : NsdManager.DiscoveryListener {
            override fun onServiceFound(info: NsdServiceInfo) {
                if (cont.isActive) cont.resume(info)
                runCatching { manager.stopServiceDiscovery(this) }
            }
            override fun onDiscoveryStarted(type: String) = Unit
            override fun onServiceLost(info: NsdServiceInfo) = Unit
            override fun onDiscoveryStopped(type: String) = Unit
            override fun onStartDiscoveryFailed(type: String, code: Int) = Unit
            override fun onStopDiscoveryFailed(type: String, code: Int) = Unit
        }
        runCatching { manager.discoverServices(HandoffServer.SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener) }
        cont.invokeOnCancellation { runCatching { manager.stopServiceDiscovery(listener) } }
    }

    @Suppress("DEPRECATION")
    private suspend fun resolve(manager: NsdManager, info: NsdServiceInfo): String? = suspendCancellableCoroutine { cont ->
        runCatching {
            manager.resolveService(
                info,
                object : NsdManager.ResolveListener {
                    override fun onServiceResolved(resolved: NsdServiceInfo) {
                        if (cont.isActive) cont.resume(resolved.host?.hostAddress?.let { "http://$it:${resolved.port}" })
                    }

                    override fun onResolveFailed(info: NsdServiceInfo, code: Int) {
                        if (cont.isActive) cont.resume(null)
                    }
                },
            )
        }.onFailure { if (cont.isActive) cont.resume(null) }
    }

    private fun enc(value: String) = URLEncoder.encode(value, "UTF-8")

    private companion object {
        const val DISCOVERY_MS = 5_000L
    }
}
