package com.mg.player.core.vpn

import androidx.compose.runtime.Immutable
import com.mg.player.core.network.HttpFetcher
import java.io.IOException
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

/** Ein Surfshark-Server (WireGuard). */
@Immutable
data class SurfsharkServer(val host: String, val publicKey: String, val country: String, val countryCode: String?, val location: String, val load: Int?) {
    val label: String get() = if (location.isBlank() || location == country) country else "$country · $location"
}

data class SurfsharkTokens(val token: String, val renewToken: String)

/** Verständliche Meldung für den Nutzer (nie mit Passwort, Token oder Schlüssel). */
class SurfsharkException(message: String, val loginExpired: Boolean = false) : IOException(message)

/**
 * Surfshark-Konto über dieselbe Schnittstelle wie die Surfshark-Apps bzw. deren Router-Skripte:
 * Anmeldung → eigenen WireGuard-Schlüssel hinterlegen → Serverliste. Das Passwort wird nur für die Anmeldung
 * gesendet (HTTPS) und nie gespeichert.
 */
class SurfsharkApi(client: OkHttpClient, private val baseUrl: String = BASE_URL) {

    /** Surfshark-Anmeldedaten und Bearer-Tokens dürfen keinem Redirect folgen. */
    private val client = HttpFetcher.clientWithoutRedirects(client)

    suspend fun login(email: String, password: String): SurfsharkTokens = withContext(Dispatchers.IO) {
        val body = JSONObject().put("username", email.trim()).put("password", password)
        val (code, text) = call(post("/v1/auth/login", body, null))
        // Surfshark schützt die Anmeldung mit Cloudflare: Apps ohne Browser bekommen dort eine Prüfseite statt JSON.
        if (isBotWall(text)) {
            throw SurfsharkException(
                "Surfshark lässt die Anmeldung mit Passwort aus Apps derzeit nicht zu (Cloudflare-Schutz). " +
                    "Bitte einmal eine WireGuard-Datei aus deinem Surfshark-Konto übertragen – danach jeden Server hier per Klick wählen.",
            )
        }
        when (code) {
            200, 201 -> tokensOf(text) ?: throw SurfsharkException("Surfshark hat keine Anmeldung zurückgegeben – bitte später erneut versuchen.")

            400, 401, 404 -> throw SurfsharkException("E-Mail oder Passwort falsch.")

            403 -> throw SurfsharkException(
                "Surfshark verlangt eine zusätzliche Bestätigung (Captcha oder Zwei-Faktor). Bitte stattdessen „VPN einrichten“ mit einer .conf-Datei nutzen.",
            )

            429 -> throw SurfsharkException("Zu viele Anmeldeversuche – bitte in einigen Minuten erneut versuchen.")

            else -> throw SurfsharkException("Surfshark ist gerade nicht erreichbar (Fehler $code).")
        }
    }

    /** Hinterlegt den öffentlichen Schlüssel als dauerhaften „manuellen“ Schlüssel (wie in der Surfshark-Weboberfläche). */
    suspend fun registerKey(token: String, publicKey: String, name: String) = withContext(Dispatchers.IO) {
        val body = JSONObject().put("pubKey", publicKey).put("name", name).put("manual", true)
        val (code, _) = call(post("/v1/account/users/public-keys", body, token))
        when (code) {
            in 200..299, 409 -> Unit

            // 409: Schlüssel ist schon hinterlegt
            401 -> throw SurfsharkException("Die Surfshark-Anmeldung ist abgelaufen – bitte neu anmelden.", loginExpired = true)

            else -> throw SurfsharkException("Der VPN-Schlüssel ließ sich bei Surfshark nicht hinterlegen (Fehler $code).")
        }
    }

    /** Öffentliche Serverliste (ohne Anmeldung abrufbar). */
    suspend fun servers(): List<SurfsharkServer> = withContext(Dispatchers.IO) {
        val request = Request.Builder().url("$baseUrl/v4/server/clusters/generic?countryCode=").header("Accept", "application/json").get().build()
        val (code, text) = call(request)
        if (code !in 200..299 || isBotWall(text)) throw SurfsharkException("Die Surfshark-Serverliste ist gerade nicht abrufbar (Fehler $code).")
        parseServers(text)
    }

    private fun post(path: String, body: JSONObject, bearer: String?): Request = Request.Builder()
        .url(baseUrl + path)
        .post(body.toString().toRequestBody(JSON))
        .apply { if (bearer != null) header("Authorization", "Bearer $bearer") }
        .build()

    private fun call(request: Request): Pair<Int, String> = try {
        client.newCall(request).execute().use { response -> response.code to response.body.string() }
    } catch (e: IOException) {
        throw SurfsharkException("Keine Verbindung zu Surfshark – Internetverbindung prüfen.")
    }

    private fun isBotWall(text: String): Boolean =
        text.trimStart().startsWith("<") && (text.contains("cloudflare", ignoreCase = true) || text.contains("Attention Required", ignoreCase = true))

    private fun tokensOf(text: String): SurfsharkTokens? = runCatching {
        val o = JSONObject(text)
        SurfsharkTokens(o.getString("token"), o.optString("renewToken")).takeIf { it.token.isNotBlank() }
    }.getOrNull()

    companion object {
        private fun germanCountry(code: String?): String? = code?.let {
            runCatching { Locale.Builder().setRegion(it).build().getDisplayCountry(Locale.GERMAN) }.getOrNull()
        }?.takeIf { it.isNotBlank() && it != code }

        const val BASE_URL = "https://api.surfshark.com"
        private val JSON = "application/json; charset=utf-8".toMediaType()

        /** Nur Server mit WireGuard-Schlüssel; nach Land, dann Ort sortiert. */
        fun parseServers(text: String): List<SurfsharkServer> {
            val array = runCatching { JSONArray(text) }.getOrNull() ?: return emptyList()
            return (0 until array.length()).mapNotNull { i ->
                val o = array.optJSONObject(i) ?: return@mapNotNull null
                val host = o.optString("connectionName")
                val key = o.optString("pubKey")
                if (host.isBlank() || key.isBlank()) return@mapNotNull null
                val code = o.optString("countryCode").takeIf { it.length == 2 }?.uppercase()?.let { if (it == "UK") "GB" else it }
                SurfsharkServer(
                    host = host,
                    publicKey = key,
                    country = germanCountry(code) ?: o.optString("country").ifBlank { host.substringBefore('-').uppercase() },
                    countryCode = code,
                    location = o.optString("location"),
                    load = if (o.has("load")) o.optInt("load") else null,
                )
            }.distinctBy { it.host }.sortedWith(compareBy({ it.country }, { it.location }))
        }
    }
}
