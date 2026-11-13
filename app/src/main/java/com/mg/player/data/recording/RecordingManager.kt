package com.mg.player.data.recording

import android.content.Context
import android.os.Build
import android.os.Environment
import android.os.storage.StorageManager
import com.mg.player.core.log.AppLog
import com.mg.player.data.remote.StreamUrlResolver
import com.mg.player.data.sync.syncErrorText
import com.mg.player.domain.model.Channel
import com.mg.player.domain.model.EpgProgram
import com.mg.player.domain.model.Recording
import com.mg.player.domain.model.RecordingStatus
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Startet, plant, stoppt und verwaltet Aufnahmen. Laufende Aufnahmen leben im App-Scope weiter, während man
 * umschaltet oder die App verlässt; ein Vordergrund-Dienst hält die App dabei am Leben.
 */
class RecordingManager(
    private val context: Context,
    private val scope: CoroutineScope,
    private val recorder: StreamRecorder,
    private val urls: StreamUrlResolver,
    private val channelLookup: suspend (String) -> Channel?,
) {
    private val store = RecordingStore(File(context.filesDir, "recordings.json"))
    private val alarms = RecordingAlarms(context)
    private val sessions = ConcurrentHashMap<String, RecordingSession>()
    private val startLock = Mutex()

    val recordings: StateFlow<List<Recording>> = store.items

    /** Aktuelle Dateigröße laufender Aufnahmen (wird nur im Speicher geführt, nicht ständig gesichert). */
    private val _liveBytes = MutableStateFlow<Map<String, Long>>(emptyMap())
    val liveBytes: StateFlow<Map<String, Long>> = _liveBytes.asStateFlow()

    /** IDs der Sender, die gerade aufgenommen werden. */
    private val _activeChannels = MutableStateFlow<Set<String>>(emptySet())
    val activeChannels: StateFlow<Set<String>> = _activeChannels.asStateFlow()

    private val ready: Job = scope.launch {
        store.load()
        recover()
    }

    /** Wartet, bis die Aufnahmeliste geladen und geplante Aufnahmen wieder eingeplant sind. */
    suspend fun awaitReady() = ready.join()

    /**
     * Übernimmt geplante Aufnahmen aus einer Sicherung (nur [RecordingStatus.SCHEDULED] mit noch nicht
     * verstrichenem Ende - Laufendes/Fertiges verweist auf eine Datei, die auf einem neuen Gerät nicht existiert).
     */
    suspend fun restoreScheduled(recordings: List<Recording>) {
        awaitReady()
        val now = System.currentTimeMillis()
        recordings.filter { it.isScheduled && it.plannedEndMs > now }.forEach { recording ->
            store.upsert(recording)
            alarms.set(recording.id, recording.startedAtMs)
        }
    }

    /** Nach dem App-Start: Unterbrochenes markieren, Verpasstes melden, Geplantes neu einplanen. */
    private suspend fun recover() {
        val now = System.currentTimeMillis()
        store.items.value.forEach { item ->
            when {
                item.isActive && !sessions.containsKey(item.id) -> {
                    val file = File(item.filePath)
                    store.update(item.id) {
                        it.copy(status = RecordingStatus.STOPPED, endedAtMs = file.lastModified(), bytes = file.length(), error = "Unterbrochen")
                    }
                }

                item.isScheduled && item.plannedEndMs <= now ->
                    store.update(item.id) { it.copy(status = RecordingStatus.FAILED, error = "Verpasst – Gerät war aus") }

                item.isScheduled && item.startedAtMs <= now -> startLock.withLock { startScheduledLocked(item.id) }

                item.isScheduled -> alarms.set(item.id, item.startedAtMs)
            }
        }
    }

    fun directory(): File {
        val base = context.getExternalFilesDir(Environment.DIRECTORY_MOVIES) ?: context.filesDir
        return File(base, "Aufnahmen").apply { mkdirs() }
    }

    /**
     * Freier Platz für Aufnahmen. Ab Android 8 zählt das System auch Cache mit, den es bei Bedarf selbst
     * freiräumen kann – sonst meldet ein fast volles Gerät "kein Platz", obwohl es genug hätte.
     */
    fun freeBytes(): Long {
        val dir = directory()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            runCatching {
                val storage = context.getSystemService(StorageManager::class.java)
                if (storage != null) return storage.getAllocatableBytes(storage.getUuidForPath(dir))
            }
        }
        return dir.usableSpace
    }

    /** Sorgt dafür, dass [bytes] wirklich frei sind – räumt notfalls Cache anderer Apps (ab Android 8). */
    private fun ensureSpace(bytes: Long): Boolean {
        val dir = directory()
        if (dir.usableSpace >= bytes) return true
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return false
        return runCatching {
            val storage = context.getSystemService(StorageManager::class.java) ?: return false
            storage.allocateBytes(storage.getUuidForPath(dir), bytes)
            true
        }.getOrDefault(false)
    }

    fun isRecording(channelId: String): Boolean = channelId in _activeChannels.value

    /** Startet sofort eine Aufnahme bis [endMs]. Liefert eine Fehlermeldung oder null bei Erfolg. */
    suspend fun start(channel: Channel, title: String, endMs: Long, programStartMs: Long = 0): String? {
        awaitReady()
        return startLock.withLock {
            if (isRecording(channel.id)) return@withLock "Dieser Sender wird bereits aufgenommen"
            val now = System.currentTimeMillis()
            val recording = Recording(
                id = now.toString(36) + channel.id.hashCode().toUInt().toString(36),
                channelId = channel.id,
                channelName = channel.name,
                title = title.ifBlank { channel.name },
                startedAtMs = now,
                plannedEndMs = endMs,
                endedAtMs = 0,
                filePath = "",
                status = RecordingStatus.RECORDING,
                bytes = 0,
                programStartMs = programStartMs,
            )
            begin(recording, channel, fromSchedule = false)
        }
    }

    /**
     * Plant die Aufnahme einer kommenden Sendung (1 Minute Vorlauf, 2 Minuten Nachlauf).
     * Liefert eine Fehlermeldung oder null bei Erfolg.
     */
    suspend fun schedule(channel: Channel, program: EpgProgram): String? {
        awaitReady()
        val key = Recording.programKey(channel.id, program.startMs)
        if (store.items.value.any { it.programKey == key && (it.isScheduled || it.isActive) }) return "Diese Sendung ist schon eingeplant"
        val start = program.startMs - PRE_ROLL_MS
        val recording = Recording(
            id = "plan" + program.startMs.toString(36) + channel.id.hashCode().toUInt().toString(36),
            channelId = channel.id,
            channelName = channel.name,
            title = program.title.ifBlank { channel.name },
            startedAtMs = start,
            plannedEndMs = program.endMs + POST_ROLL_MS,
            endedAtMs = 0,
            filePath = "",
            status = RecordingStatus.SCHEDULED,
            bytes = 0,
            programStartMs = program.startMs,
        )
        store.upsert(recording)
        alarms.set(recording.id, start)
        AppLog.log("Aufnahme", "Geplant: ${channel.name} – ${recording.title}")
        return null
    }

    /** Vom Wecker (oder nach dem App-Start) aufgerufen: aus der geplanten wird eine laufende Aufnahme. */
    suspend fun startScheduled(id: String) {
        awaitReady()
        startLock.withLock { startScheduledLocked(id) }
    }

    /** Nur mit gehaltenem [startLock] aufrufen. */
    private suspend fun startScheduledLocked(id: String) {
        val planned = store.items.value.firstOrNull { it.id == id && it.isScheduled } ?: return
        val now = System.currentTimeMillis()
        val channel = channelLookup(planned.channelId)
        val problem = when {
            planned.plannedEndMs <= now -> "Verpasst"
            channel == null -> "Sender nicht mehr in der Playlist"
            isRecording(planned.channelId) -> "Sender wurde bereits aufgenommen"
            else -> null
        }
        if (problem != null || channel == null) {
            store.update(id) { it.copy(status = RecordingStatus.FAILED, error = problem) }
            return
        }
        begin(planned.copy(startedAtMs = now, status = RecordingStatus.RECORDING), channel, fromSchedule = true)
    }

    /** Legt die Datei an und startet den eigentlichen Mitschnitt im App-Scope. */
    private suspend fun begin(template: Recording, channel: Channel, fromSchedule: Boolean): String? {
        if (!withContext(Dispatchers.IO) { ensureSpace(MIN_FREE_BYTES) }) {
            val message = "Zu wenig freier Speicher für eine Aufnahme"
            if (fromSchedule) store.update(template.id) { it.copy(status = RecordingStatus.FAILED, error = message) }
            return message
        }
        val file = withContext(Dispatchers.IO) { File(directory(), fileName(channel.name, template.title, template.startedAtMs)) }
        val recording = template.copy(filePath = file.absolutePath, status = RecordingStatus.RECORDING)
        store.upsert(recording)
        val session = RecordingSession(recording.id, channel.id, allowInsecureHttp = urls.isHttpAllowed(channel.id))
        sessions[recording.id] = session
        _activeChannels.update { it + channel.id }
        if (!RecordingService.start(context, "${channel.name}: ${recording.title}")) {
            // Ohne den Vordergrund-Dienst kann Android die App jederzeit beenden - weiterlaufen lassen wäre ein
            // Schein-Erfolg, den der Nutzer erst beim Scheitern der Aufnahme bemerkt (Fund: stiller Recording-Fehler).
            val message = "Aufnahme ohne Android-Schutz vor dem Beenden – bitte Akku-Optimierung für die App ausschalten"
            sessions.remove(recording.id)
            _activeChannels.update { it - channel.id }
            store.update(recording.id) { it.copy(status = RecordingStatus.FAILED, error = message) }
            return message
        }
        AppLog.log("Aufnahme", "Start: ${channel.name} – ${recording.title}")
        scope.launch { run(recording, channel, session) }
        return null
    }

    private suspend fun run(recording: Recording, channel: Channel, session: RecordingSession) {
        var error: String? = null
        var bytes = 0L
        try {
            val url = urls.resolve(channel.streamUrl, channel.id)
            bytes = recorder.record(
                url = url,
                file = File(recording.filePath),
                untilMs = recording.plannedEndMs,
                session = session,
                // Während der Aufnahme nur den tatsächlich freien Platz prüfen (schnell, kein Systemaufruf).
                hasSpace = { directory().usableSpace > STOP_FREE_BYTES },
                onBytes = { total -> _liveBytes.update { it + (recording.id to total) } },
            )
        } catch (e: CancellationException) {
            // App wird beendet: Beim nächsten Start wird die Aufnahme als "Unterbrochen" markiert.
            throw e
        } catch (e: Exception) {
            AppLog.error("Aufnahme", "Aufnahme fehlgeschlagen", e)
            // syncErrorText() übersetzt bekannte Netzwerkfehler (Zeitüberschreitung, Server nicht erreichbar, …)
            // ins Deutsche; für alles andere bleibt derselbe Rückfall wie vorher (e.message bzw. "Unbekannter Fehler").
            error = syncErrorText(e)
        }
        val size = withContext(Dispatchers.IO) { File(recording.filePath).length() }
        val status = when {
            error != null && size == 0L -> RecordingStatus.FAILED
            session.stopped || error != null -> RecordingStatus.STOPPED
            else -> RecordingStatus.DONE
        }
        store.update(recording.id) {
            it.copy(status = status, endedAtMs = System.currentTimeMillis(), bytes = maxOf(size, bytes), error = error ?: session.stopReason)
        }
        sessions.remove(recording.id)
        _liveBytes.update { it - recording.id }
        _activeChannels.update { it - channel.id }
        if (sessions.isEmpty()) RecordingService.stop(context)
        AppLog.log("Aufnahme", "Ende (${status.label}): ${channel.name} – ${recording.title}")
    }

    fun stop(id: String) {
        sessions[id]?.stop()
    }

    fun stopChannel(channelId: String) {
        sessions.values.filter { it.channelId == channelId }.forEach { it.stop() }
    }

    /** Löscht eine Aufnahme samt Datei bzw. streicht eine geplante Aufnahme. */
    suspend fun delete(id: String) {
        sessions[id]?.stop()
        alarms.cancel(id)
        val recording = store.items.value.firstOrNull { it.id == id } ?: return
        if (recording.filePath.isNotEmpty()) withContext(Dispatchers.IO) { File(recording.filePath).delete() }
        store.remove(id)
    }

    suspend fun recording(id: String): Recording? {
        awaitReady()
        return store.items.value.firstOrNull { it.id == id }
    }

    private fun fileName(channel: String, title: String, timeMs: Long): String {
        val stamp = SimpleDateFormat("yyyy-MM-dd_HHmm", Locale.GERMANY).format(Date(timeMs))
        val base = "${channel}_$title".replace(Regex("[^\\p{L}\\p{N}._-]+"), "_").trim('_').take(80)
        return "${stamp}_$base.ts"
    }

    private companion object {
        const val MIN_FREE_BYTES = 500L * 1024 * 1024
        const val STOP_FREE_BYTES = 200L * 1024 * 1024
        const val PRE_ROLL_MS = 60_000L
        const val POST_ROLL_MS = 2 * 60_000L
    }
}
