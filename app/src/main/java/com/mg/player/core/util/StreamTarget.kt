package com.mg.player.core.util

import com.mg.player.core.net.isSafeHeader
import java.net.URLDecoder
import java.net.URLEncoder

/**
 * Stream-Adresse plus HTTP-Header. In der Datenbank steht beides im verbreiteten Kodi-Format
 * `http://server/stream.ts|User-Agent=Foo&Referer=http%3A%2F%2Fseite` – so wie es viele M3U-Listen direkt
 * liefern; `#EXTVLCOPT:http-user-agent=…` / `http-referrer=…` wird beim Import in dieses Format übersetzt.
 * Erst beim Abspielen wird getrennt: die Engine bekommt die reine Adresse und die Header einzeln.
 */
data class StreamTarget(val url: String, val headers: Map<String, String> = emptyMap()) {

    val userAgent: String? get() = headers.entries.firstOrNull { it.key.equals(USER_AGENT, ignoreCase = true) }?.value

    /** Alle Header außer dem User-Agent (der wird bei den Engines separat gesetzt). */
    val extraHeaders: Map<String, String> get() = headers.filterKeys { !it.equals(USER_AGENT, ignoreCase = true) }

    companion object {
        const val USER_AGENT = "User-Agent"
        const val REFERER = "Referer"

        fun parse(raw: String): StreamTarget {
            val pipe = raw.indexOf('|')
            if (pipe < 0) return StreamTarget(raw.trim())
            val url = raw.substring(0, pipe).trim()
            val headers = LinkedHashMap<String, String>()
            raw.substring(pipe + 1).split('&').forEach { part ->
                val eq = part.indexOf('=')
                if (eq <= 0) return@forEach
                val name = decode(part.substring(0, eq)).trim()
                val value = decode(part.substring(eq + 1)).trim()
                // Aus der Playlist, also fremd: nur gültige Kopfzeilen (OkHttp würfe sonst IllegalArgumentException mitten
                // in Wiedergabe oder Aufnahme), und ein Zeilenumbruch könnte weitere Kopfzeilen einschleusen.
                if (value.isNotEmpty() && isSafeHeader(name, value)) {
                    headers[canonical(name)] = value
                }
            }
            return StreamTarget(url, headers)
        }

        /** Hängt Header an eine Adresse an (bestehende Header der Adresse bleiben erhalten). */
        fun withHeaders(raw: String, headers: Map<String, String>): String {
            if (headers.isEmpty()) return raw
            val existing = parse(raw)
            val merged = LinkedHashMap(existing.headers)
            headers.forEach { (name, value) -> if (value.isNotBlank()) merged.getOrPut(canonical(name)) { value } }
            if (merged.isEmpty()) return existing.url
            return existing.url + "|" + merged.entries.joinToString("&") { "${encode(it.key)}=${encode(it.value)}" }
        }

        private fun canonical(name: String): String = when (name.lowercase()) {
            "user-agent", "useragent", "http-user-agent" -> USER_AGENT
            "referer", "referrer", "http-referrer", "http-referer" -> REFERER
            else -> name
        }

        private fun decode(value: String): String = try {
            URLDecoder.decode(value, "UTF-8")
        } catch (e: IllegalArgumentException) {
            value
        }

        private fun encode(value: String): String = URLEncoder.encode(value, "UTF-8")
    }
}
