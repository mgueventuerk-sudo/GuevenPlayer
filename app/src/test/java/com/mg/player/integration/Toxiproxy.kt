package com.mg.player.integration

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.junit.Assume.assumeTrue

/**
 * Schaltet Toxiproxy (github.com/Shopify/toxiproxy) zwischen App und einen lokalen Testserver, um echte TCP-Fehler
 * (Latenz, Abbruch mitten im Body, hängende Verbindung, Reset) zu erzeugen. Läuft kein toxiproxy-server unter
 * TOXIPROXY_URL (Standard http://127.0.0.1:8474), werden die Tests übersprungen statt rot.
 */
class Toxiproxy(private val api: String = System.getenv("TOXIPROXY_URL") ?: "http://127.0.0.1:8474") {
    private val http = OkHttpClient()
    private val json = "application/json".toMediaType()
    private val created = mutableListOf<String>()

    fun assumeRunning() = assumeTrue("toxiproxy-server läuft nicht unter $api", runCatching { call("GET", "/version") }.isSuccess)

    /** Neuer Proxy vor 127.0.0.1:[upstreamPort]; liefert "host:port" des Proxys. */
    fun proxy(name: String, upstreamPort: Int): String {
        call("DELETE", "/proxies/$name", allowMissing = true)
        val body = call("POST", "/proxies", """{"name":"$name","listen":"127.0.0.1:0","upstream":"127.0.0.1:$upstreamPort","enabled":true}""")
        created += name
        return requireNotNull(Regex("\"listen\":\"([^\"]+)\"").find(body)) { body }.groupValues[1]
    }

    fun toxic(proxy: String, type: String, attributes: String, stream: String = "downstream") {
        call("POST", "/proxies/$proxy/toxics", """{"name":"$type-$stream","type":"$type","stream":"$stream","toxicity":1.0,"attributes":{$attributes}}""")
    }

    fun reset() {
        created.forEach { call("DELETE", "/proxies/$it", allowMissing = true) }
        created.clear()
    }

    private fun call(method: String, path: String, body: String? = null, allowMissing: Boolean = false): String {
        val request = Request.Builder().url(api + path).method(method, body?.toRequestBody(json)).build()
        http.newCall(request).execute().use { response ->
            val text = response.body.string()
            check(response.isSuccessful || (allowMissing && response.code == 404)) { "Toxiproxy $method $path → HTTP ${response.code}: $text" }
            return text
        }
    }
}
