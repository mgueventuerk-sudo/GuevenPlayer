package com.mg.player.player

import android.content.Context
import androidx.core.content.edit
import com.mg.player.player.engine.TrackOption

/**
 * Gewählte Tonspur je Sender (bzw. Serie oder Film) merken und beim nächsten Start wieder wählen – z. B. Originalton
 * statt Deutsch. Gemerkt wird die Beschriftung (Sprache, 5.1 …) und zur Sicherheit die Position in der Liste.
 */
class TrackMemory(private val store: Store) {

    interface Store {
        fun get(key: String): String?
        fun put(key: String, value: String)
    }

    fun save(key: String, options: List<TrackOption>, chosen: TrackOption) {
        val index = options.indexOfFirst { it.id == chosen.id }
        if (index < 0) return
        store.put(key, "$index|${chosen.label}")
    }

    /** Die passende Spur aus [options] – oder null, wenn nichts gemerkt ist oder sie schon läuft. */
    fun restore(key: String, options: List<TrackOption>): TrackOption? {
        if (options.size < 2) return null
        val saved = store.get(key) ?: return null
        val index = saved.substringBefore('|').toIntOrNull()
        val label = saved.substringAfter('|')
        val match = options.firstOrNull { it.label == label } ?: index?.let { options.getOrNull(it) } ?: return null
        return match.takeUnless { it.selected }
    }

    companion object {
        fun prefs(context: Context): TrackMemory {
            val prefs = context.getSharedPreferences("audio_tracks", Context.MODE_PRIVATE)
            return TrackMemory(object : Store {
                override fun get(key: String): String? = prefs.getString(key, null)
                override fun put(key: String, value: String) {
                    prefs.edit { putString(key, value) }
                }
            })
        }
    }
}
