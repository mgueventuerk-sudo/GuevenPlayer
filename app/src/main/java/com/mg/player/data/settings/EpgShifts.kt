package com.mg.player.data.settings

import com.mg.player.core.log.AppLog
import com.mg.player.data.db.AppDatabase
import com.mg.player.domain.model.EPG_SHIFT_MAX_HOURS
import com.mg.player.domain.repository.SettingsRepository

/**
 * EPG-Zeitverschiebung je Zugang (seit 3.32). Maßgeblich ist der Zugang, von dem das EPG stammt – bei „Alle Zugänge“
 * also der Original-Zugang des Senders. Solange der frühere gemeinsame Wert noch nicht übertragen ist, gilt er für alle.
 */
class EpgShifts(private val db: AppDatabase, private val settings: SettingsRepository) {

    /** Verschiebung in Millisekunden für das EPG des Zugangs [epgPlaylistId]. */
    suspend fun forPlaylist(epgPlaylistId: Long): Long {
        val legacy = settings.current().epgShiftHours
        val hours = if (legacy != 0) legacy else db.playlistDao().epgShiftHours(epgPlaylistId) ?: 0
        return hours * MS_PER_HOUR
    }

    /**
     * Einmalig nach dem Update: den gemeinsamen Wert auf alle Zugänge übertragen. Erst die Zugänge, dann der alte Wert
     * auf 0 – dazwischen gilt weiter der alte Wert, die Sendezeiten springen also nie.
     */
    suspend fun adoptLegacy() {
        val legacy = settings.current().epgShiftHours
        if (legacy == 0) return
        db.playlistDao().setEpgShiftHoursForAll(legacy.coerceIn(-EPG_SHIFT_MAX_HOURS, EPG_SHIFT_MAX_HOURS))
        settings.update { it.copy(epgShiftHours = 0) }
        AppLog.log("EPG", "Zeitverschiebung $legacy h gilt jetzt je Zugang (auf alle übertragen)")
    }

    private companion object {
        const val MS_PER_HOUR = 3_600_000L
    }
}
