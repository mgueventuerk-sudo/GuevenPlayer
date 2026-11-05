package com.mg.player.data.sport.proof

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import com.mg.player.core.log.AppLog
import com.mg.player.core.net.MiniHttpServer
import com.mg.player.core.network.HttpFetcher
import com.mg.player.data.sport.proof.ProofEvaluator.Verdict
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.security.MessageDigest
import kotlin.coroutines.resume

/**
 * Plan „Spiel-Finder“ O: Ein Gerät sucht (Prüfgerät, z. B. Fire TV Cube), alle anderen wissen es.
 *
 * Prüfgerät: stellt seine Beweise im Heimnetz bereit (GET /proofs) und nimmt „Ich brauche diese Playlist“ entgegen
 * (GET /release) – dann prüft es dort 2 Minuten nicht (Regel „Freie Playlist“ 4). Gefunden wird es per Netzwerkdienst
 * (NSD, „_guevenproof._tcp“), geschützt durch einen 8-stelligen Code, den man am anderen Gerät einträgt. Übertragen werden
 * nur Spiel-Kennung, Senderschlüssel und Ergebnis – keine Zugangsdaten. Event-Kanäle (gerätebezogene Kanal-IDs) nicht.
 */
class ProofShareServer(private val context: Context, private val proofs: ProofStore, private val release: (String) -> Unit) {

    private var server: MiniHttpServer? = null
    private var registration: NsdManager.RegistrationListener? = null

    fun start(code: String) {
        if (server != null) return
        val http = MiniHttpServer(fixedToken = code) { request -> handle(request) }
        val base = http.start() ?: return
        server = http
        val port = base.substringAfterLast(':').toIntOrNull() ?: return
        val info = NsdServiceInfo().apply {
            serviceName = SERVICE_NAME
            serviceType = SERVICE_TYPE
            setPort(port)
        }
        val listener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(info: NsdServiceInfo) = AppLog.log("Sport", "Prüfgerät im Heimnetz bereit (Port $port)")
            override fun onRegistrationFailed(info: NsdServiceInfo, code: Int) =
                AppLog.log("Sport", "Prüfgerät: Anmeldung im Heimnetz fehlgeschlagen ($code)")
            override fun onServiceUnregistered(info: NsdServiceInfo) = Unit
            override fun onUnregistrationFailed(info: NsdServiceInfo, code: Int) = Unit
        }
        runCatching { context.getSystemService(NsdManager::class.java)?.registerService(info, NsdManager.PROTOCOL_DNS_SD, listener) }
            .onSuccess { registration = listener }
    }

    fun stop() {
        registration?.let { listener -> runCatching { context.getSystemService(NsdManager::class.java)?.unregisterService(listener) } }
        registration = null
        server?.stop()
        server = null
    }

    private fun handle(request: MiniHttpServer.Request): MiniHttpServer.Response {
        val http = server ?: return MiniHttpServer.Response.html(HTTP_UNAVAILABLE, "")
        if (!http.isAuthorized(request.param("t"))) return MiniHttpServer.Response.html(HTTP_FORBIDDEN, "")
        return when (request.path) {
            "/proofs" -> MiniHttpServer.Response(HTTP_OK, export().toByteArray(Charsets.UTF_8), "application/json")
            "/release" -> {
                request.param("a")?.takeIf { it.length in ACCOUNT_KEY_RANGE }?.let(release)
                MiniHttpServer.Response(HTTP_OK, "{}".toByteArray(), "application/json")
            }
            else -> MiniHttpServer.Response.html(HTTP_NOT_FOUND, "")
        }
    }

    /** Nur Senderschlüssel (gelten auf jedem Gerät), keine Kanal-IDs. */
    private fun export(): String = JSONArray(
        proofs.shareable().map { (eventId, key, verdict) -> JSONObject().put("e", eventId).put("k", key).put("v", verdict.name) },
    ).toString()

    companion object {
        const val SERVICE_TYPE = "_guevenproof._tcp."
        private const val SERVICE_NAME = "GuevenPlayer-Pruefgeraet"
        private const val HTTP_OK = 200
        private const val HTTP_FORBIDDEN = 403
        private const val HTTP_NOT_FOUND = 404
        private const val HTTP_UNAVAILABLE = 503
        private val ACCOUNT_KEY_RANGE = 8..64

        /** Neuer Code für das Prüfgerät (8 Ziffern, am anderen Gerät einzutippen). */
        fun newCode(): String = java.security.SecureRandom().let { random -> (1..CODE_LENGTH).map { random.nextInt(DIGITS) }.joinToString("") }

        /** Kennung eines Zugangs, die auf allen Geräten gleich ist (Adresse + Benutzer, gehasht – keine Zugangsdaten). */
        fun accountKey(serverUrl: String, username: String): String {
            val digest = MessageDigest.getInstance("SHA-256").digest("${serverUrl.trim().lowercase()}|${username.trim()}".toByteArray())
            return digest.take(ACCOUNT_BYTES).joinToString("") { "%02x".format(it) }
        }

        private const val CODE_LENGTH = 8
        private const val DIGITS = 10
        private const val ACCOUNT_BYTES = 8
    }
}

