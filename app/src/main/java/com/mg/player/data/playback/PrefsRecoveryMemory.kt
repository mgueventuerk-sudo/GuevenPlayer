package com.mg.player.data.playback

import android.content.SharedPreferences
import androidx.core.content.edit
import com.mg.player.domain.model.PlayerEngineType
import com.mg.player.player.supervisor.RecoveryMemory
import com.mg.player.player.supervisor.StartHint
import com.mg.player.player.supervisor.Symptom

/**
 * Gedächtnis des Wiedergabe-Wächters in SharedPreferences: je Inhalt, was zuletzt geholfen hat (Engine, sicherer
 * Decoder, Ton selbst dekodieren), plus Zähler je Symptom für die lokale Auswertung. Einträge verfallen nach
 * [MAX_AGE_MS]; höchstens [MAX_ENTRIES] Inhalte (die ältesten fliegen zuerst). Enthält keine Adressen oder Zugangsdaten.
 */
class PrefsRecoveryMemory(private val prefs: SharedPreferences, private val clock: () -> Long = System::currentTimeMillis) : RecoveryMemory {

    override fun hintFor(contentKey: String): StartHint? {
        val raw = prefs.getString(HINT + contentKey, null) ?: return null
        val parts = raw.split('|')
        val at = parts.getOrNull(TIME_FIELD)?.toLongOrNull() ?: return null
        if (clock() - at > MAX_AGE_MS) {
            forget(contentKey)
            return null
        }
        return StartHint(
            engine = parts.getOrNull(0)?.takeIf { it.isNotEmpty() }?.let { name -> PlayerEngineType.entries.firstOrNull { it.name == name } },
            safeDecoder = parts.getOrNull(1) == "1",
            safeAudio = parts.getOrNull(2) == "1",
        )
    }

    override fun remember(contentKey: String, hint: StartHint) {
        val value = listOf(hint.engine?.name.orEmpty(), flag(hint.safeDecoder), flag(hint.safeAudio), clock().toString()).joinToString("|")
        prefs.edit { putString(HINT + contentKey, value) }
        prune()
    }

    override fun forget(contentKey: String) {
        if (prefs.contains(HINT + contentKey)) prefs.edit { remove(HINT + contentKey) }
    }

    override fun count(symptom: Symptom, fixed: Boolean) {
        val key = COUNT + symptom.code + if (fixed) ".ok" else ".seen"
        prefs.edit { putInt(key, prefs.getInt(key, 0) + 1) }
    }

    override fun stats(): Map<Symptom, Pair<Int, Int>> = Symptom.entries.associateWith { symptom ->
        prefs.getInt(COUNT + symptom.code + ".seen", 0) to prefs.getInt(COUNT + symptom.code + ".ok", 0)
    }.filterValues { it.first > 0 }

    private fun prune() {
        val hints = prefs.all.filterKeys { it.startsWith(HINT) }
        if (hints.size <= MAX_ENTRIES) return
        val now = clock()
        val byAge = hints.mapValues { (_, value) -> (value as? String)?.substringAfterLast('|')?.toLongOrNull() ?: 0L }
        val drop = byAge.entries.filter { now - it.value > MAX_AGE_MS }.map { it.key } +
            byAge.entries.sortedBy { it.value }.take((hints.size - MAX_ENTRIES).coerceAtLeast(0)).map { it.key }
        prefs.edit { drop.distinct().forEach { remove(it) } }
    }

    private fun flag(value: Boolean) = if (value) "1" else "0"

    private companion object {
        const val HINT = "hint:"
        const val COUNT = "count:"
        const val MAX_AGE_MS = 14L * 24 * 60 * 60 * 1000
        const val MAX_ENTRIES = 500
        const val TIME_FIELD = 3
    }
}
