package com.mg.player.core.util

import androidx.core.net.toUri

object UrlUtils {

    /** "example.com:8080/" -> "http://example.com:8080" */
    fun normalizeBaseUrl(raw: String): String {
        var url = raw.trim().trimEnd('/')
        if (!url.startsWith("http://", ignoreCase = true) && !url.startsWith("https://", ignoreCase = true)) {
            url = "http://$url"
        }
        // Falls jemand die komplette player_api.php- oder get.php-URL eingibt.
        val cutAt = listOf("/player_api.php", "/get.php", "/xmltv.php")
            .map { url.indexOf(it, ignoreCase = true) }
            .filter { it > 0 }
            .minOrNull()
        return if (cutAt != null) url.substring(0, cutAt) else url
    }

    data class XtreamCredentials(val baseUrl: String, val username: String, val password: String)

    /**
     * Viele Anbieter verschicken ihre M3U als get.php-Link mit Zugangsdaten.
     * Daraus lässt sich ein vollwertiger Xtream-Zugang ableiten (mit Covern, Plots und EPG).
     */
    fun detectXtreamFromM3u(m3uUrl: String): XtreamCredentials? {
        if (!m3uUrl.contains("get.php", ignoreCase = true)) return null
        return try {
            val uri = m3uUrl.trim().toUri()
            val user = uri.getQueryParameter("username")
            val pass = uri.getQueryParameter("password")
            if (user.isNullOrBlank() || pass.isNullOrBlank()) return null
            XtreamCredentials(normalizeBaseUrl(m3uUrl), user, pass)
        } catch (e: Exception) {
            null
        }
    }

    fun extension(url: String): String {
        val path = url.substringBefore('|').substringBefore('?').substringBefore('#')
        val lastSegment = path.substringAfterLast('/')
        val dot = lastSegment.lastIndexOf('.')
        return if (dot >= 0) lastSegment.substring(dot + 1).lowercase() else ""
    }

    /**
     * Für Logs: nur Schema, Host und Port – nie Pfad oder Query, denn dort stehen bei Xtream
     * Benutzername und Passwort ("http://host:8080/live/user/pass/1.ts" -> "http://host:8080/…").
     */
    fun redact(url: String): String {
        val clean = url.substringBefore('|').substringBefore('?')
        val schemeEnd = clean.indexOf("://")
        if (schemeEnd < 0) return "…"
        val host = clean.substring(schemeEnd + 3).substringBefore('/').substringAfter('@')
        return clean.substring(0, schemeEnd + 3) + host + "/…"
    }
}
