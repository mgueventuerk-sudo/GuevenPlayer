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
 * (NSD, „_guevenproof._tcp“), geschützt durch das Geheimnis des Prüfgeräts, das man am anderen Gerät einträgt oder
 * als QR-Code abliest; das Geheimnis selbst wird nie gesendet, Anfragen und Antworten sind damit signiert ([HubAuth]).
 * Übertragen werden nur Spiel-Kennung, Senderschlüssel und Ergebnis – keine Zugangsdaten. Event-Kanäle
 * (gerätebezogene Kanal-IDs) nicht.
 */
class ProofShareServer(private val context: Context, private val proofs: ProofStore, private val release: (String) -> Unit) {

    private var server: MiniHttpServer? = null
    private var registration: NsdManager.RegistrationListener? = null

    @Volatile
    private var key: ByteArray? = null

    /** Geheimnis des abgeleiteten Schlüssels – die Ableitung eines alten Zifferncodes ist teuer, darum gemerkt. */
    @Volatile
    private var keyFor: String? = null

    /**
     * Zufallswerte der letzten Anfragen (Zeitpunkt) – jede signierte Anfrage gilt nur einmal.
     *
     * Die Tabelle lebt nur im Arbeitsspeicher: Nach einem Neustart des Prüfgeräts ist eine kurz davor mitgeschnittene
     * Anfrage im Zeitfenster noch einmal gültig. Das bleibt bewusst so, weil eine Wiederholung hier nichts einbringt:
     * `/proofs` liefert nur dieselben Daten, die der Mitschnitt schon enthält, und `/release` verlängert bloß eine
     * 2-Minuten-Pause, die derselbe Mitschnitt ohnehin ausgelöst hatte. Ein Riegel über den Startzeitpunkt würde die
     * Lücke nicht schließen (die Anfrage liegt im Zeitfenster) und Geräte mit nachgehender Uhr abweisen.
     */
    private val seen = java.util.concurrent.ConcurrentHashMap<String, Long>()

    /**
     * Prüfgerät bereitstellen. Läuft es schon mit einem **anderen** Geheimnis (der Nutzer hat in den Einstellungen ein
     * neues erzeugt), wird es erst beendet und dann neu angemeldet: Sonst signierte es bis zum nächsten App-Start
     * weiter mit dem alten Schlüssel, und das neue Geheimnis bliebe wirkungslos.
     */
    fun start(secret: String) {
        // Nicht nur "ein Server existiert", sondern "er nimmt noch Verbindungen an": Ein Netzwechsel kann den
        // gebundenen Socket unter accept() wegziehen, ohne dass das Feld selbst null wird (MiniHttpServer.isAccepting).
        // shareLoop() ruft start() ohnehin im Minutenabstand erneut auf - so greift der Neuaufbau tatsächlich.
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
        key = null
        keyFor = null
        seen.clear()
    }

    /** Signatur über Pfad, Zufallswert, Uhrzeit und Playlist-Kennung prüfen; Wiederholungen ablehnen. */
    private fun authorized(path: String, query: Map<String, String>): Boolean {
        val k = key ?: return false
        val nonce = query["n"]?.takeIf { it.length in NONCE_RANGE } ?: return false
        val ts = query["ts"]?.toLongOrNull()
        val now = System.currentTimeMillis()
        if (!signed(k, path, query, nonce, ts)) return false
        seen.entries.removeIf { it.value < now - KEEP_NONCE_SKEWS * HubAuth.MAX_SKEW_MS }
        return seen.putIfAbsent(nonce, now) == null
    }

    /** Uhrzeit im erlaubten Fenster und Signatur gültig. */
    private fun signed(key: ByteArray, path: String, query: Map<String, String>, nonce: String, ts: Long?): Boolean {
        if (!HubAuth.fresh(ts, System.currentTimeMillis())) {
            // Häufigste Ursache ist keine Attacke, sondern eine abweichende Uhr: TV-Boxen ohne Zeitabgleich driften,
            // und dann scheitert die Anmeldung ohne jeden Hinweis. Darum eigens vermerken.
            AppLog.log(
                "Sport",
                "Prüfgerät: Anfrage mit abweichender Uhrzeit abgelehnt (erlaubt sind ${HubAuth.MAX_SKEW_MINUTES} Minuten)",
            )
            return false
        }
        return HubAuth.verify(key, query["s"], path, nonce, ts.toString(), query["a"].orEmpty())
    }

