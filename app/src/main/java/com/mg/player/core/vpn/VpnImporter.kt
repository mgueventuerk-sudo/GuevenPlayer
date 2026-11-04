package com.mg.player.core.vpn

import com.mg.player.core.log.AppLog
import com.mg.player.core.security.SecretStorageUnavailable
import com.mg.player.core.util.runCatchingCancellable
import org.json.JSONArray
import org.json.JSONObject

/**
 * Einrichten aus einer .conf (Upload vom Handy, Datei, Zwischenablage) und VPN-Teil der Sicherung.
 * Nach dem Import ist der neue Standort aktiv und das VPN eingeschaltet (Android fragt dann einmal nach der Erlaubnis).
 */
class VpnImporter(private val store: VpnStore, private val controller: VpnController) {

    fun import(raw: String, fileName: String? = null, name: String? = null, country: String? = null, replaces: String? = null): Result<VpnProfile> {
        val unsupported = IllegalStateException("Das eingebaute VPN braucht Android 7 oder neuer.")
        if (!controller.supported) return Result.failure(unsupported)
        val text = raw.removePrefix("\uFEFF").replace("\r", "").trim()
        val parsed = try {
            VpnConfigParser.parse(text, fileName).getOrElse { return Result.failure(it) }
        } catch (e: LinkageError) {
            return Result.failure(IllegalStateException("Das eingebaute VPN wird auf diesem Gerät nicht unterstützt."))
        }
        val profile = try {
            store.add(name ?: parsed.suggestedName, country ?: parsed.country, text, replaces)
        } catch (e: SecretStorageUnavailable) {
            return Result.failure(e)
        } catch (e: Exception) {
            return Result.failure(IllegalStateException("Die Konfiguration ließ sich nicht sicher speichern."))
        }
        AppLog.log("VPN", "Standort eingerichtet: ${profile.name}")
        controller.enableAsync()
        return Result.success(profile)
    }

    /** Für die (mit Kennwort verschlüsselte) Sicherung – enthält die Konfigurationen im Klartext. */
    fun backup(): JSONObject {
        val profiles = JSONArray()
        store.profiles.value.forEach { p ->
            val conf = store.configText(p.id) ?: return@forEach
            profiles.put(JSONObject().put("name", p.name).put("country", p.country ?: "").put("conf", conf).put("active", p.id == store.active()?.id))
        }
        return JSONObject().put("enabled", store.enabled.value).put("alertSound", store.alertSound.value).put("profiles", profiles)
    }

    /** Übernimmt Standorte aus einer Sicherung (gleichnamige vorhandene bleiben unverändert). */
    fun restore(json: JSONObject) {
        val existing = store.profiles.value.map { it.name }.toSet()
        val profiles = json.optJSONArray("profiles") ?: JSONArray()
        var active: VpnProfile? = null
        for (i in 0 until profiles.length()) {
            val o = profiles.optJSONObject(i) ?: continue
            val name = o.optString("name")
            if (name in existing) continue
            val country = o.optString("country").takeIf { it.isNotEmpty() }
            val added = runCatchingCancellable { store.add(name, country, o.optString("conf")) }.getOrNull() ?: continue
            if (o.optBoolean("active")) active = added
        }
        active?.let { store.select(it.id) }
        if (profiles.length() > 0) {
            // Die Sicherung enthält die privaten Schlüssel des Quellgeräts. Laufen beide Geräte gleichzeitig mit demselben
            // Schlüssel, wirft der Server sie abwechselnd raus (Protokoll 04.11.2026).
            AppLog.log("VPN", "Standorte aus Sicherung übernommen – nicht gleichzeitig mit dem Quellgerät nutzen (gleicher Schlüssel)")
        }
        store.setAlertSound(json.optBoolean("alertSound", store.alertSound.value))
        if (json.optBoolean("enabled") && store.profiles.value.isNotEmpty()) controller.enableAsync()
    }
}
