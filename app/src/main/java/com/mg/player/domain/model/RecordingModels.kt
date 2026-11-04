package com.mg.player.domain.model

import androidx.compose.runtime.Immutable

enum class RecordingStatus(val label: String) {
    SCHEDULED("Geplant"),
    RECORDING("Aufnahme läuft"),
    DONE("Fertig"),
    STOPPED("Gestoppt"),
    FAILED("Fehlgeschlagen"),
}

/**
 * Eine Aufnahme eines Live-Senders als Datei auf dem Gerät. Geplante Aufnahmen ([RecordingStatus.SCHEDULED])
 * haben noch keine Datei; [startedAtMs] ist bei ihnen der geplante Beginn.
 */
@Immutable
data class Recording(
    val id: String,
    val channelId: String,
    val channelName: String,
    val title: String,
    val startedAtMs: Long,
    /** Geplantes Ende; bei "bis ich stoppe" eine Sicherheitsgrenze. */
    val plannedEndMs: Long,
    val endedAtMs: Long,
    val filePath: String,
    val status: RecordingStatus,
    val bytes: Long,
    val error: String? = null,
    /** Beginn der EPG-Sendung, zu der die Aufnahme gehört (0 = frei gewählte Dauer). */
    val programStartMs: Long = 0,
) {
    val isActive: Boolean get() = status == RecordingStatus.RECORDING
    val isScheduled: Boolean get() = status == RecordingStatus.SCHEDULED

    /** Schlüssel, über den der TV-Guide eine Sendung als "wird aufgenommen" erkennt. */
    val programKey: String? get() = if (programStartMs > 0) programKey(channelId, programStartMs) else null

    companion object {
        fun programKey(channelId: String, programStartMs: Long) = "$channelId@$programStartMs"
    }

    fun durationMs(nowMs: Long = System.currentTimeMillis()): Long = when {
        isActive -> nowMs - startedAtMs
        isScheduled -> plannedEndMs - startedAtMs
        else -> endedAtMs - startedAtMs
    }.coerceAtLeast(0)
}
