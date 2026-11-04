package com.mg.player.data.share

import com.mg.player.core.net.MiniHttpServer
import org.json.JSONObject

/**
 * Stellt Dateien fürs Handy bereit bzw. nimmt sie entgegen:
 * Sicherung herunterladen, Sicherung hochladen, Fehlerprotokoll ansehen.
 *
 * Das Kennwort der Sicherung wird ausschließlich am Fernseher eingegeben. Die Verbindung zum Handy ist
 * unverschlüsseltes HTTP im Heimnetz – früher gingen Kennwort und Sicherung zusammen darüber, wer mitlas,
 * hatte damit alle Zugangsdaten. Jetzt läuft nur noch die verschlüsselte Datei übers Netz.
 */
class DeviceShareServer(
    /** Verschlüsselte Sicherung – null, solange am Fernseher noch kein Kennwort festgelegt wurde. */
    private val exportBackup: suspend () -> String?,
    /** Hochgeladene Sicherungsdatei -> Rückmeldung fürs Handy; null = /restore ist zu (nur „Wiederherstellen“ öffnet ihn). */
    private val receiveBackup: (suspend (String) -> Reply)? = null,
    private val readLog: () -> String,
    /** VPN-Konfiguration (im Browser verschlüsselt) -> Rückmeldung fürs Handy. */
    private val receiveVpn: suspend (String) -> Reply = { Reply(false, "Nicht verfügbar", "Bitte am Fernseher „VPN einrichten“ öffnen.") },
    /** Fehlerprotokoll nur in „Protokoll teilen“ – das Token der anderen Seiten geht im Klartext durchs WLAN. */
    private val allowLog: Boolean = true,
) {
    /** Antwort an das Handy nach dem Hochladen. */
    data class Reply(val ok: Boolean, val title: String, val text: String)

    private val server = MiniHttpServer(maxBodyBytes = 16 * 1024 * 1024) { request -> handle(request) }

    /** Basis-Adresse, z. B. "http://192.168.1.20:8765" – Pfade: /backup, /restore, /vpn, /log (immer mit Token). */
    fun start(): String? = server.start()

    fun urlFor(base: String, path: String) = "$base$path?t=${server.token}"

    fun stop() = server.stop()

    private suspend fun handle(request: MiniHttpServer.Request): MiniHttpServer.Response {
        if (!server.isAuthorized(request.param("t"))) {
            return MiniHttpServer.Response.html(403, SharePages.message("Abgelehnt", "Bitte scanne den QR-Code am Fernseher erneut."))
        }
        return when {
            request.path == "/restore" && receiveBackup == null -> restoreClosed()

            request.method == "GET" && request.path == "/backup" -> MiniHttpServer.Response.html(200, SharePages.backupPage(server.token))

            request.method == "GET" && request.path == "/backup/file" -> backupFile()

            request.method == "GET" && request.path == "/restore" -> MiniHttpServer.Response.html(200, SharePages.restoreForm(server.token))

            request.method == "POST" && request.path == "/restore" -> {
                val reply = receiveBackup?.invoke(backupFrom(request.body)) ?: return restoreClosed()
                MiniHttpServer.Response.html(if (reply.ok) 200 else 400, SharePages.message(reply.title, reply.text))
            }

            request.method == "GET" && request.path == "/vpn" -> MiniHttpServer.Response.html(200, SharePages.vpnForm(server.token))

            request.method == "POST" && request.path == "/vpn" -> {
                val reply = receiveVpn(request.body)
                MiniHttpServer.Response.html(if (reply.ok) 200 else 400, SharePages.message(reply.title, reply.text))
            }

            request.method == "GET" && request.path == "/log" && allowLog -> MiniHttpServer.Response(
                code = 200,
                body = readLog().ifBlank { "Das Protokoll ist leer." }.toByteArray(Charsets.UTF_8),
                contentType = "text/plain; charset=utf-8",
            )

            else -> MiniHttpServer.Response.html(404, SharePages.message("Nicht gefunden", "Diese Seite gibt es nicht."))
        }
    }

    private fun restoreClosed() =
        MiniHttpServer.Response.html(404, SharePages.message("Nicht verfügbar", "Bitte am Fernseher „Sicherung wiederherstellen“ öffnen."))

    private suspend fun backupFile(): MiniHttpServer.Response {
        val backup = exportBackup() ?: return MiniHttpServer.Response.html(
            409,
            SharePages.message("Noch kein Kennwort", "Bitte zuerst am Fernseher ein Kennwort festlegen und dann erneut tippen."),
        )
        return MiniHttpServer.Response(
            code = 200,
            body = backup.toByteArray(Charsets.UTF_8),
            contentType = "application/json; charset=utf-8",
            headers = mapOf("Content-Disposition" to "attachment; filename=\"guevenplayer-sicherung.json\""),
        )
    }

    /**
     * Die Upload-Seite schickt {"backup": "<Dateiinhalt>"}; ältere Seiten schickten die Datei direkt.
     * Ein mitgeschicktes Kennwort älterer Seiten wird bewusst ignoriert – es wird am Fernseher abgefragt.
     */
    private fun backupFrom(body: String): String = try {
        val root = JSONObject(body)
        if (root.has("backup")) root.getString("backup") else body
    } catch (ignored: org.json.JSONException) {
        // Ältere Upload-Seite: die Datei selbst wurde geschickt.
        body
    }
}
