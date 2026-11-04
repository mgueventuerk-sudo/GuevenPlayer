package com.mg.player.data.qr

import com.mg.player.core.net.MiniHttpServer
import com.mg.player.core.security.ShareKey
import com.mg.player.domain.model.PlaylistType
import com.mg.player.domain.repository.PlaylistInput
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow

/**
 * Einrichtung per Smartphone: Das Handy öffnet nach dem Scannen ein Formular
 * und schickt die Zugangsdaten an den Fernseher.
 */
class QrSetupServer(private val key: ShareKey = ShareKey()) {

    private val _submissions = MutableSharedFlow<PlaylistInput>(extraBufferCapacity = 1)
    val submissions: SharedFlow<PlaylistInput> = _submissions

    private val server = MiniHttpServer { request -> handle(request) }

    /** URL für den QR-Code oder null ohne WLAN/LAN. */
    fun start(): String? = server.start()?.let { "$it/?t=${server.token}#k=${key.fragment}" }

    fun stop() = server.stop()

    private suspend fun handle(request: MiniHttpServer.Request): MiniHttpServer.Response = when {
        request.method == "GET" && request.path == "/" -> MiniHttpServer.Response.html(200, SetupPage.form(request.param("t").orEmpty()))
        request.method == "POST" && request.path == "/submit" -> submit(request)
        else -> MiniHttpServer.Response.html(404, SetupPage.message("Nicht gefunden", "Diese Seite gibt es nicht."))
    }

    private suspend fun submit(request: MiniHttpServer.Request): MiniHttpServer.Response {
        // Zugangsdaten kommen im Browser verschlüsselt (ChaCha20-Poly1305), nie im Klartext übers WLAN.
        val (text, _) = key.open(request.body)
            ?: return MiniHttpServer.Response.html(400, SetupPage.message("Nicht lesbar", "Bitte scanne den QR-Code am Fernseher erneut."))
        val form = parseForm(text)
        if (!server.isAuthorized(form["t"])) {
            return MiniHttpServer.Response.html(403, SetupPage.message("Abgelehnt", "Bitte scanne den QR-Code am Fernseher erneut."))
        }
        val type = if (form["type"] == "m3u") PlaylistType.M3U else PlaylistType.XTREAM
        val input = PlaylistInput(
            name = form["name"].orEmpty().trim(),
            type = type,
            serverUrl = form["server"].orEmpty().trim(),
            username = form["user"].orEmpty().trim(),
            password = form["pass"].orEmpty().trim(),
            m3uUrl = form["m3u"].orEmpty().trim(),
            epgUrl = form["epg"].orEmpty().trim(),
        )
        val valid = when (type) {
            PlaylistType.XTREAM -> input.serverUrl.isNotBlank() && input.username.isNotBlank() && input.password.isNotBlank()
            PlaylistType.M3U -> input.m3uUrl.isNotBlank()
        }
        if (!valid) {
            return MiniHttpServer.Response.html(400, SetupPage.message("Unvollständig", "Bitte fülle alle Pflichtfelder aus und sende erneut."))
        }
        _submissions.emit(input)
        return MiniHttpServer.Response.html(
            200,
            SetupPage.message("Gesendet ✓", "Die Daten sind auf dem Fernseher angekommen. Du kannst diese Seite schließen."),
        )
    }

    companion object {
        /** application/x-www-form-urlencoded -> Map */
        fun parseForm(body: String): Map<String, String> = body.split('&')
            .filter { it.isNotEmpty() }
            .associate { pair ->
                val key = pair.substringBefore('=')
                val value = pair.substringAfter('=', "")
                decode(key) to decode(value)
            }

        private fun decode(value: String): String = try {
            java.net.URLDecoder.decode(value, "UTF-8")
        } catch (e: IllegalArgumentException) {
            value
        }
    }
}