/** Gegenstück auf Stick und Handy: Prüfgerät im Heimnetz finden, Beweise übernehmen, „Ich brauche diese Playlist“ melden. */
class ProofShareClient(private val context: Context, private val http: HttpFetcher, private val proofs: ProofStore) {

    @Volatile
    private var base: String? = null

    /** Beweise vom Prüfgerät holen; liefert die Spiele mit neuem Ergebnis (leer ohne Code oder ohne Prüfgerät). */
    suspend fun pull(code: String): Set<String> = withContext(Dispatchers.IO) {
        if (code.isBlank()) return@withContext emptySet()
        val url = (base ?: discover()?.also { base = it }) ?: return@withContext emptySet()
        val text = runCatching { http.getText("$url/proofs?t=${enc(code)}", allowRedirects = false, allowInsecureHttp = true) }
            .onFailure { base = null }.getOrNull() ?: return@withContext emptySet()
        val array = runCatching { JSONArray(text) }.getOrNull() ?: return@withContext emptySet()
        val changed = HashSet<String>()
        for (i in 0 until array.length()) {
            val o = array.optJSONObject(i) ?: continue
            val verdict = Verdict.entries.firstOrNull { it.name == o.optString("v") } ?: continue
            val eventId = o.optString("e").takeIf { it.isNotBlank() } ?: continue
            val key = o.optString("k").takeIf { it.isNotBlank() } ?: continue
            if (proofs.verdictFor(eventId, listOf(key)) != verdict) {
                proofs.record(eventId, key, key, verdict, ProofHunter.BY_HUB)
                changed += eventId
            }
        }
        changed
    }

    /** Vorfahrt fürs Zuschauen: Das Prüfgerät gibt diese Playlist sofort frei (Regel „Freie Playlist“ 4). */
    suspend fun release(code: String, account: String) = withContext(Dispatchers.IO) {
        if (code.isBlank()) return@withContext
        val url = base ?: return@withContext
        runCatching { http.getText("$url/release?t=${enc(code)}&a=${enc(account)}", allowRedirects = false, allowInsecureHttp = true) }
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
        runCatching { manager.discoverServices(ProofShareServer.SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener) }
        cont.invokeOnCancellation { runCatching { manager.stopServiceDiscovery(listener) } }
    }

    @Suppress("DEPRECATION")
    private suspend fun resolve(manager: NsdManager, info: NsdServiceInfo): String? = suspendCancellableCoroutine { cont ->
        runCatching {
            manager.resolveService(
                info,
                object : NsdManager.ResolveListener {
                    override fun onServiceResolved(resolved: NsdServiceInfo) {
                        val host = resolved.host?.hostAddress
                        if (cont.isActive) cont.resume(host?.let { "http://$it:${resolved.port}" })
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
