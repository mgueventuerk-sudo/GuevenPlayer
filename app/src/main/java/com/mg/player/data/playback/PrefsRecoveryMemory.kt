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
 *
 * Dazu zwei Merker je Inhalt (ab 3.59): „hatte ein Bild“ und „ist Radio“ – daran erkennt der Wächter, ob „keine Videospur“
 * eine Störung oder normal ist. Sie verfallen nach [FLAG_MAX_AGE_MS] und sind auf [MAX_FLAGS] je Art begrenzt.
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

    override fun hadPicture(contentKey: String): Boolean = flagFresh(PICTURE + contentKey)

    override fun markHadPicture(contentKey: String) {
        val now = clock()
        val last = prefs.getLong(PICTURE + contentKey, -1L)
        val stale = last < 0 || now - last > FLAG_REFRESH_MS
        if (!stale && !prefs.contains(RADIO + contentKey)) return
        prefs.edit {
            if (stale) putLong(PICTURE + contentKey, now)
            remove(RADIO + contentKey)
        }
        if (stale) pruneFlags(PICTURE)
    }

    override fun isRadio(contentKey: String): Boolean = !hadPicture(contentKey) && flagFresh(RADIO + contentKey)

    override fun markRadio(contentKey: String) {
        prefs.edit { putLong(RADIO + contentKey, clock()) }
        pruneFlags(RADIO)
    }

    private fun flagFresh(key: String): Boolean {
        val at = prefs.getLong(key, 0L)
        return at > 0 && clock() - at <= FLAG_MAX_AGE_MS
    }

    /** Ältestes zuerst entfernen, wenn zu viele Merker einer Art da sind. */
    private fun pruneFlags(prefix: String) {
        val flags = prefs.all.filterKeys { it.startsWith(prefix) }
        if (flags.size <= MAX_FLAGS) return
        val oldest = flags.entries.sortedBy { (it.value as? Long) ?: 0L }.take(flags.size - MAX_FLAGS).map { it.key }
        prefs.edit { oldest.forEach { remove(it) } }
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
        const val PICTURE = "pic:"
        const val RADIO = "radio:"
        const val FLAG_MAX_AGE_MS = 90L * 24 * 60 * 60 * 1000

        /** „Hatte ein Bild“ höchstens einmal am Tag neu schreiben (der Wächter meldet es bei jedem stabilen Start). */
        const val FLAG_REFRESH_MS = 24L * 60 * 60 * 1000
        const val MAX_FLAGS = 1_000
        const val MAX_AGE_MS = 14L * 24 * 60 * 60 * 1000
        const val MAX_ENTRIES = 500
        const val TIME_FIELD = 3
    }
}