    private fun handle(request: MiniHttpServer.Request): MiniHttpServer.Response {
        val k = key ?: return MiniHttpServer.Response.html(HTTP_UNAVAILABLE, "")
        return when (request.path) {
            "/proofs" -> {
                val data = export()
                val signed = JSONObject().put("d", data).put("s", HubAuth.sign(k, RESPONSE, request.param("n").orEmpty(), data))
                MiniHttpServer.Response(HTTP_OK, signed.toString().toByteArray(Charsets.UTF_8), "application/json")
            }
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
        internal const val RESPONSE = "antwort"
        internal val NONCE_RANGE = 16..64

        /** Zufallswerte so lange merken wie zwei Zeitfenster – danach kann die Anfrage ohnehin nicht mehr gelten. */
        private const val KEEP_NONCE_SKEWS = 2
        private const val SERVICE_NAME = "GuevenPlayer-Pruefgeraet"
        private const val HTTP_OK = 200
        private const val HTTP_NOT_FOUND = 404
        private const val HTTP_UNAVAILABLE = 503
        private val ACCOUNT_KEY_RANGE = 8..64

        /** Neues Geheimnis für das Prüfgerät (siehe [HubAuth.newSecret]) – am anderen Gerät eintippen oder abscannen. */
        fun newSecret(): String = HubAuth.newSecret()

        /** Kennung eines Zugangs, die auf allen Geräten gleich ist (Adresse + Benutzer, gehasht – keine Zugangsdaten). */
        fun accountKey(serverUrl: String, username: String): String {
            val digest = MessageDigest.getInstance("SHA-256").digest("${serverUrl.trim().lowercase()}|${username.trim()}".toByteArray())
            return digest.take(ACCOUNT_BYTES).joinToString("") { "%02x".format(it) }
        }

        private const val ACCOUNT_BYTES = 8
    }
}

/** Gegenstück auf Stick und Handy: Prüfgerät im Heimnetz finden, Beweise übernehmen, „Ich brauche diese Playlist“ melden. */
class ProofShareClient(private val context: Context, private val http: HttpFetcher, private val proofs: ProofStore) {

    @Volatile
    private var base: String? = null

    /** Abgeleiteter Schlüssel zum eingetragenen Code (die Ableitung ist absichtlich langsam, daher gemerkt). */
    @Volatile
    private var keyFor: Pair<String, ByteArray>? = null

    private fun keyOf(code: String): ByteArray = keyFor?.takeIf { it.first == code }?.second ?: HubAuth.key(code).also { keyFor = code to it }

    /** Signierte Anfrage: Code wird nie gesendet; [account] ist Teil der Signatur. */
    private fun signedQuery(key: ByteArray, path: String, nonce: String, account: String = ""): String {
        val ts = System.currentTimeMillis().toString()
        val a = if (account.isEmpty()) "" else "&a=${enc(account)}"
        return "n=$nonce&ts=$ts&s=${HubAuth.sign(key, path, nonce, ts, account)}$a"
    }

    /**
     * Beweise vom Prüfgerät holen; liefert die Spiele mit neuem Ergebnis (leer ohne Code oder ohne Prüfgerät).
     * Nur eine Antwort mit gültiger Signatur zählt – sonst war es nicht das eigene Prüfgerät (nächstes Mal neu suchen).
     */
    suspend fun pull(code: String): Set<String> = withContext(Dispatchers.IO) {
        if (code.isBlank()) return@withContext emptySet()
        val url = (base ?: discover()?.also { base = it }) ?: return@withContext emptySet()
        val key = keyOf(code)
        val nonce = HubAuth.nonce()
        val text = runCatching { http.getText("$url/proofs?${signedQuery(key, "/proofs", nonce)}", allowRedirects = false, allowInsecureHttp = true) }
            .onFailure { base = null }.getOrNull() ?: return@withContext emptySet()
        val signed = runCatching { JSONObject(text) }.getOrNull()
        val data = signed?.optString("d")
        if (data == null || !HubAuth.verify(key, signed.optString("s"), ProofShareServer.RESPONSE, nonce, data)) {
            base = null
            return@withContext emptySet()
        }
        val array = runCatching { JSONArray(data) }.getOrNull() ?: return@withContext emptySet()
        (0 until array.length()).mapNotNull { i -> array.optJSONObject(i)?.let(::adopt) }.toSet()
    }

    /** Ein Ergebnis vom Prüfgerät übernehmen; liefert das Spiel, wenn sich etwas geändert hat. */
    private fun adopt(o: org.json.JSONObject): String? {
        val verdict = Verdict.entries.firstOrNull { it.name == o.optString("v") }
        val eventId = o.optString("e").takeIf { it.isNotBlank() }
        val key = o.optString("k").takeIf { it.isNotBlank() }
        if (verdict == null || eventId == null || key == null) return null
        if (proofs.verdictFor(eventId, listOf(key)) == verdict) return null
        proofs.record(eventId, key, key, verdict, ProofHunter.BY_HUB)
        return eventId
    }

    /** Vorfahrt fürs Zuschauen: Das Prüfgerät gibt diese Playlist sofort frei (Regel „Freie Playlist“ 4). */
    suspend fun release(code: String, account: String) = withContext(Dispatchers.IO) {
        if (code.isBlank()) return@withContext
        val url = base ?: return@withContext
        val query = signedQuery(keyOf(code), "/release", HubAuth.nonce(), account)
        runCatching { http.getText("$url/release?$query", allowRedirects = false, allowInsecureHttp = true) }
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
