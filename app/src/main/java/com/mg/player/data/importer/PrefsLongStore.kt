package com.mg.player.data.importer

import android.content.SharedPreferences
import androidx.core.content.edit

/** Dauerhafte Ablage für [FallbackEpgGate] (überlebt App-Neustarts). */
class PrefsLongStore(private val prefs: SharedPreferences) : FallbackEpgGate.Store {
    override fun get(key: String): Long? = if (prefs.contains(key)) prefs.getLong(key, 0L) else null
    override fun put(key: String, value: Long) = prefs.edit { putLong(key, value) }
}
