package com.mg.player.data.recording

import com.mg.player.core.log.AppLog
import com.mg.player.domain.model.Recording
import com.mg.player.domain.model.RecordingStatus
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * Liste der Aufnahmen als kleine JSON-Datei. Bewusst ohne Datenbank-Migration:
 * so bleiben Zugänge, Favoriten und Fortschritt beim Update garantiert erhalten.
 */
class RecordingStore(private val file: File) {

    private val mutex = Mutex()
    private val _items = MutableStateFlow<List<Recording>>(emptyList())
    val items: StateFlow<List<Recording>> = _items.asStateFlow()

    suspend fun load() = withContext(Dispatchers.IO) {
        mutex.withLock { _items.value = read() }
    }

    suspend fun upsert(recording: Recording) = mutate { list -> list.filterNot { it.id == recording.id } + recording }

    suspend fun update(id: String, transform: (Recording) -> Recording) = mutate { list ->
        list.map { if (it.id == id) transform(it) else it }
    }

    suspend fun remove(id: String) = mutate { list -> list.filterNot { it.id == id } }

    private suspend fun mutate(block: (List<Recording>) -> List<Recording>) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val next = block(_items.value).sortedByDescending { it.startedAtMs }
            _items.value = next
            write(next)
        }
    }

    private fun read(): List<Recording> {
        if (!file.exists()) return emptyList()
        return runCatching {
            val array = JSONArray(file.readText())
            (0 until array.length()).mapNotNull { index -> array.optJSONObject(index)?.toRecording() }
        }.onFailure { AppLog.error("Aufnahme", "Aufnahmeliste unlesbar", it) }.getOrDefault(emptyList())
            .sortedByDescending { it.startedAtMs }
    }

    private fun write(list: List<Recording>) {
        val array = JSONArray()
        list.forEach { array.put(it.toJson()) }
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(array.toString())
        if (!tmp.renameTo(file)) {
            file.delete()
            tmp.renameTo(file)
        }
    }

    private fun Recording.toJson(): JSONObject = JSONObject()
        .put("id", id)
        .put("channelId", channelId)
        .put("channelName", channelName)
        .put("title", title)
        .put("startedAt", startedAtMs)
        .put("plannedEnd", plannedEndMs)
        .put("endedAt", endedAtMs)
        .put("file", filePath)
        .put("status", status.name)
        .put("bytes", bytes)
        .put("error", error ?: "")
        .put("programStart", programStartMs)

    private fun JSONObject.toRecording(): Recording? {
        val id = optString("id").ifEmpty { return null }
        return Recording(
            id = id,
            channelId = optString("channelId"),
            channelName = optString("channelName"),
            title = optString("title"),
            startedAtMs = optLong("startedAt"),
            plannedEndMs = optLong("plannedEnd"),
            endedAtMs = optLong("endedAt"),
            filePath = optString("file"),
            status = RecordingStatus.entries.firstOrNull { it.name == optString("status") } ?: RecordingStatus.STOPPED,
            bytes = optLong("bytes"),
            error = optString("error").ifEmpty { null },
            programStartMs = optLong("programStart"),
        )
    }
}
