package com.mg.player.core.vpn

import android.content.SharedPreferences
import androidx.compose.runtime.Immutable
import androidx.core.content.edit
import com.mg.player.core.security.SecretCipher
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

/** Ein gespeicherter VPN-Standort (ohne Schlüssel – der bleibt verschlüsselt im [VpnStore]). */
@Immutable
data class VpnProfile(val id: String, val name: String, val country: String?)

/** Letzter Abbruch fürs Anzeigen in den Einstellungen. */
@Immutable
data class VpnDrop(val atMs: Long, val reason: String)

/**
 * VPN-Standorte und Schalter. Die Konfiguration (mit privatem Schlüssel) liegt nur verschlüsselt vor
 * (Android Keystore über [SecretCipher]); Klartext gibt es nur im Speicher, während der Tunnel aufgebaut wird.
 */
class VpnStore(
    private val prefs: SharedPreferences,
    private val encrypt: (String) -> String = SecretCipher::encrypt,
    private val decrypt: (String) -> String = SecretCipher::decrypt,
) {
    private val _profiles = MutableStateFlow(load())
    val profiles: StateFlow<List<VpnProfile>> = _profiles.asStateFlow()

    private val _activeId = MutableStateFlow(prefs.getString(KEY_ACTIVE, null))
    val activeId: StateFlow<String?> = _activeId.asStateFlow()

    private val _enabled = MutableStateFlow(prefs.getBoolean(KEY_ENABLED, false))
    val enabled: StateFlow<Boolean> = _enabled.asStateFlow()

    private val _alertSound = MutableStateFlow(prefs.getBoolean(KEY_SOUND, false))
    val alertSound: StateFlow<Boolean> = _alertSound.asStateFlow()

    private val _lastDrop = MutableStateFlow(
        prefs.getLong(KEY_DROP_AT, 0L).takeIf { it > 0 }?.let { VpnDrop(it, prefs.getString(KEY_DROP_REASON, "").orEmpty()) },
    )
    val lastDrop: StateFlow<VpnDrop?> = _lastDrop.asStateFlow()

    fun active(): VpnProfile? = _profiles.value.firstOrNull { it.id == _activeId.value } ?: _profiles.value.firstOrNull()

    /** Klartext der Konfiguration – nur für den Tunnelaufbau und die verschlüsselte Sicherung. */
    fun configText(id: String): String? {
        val entries = rawEntries()
        val stored = (0 until entries.length()).mapNotNull { entries.optJSONObject(it) }
            .firstOrNull { it.optString("id") == id }?.optString("conf").orEmpty()
        return decrypt(stored).takeIf { it.isNotBlank() }
    }

    /**
     * Speichert verschlüsselt und macht den neuen Standort aktiv. [replaces]: dieser Standort wird in einem Schritt
     * ersetzt (Surfshark-Serverwechsel). Wirft, wenn sicheres Speichern nicht geht.
     */
    @Synchronized
    fun add(name: String, country: String?, configText: String, replaces: String? = null): VpnProfile {
        val sealed = encrypt(configText)
        val entries = JSONArray()
        val old = rawEntries()
        for (i in 0 until old.length()) old.optJSONObject(i)?.takeIf { it.optString("id") != replaces }?.let(entries::put)
        val profile = VpnProfile(UUID.randomUUID().toString(), uniqueName(name, parse(entries)), country)
        entries.put(JSONObject().put("id", profile.id).put("name", profile.name).put("country", country ?: "").put("conf", sealed))
        prefs.edit { putString(KEY_PROFILES, entries.toString()).putString(KEY_ACTIVE, profile.id) }
        _profiles.value = parse(entries)
        _activeId.value = profile.id
        return profile
    }

    @Synchronized
    fun remove(id: String) {
        val kept = JSONArray()
        val entries = rawEntries()
        for (i in 0 until entries.length()) entries.getJSONObject(i).takeIf { it.optString("id") != id }?.let(kept::put)
        val active = _activeId.value.takeIf { it != id } ?: parse(kept).firstOrNull()?.id
        prefs.edit { putString(KEY_PROFILES, kept.toString()).putString(KEY_ACTIVE, active) }
        _profiles.value = parse(kept)
        _activeId.value = active
        if (kept.length() == 0) setEnabled(false)
    }

    fun select(id: String) {
        prefs.edit { putString(KEY_ACTIVE, id) }
        _activeId.value = id
    }

    fun setEnabled(on: Boolean) {
        prefs.edit { putBoolean(KEY_ENABLED, on) }
        _enabled.value = on
    }

    fun setAlertSound(on: Boolean) {
        prefs.edit { putBoolean(KEY_SOUND, on) }
        _alertSound.value = on
    }

    fun recordDrop(atMs: Long, reason: String) {
        prefs.edit { putLong(KEY_DROP_AT, atMs).putString(KEY_DROP_REASON, reason) }
        _lastDrop.value = VpnDrop(atMs, reason)
    }

    private fun uniqueName(name: String, existing: List<VpnProfile>): String {
        val base = name.trim().ifEmpty { "VPN-Server" }.take(40)
        val taken = existing.map { it.name }.toSet()
        if (base !in taken) return base
        return (2..99).map { "$base ($it)" }.firstOrNull { it !in taken } ?: "$base (${taken.size + 1})"
    }

    private fun rawEntries(): JSONArray = runCatching { JSONArray(prefs.getString(KEY_PROFILES, "[]")) }.getOrDefault(JSONArray())

    private fun load(): List<VpnProfile> = parse(rawEntries())

    private fun parse(entries: JSONArray): List<VpnProfile> = (0 until entries.length()).mapNotNull { i ->
        val o = entries.optJSONObject(i) ?: return@mapNotNull null
        VpnProfile(o.optString("id"), o.optString("name"), o.optString("country").takeIf { it.isNotEmpty() })
            .takeIf { it.id.isNotEmpty() }
    }

    private companion object {
        const val KEY_PROFILES = "profiles"
        const val KEY_ACTIVE = "active"
        const val KEY_ENABLED = "enabled"
        const val KEY_SOUND = "alert_sound"
        const val KEY_DROP_AT = "last_drop_at"
        const val KEY_DROP_REASON = "last_drop_reason"
    }
}
