package com.mg.player.data.update

import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import androidx.core.net.toUri
import com.mg.player.BuildConfig
import com.mg.player.core.log.AppLog
import com.mg.player.core.network.HttpFetcher
import com.mg.player.core.network.HttpStatusException
import com.mg.player.core.util.runCatchingCancellable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.TimeUnit

/**
 * Eine neuere Version im privaten Repo. [assetUrl]: API-Adresse der APK (nur mit Schlüssel abrufbar).
 * [notes]: bereinigte Stichpunkte aus den GitHub-Release-Notizen (null = keine vorhanden oder nichts übrig).
 */
data class AppUpdate(val versionCode: Int, val versionName: String, val assetUrl: String, val sizeBytes: Long, val notes: String? = null)

/** Was der Updater gerade weiß bzw. tut – für Einstellungen und Hinweis beim Start. */
sealed interface UpdateStatus {
    data object Idle : UpdateStatus
    data object Checking : UpdateStatus
    data object UpToDate : UpdateStatus
    data class Available(val update: AppUpdate) : UpdateStatus
    data class Downloading(val update: AppUpdate, val percent: Int) : UpdateStatus
    data class ReadyToInstall(val update: AppUpdate) : UpdateStatus
    /** [retry]: schon geladenes Update, das nur noch installiert werden muss (z. B. nach der Installations-Erlaubnis). */
    data class Failed(val message: String, val retry: AppUpdate? = null) : UpdateStatus
}

/**
 * Updates aus dem privaten GitHub-Repo (ab 3.51, Weg 2): Jeder Build mit Release-Schlüssel legt dort ein Release
 * „build-<versionCode>“ mit der APK ab. Die App fragt mit dem Zugriffsschlüssel aus den Einstellungen nach dem neuesten
 * Release, lädt die APK in den eigenen Cache und öffnet den Android-Installer (bestätigen muss man immer selbst).
 *
 * Sicherheit: Der Schlüssel geht nur an api.github.com (Kopfzeile, nie in die Adresse) – beim Weiterleiten auf den
 * Download-Server entfernt OkHttp ihn. Android installiert das Update nur, wenn es mit demselben Schlüssel signiert ist
 * wie die laufende App; eine untergeschobene APK wird abgelehnt.
 */
class AppUpdater(
    private val context: Context,
    http: HttpFetcher,
    /** App-weiter Scope für den Download - nicht der eines Screens, sonst stirbt er beim Verlassen der Komposition. */
    private val scope: CoroutineScope,
    private val clock: () -> Long = System::currentTimeMillis,
) {

    // Keine Weiterleitung von HTTPS auf HTTP; großzügige Zeit für den Download, aber nicht endlos.
    private val client = http.client.newBuilder().followSslRedirects(false).readTimeout(READ_TIMEOUT_S, TimeUnit.SECONDS).build()

    private val _status = MutableStateFlow<UpdateStatus>(UpdateStatus.Idle)
    val status: StateFlow<UpdateStatus> = _status.asStateFlow()

    @Volatile
    private var lastCheckMs = 0L

    /** „Später“ beim Start-Hinweis: für diese Version nicht erneut fragen (gilt bis zum App-Neustart). */
    @Volatile
    var dismissedVersion: Int? = null

    /** „Ausblenden“ bei der Fortschritts-Karte: für dieses Update nicht mehr zeigen, bis der Download neu startet. */
    @Volatile
    var hiddenProgressFor: Int? = null

    /** Ein Download zur Zeit (Start-Hinweis und Einstellungen könnten sonst dieselbe Datei gleichzeitig schreiben). */
    private val downloadLock = Mutex()

    /** Schon vollständig geladene APK – erneutes Öffnen des Installers lädt nicht noch einmal 50 MB. */
    @Volatile
    private var downloaded: Pair<Int, File>? = null

    /** Beim Start: höchstens alle [AUTO_CHECK_MS] nachsehen, nur mit Schlüssel. */
    suspend fun checkIfDue(token: String) {
        if (token.isBlank() || clock() - lastCheckMs < AUTO_CHECK_MS) return
        check(token)
    }

    suspend fun check(token: String): UpdateStatus = withContext(Dispatchers.IO) {
        if (token.isBlank()) {
            return@withContext UpdateStatus.Failed("Zuerst den GitHub-Zugriffsschlüssel eintragen").also { _status.value = it }
        }
        _status.value = UpdateStatus.Checking
        lastCheckMs = clock()
        val result = try {
            runCatchingCancellable { latest(token) }.fold(
                onSuccess = { update -> update?.takeIf { it.versionCode > BuildConfig.VERSION_CODE }?.let(UpdateStatus::Available) ?: UpdateStatus.UpToDate },
                onFailure = { e -> UpdateStatus.Failed(problem(e)) },
            )
        } catch (e: CancellationException) {
            // Abgebrochen (Bildschirm verlassen, App im Hintergrund): „Prüfe …“ bliebe sonst für immer stehen.
            _status.value = UpdateStatus.Idle
            throw e
        }
        _status.value = result
        result
    }

    /**
     * Startet den Download im eigenen, app-weiten Scope statt im Scope des Aufrufers: AppRoot nimmt den Start-Hinweis
     * aus der Komposition, sobald die Wiedergabe beginnt (nicht mitten im Film fragen) - ein dort gestarteter Download
     * lief bisher mit und riss sofort ab, sobald man weiterschaute. [hiddenProgressFor] wird zurückgesetzt, damit ein
     * neuer Versuch die Fortschritts-Karte wieder zeigt, auch wenn ein vorheriger Durchlauf ausgeblendet wurde.
     */
    fun startDownload(token: String, update: AppUpdate) {
        hiddenProgressFor = null
        scope.launch { downloadAndInstall(token, update) }
    }

    /** APK laden (mit Fortschritt) und den Installer öffnen. */
    suspend fun downloadAndInstall(token: String, update: AppUpdate) {
        if (!downloadLock.tryLock()) return
        try {
            val file = cachedFile(update) ?: withContext(Dispatchers.IO) {
                runCatchingCancellable { download(token, update) }.getOrElse { e ->
                    AppLog.log("Update", "Download fehlgeschlagen: ${e.javaClass.simpleName}")
                    _status.value = UpdateStatus.Failed("Download fehlgeschlagen (${problem(e)})")
                    null
                }
            } ?: return
            downloaded = update.versionCode to file
            _status.value = UpdateStatus.ReadyToInstall(update)
            install(file, update)
        } finally {
            downloadLock.unlock()
        }
    }

    private fun cachedFile(update: AppUpdate): File? = downloaded
        ?.takeIf { (code, file) -> code == update.versionCode && file.exists() && (update.sizeBytes <= 0 || file.length() == update.sizeBytes) }
        ?.second

    /** Neuestes Release (ohne Entwürfe/Vorabversionen); null, wenn keins eine passende APK hat. */
    private fun latest(token: String): AppUpdate? {
        val request = Request.Builder().url("$API/repos/$REPO/releases/latest").apply { authorize(token) }
            .header("Accept", "application/vnd.github+json").build()
        val json = client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw HttpStatusException(response.code, null)
            JSONObject(response.body.string())
        }
        return parseRelease(json)
    }

    private fun download(token: String, update: AppUpdate): File {
        val dir = File(context.cacheDir, UPDATE_DIR).apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }
        val target = File(dir, "Player-${update.versionCode}.apk")
        val request = Request.Builder().url(update.assetUrl).apply { authorize(token) }.header("Accept", "application/octet-stream").build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw HttpStatusException(response.code, null)
            val total = update.sizeBytes.takeIf { it > 0 } ?: response.body.contentLength()
            val done = response.body.byteStream().use { input -> target.outputStream().use { copy(input, it, total, update) } }
            // Abgeschnittene Datei nie dem Installer geben.
            if (total > 0 && done != total) throw IOException("unvollständig ($done von $total Bytes)")
        }
        return target
    }

    /** Kopiert und meldet den Fortschritt in ganzen Prozent; liefert die Anzahl Bytes. */
    private fun copy(input: InputStream, output: OutputStream, total: Long, update: AppUpdate): Long {
        val buffer = ByteArray(BUFFER)
        var done = 0L
        var lastPercent = -1
        var n = input.read(buffer)
        while (n >= 0) {
            output.write(buffer, 0, n)
            done += n
            val percent = if (total > 0) (done * PERCENT / total).toInt() else 0
            if (percent != lastPercent) {
                lastPercent = percent
                _status.value = UpdateStatus.Downloading(update, percent)
            }
            n = input.read(buffer)
        }
        return done
    }

    private fun install(file: File, update: AppUpdate) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !context.packageManager.canRequestPackageInstalls()) {
            // Einmalig erlauben: „Unbekannte Apps installieren“ für GuevenPlayer.
            _status.value = UpdateStatus.Failed("Bitte „Unbekannte Apps installieren“ für GuevenPlayer erlauben und erneut tippen", update)
            val settings = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, "package:${context.packageName}".toUri())
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            runCatching { context.startActivity(settings) }
            return
        }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.updates", file)
        val intent = Intent(Intent.ACTION_VIEW).setDataAndType(uri, APK_TYPE)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(intent) }.onFailure {
            _status.value = UpdateStatus.Failed("Installer ließ sich nicht öffnen", update)
        }
    }

    private fun Request.Builder.authorize(token: String) {
        header("Authorization", "Bearer ${token.trim()}")
        header("X-GitHub-Api-Version", "2022-11-28")
    }

    companion object {
        private const val API = "https://api.github.com"
        private const val REPO = "mgueventuerk-sudo/Player"
        private const val UPDATE_DIR = "updates"
        private const val APK_TYPE = "application/vnd.android.package-archive"
        private const val BUFFER = 64 * 1024
        private const val PERCENT = 100
        private const val READ_TIMEOUT_S = 60L
        private const val AUTO_CHECK_MS = 6 * 3_600_000L
        private val TAG = Regex("^build-(\\d+)$")

        /** Release „build-<versionCode>“ mit einer Release-APK (nicht „-debug“); sonst null. */
        internal fun parseRelease(json: JSONObject): AppUpdate? {
            val code = TAG.find(json.optString("tag_name"))?.groupValues?.get(1)?.toIntOrNull()
            val assets = json.optJSONArray("assets")
            val apk = assets?.let { list -> (0 until list.length()).mapNotNull(list::optJSONObject) }
                ?.firstOrNull { it.optString("name").endsWith(".apk") && !it.optString("name").contains("debug") }
            // Nur API-Adressen von GitHub (dorthin geht der Schlüssel) – nie eine fremde Adresse aus der Antwort.
            val url = apk?.optString("url")?.takeIf { it.startsWith("$API/") }
            if (code == null || apk == null || url == null) return null
            val name = json.optString("name").removePrefix("Player ").ifBlank { "Build $code" }
            return AppUpdate(code, name, url, apk.optLong("size"), cleanChangelog(json.optString("body")))
        }

        /**
         * GitHubs automatisch erzeugte Release-Notizen ("## What's Changed", "* Titel by @user in #123",
         * "**Full Changelog**: …") auf reine Stichpunkte ohne Markdown/Benutzernamen/Links reduziert.
         * Höchstens [MAX_NOTES_LINES] Zeilen – mehr passt nicht lesbar in den Hinweis-Dialog.
         */
        /** Lesbare Notizen für den Update-Hinweis, siehe [ReleaseNotes]. */
        internal fun cleanChangelog(raw: String): String? = ReleaseNotes.clean(raw)

        internal fun problem(e: Throwable): String = when (val code = (e as? HttpStatusException)?.code) {
            401 -> "Schlüssel ungültig oder abgelaufen (HTTP 401)"
            403 -> "Schlüssel ohne Leserecht für das Repo (HTTP 403)"
            404 -> "Kein Release gefunden oder Schlüssel ohne Zugriff aufs Repo (HTTP 404)"
            null -> "keine Verbindung"
            else -> "GitHub antwortet mit HTTP $code"
        }
    }
}
