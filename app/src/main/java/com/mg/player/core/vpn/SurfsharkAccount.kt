package com.mg.player.core.vpn

import android.content.SharedPreferences
import androidx.core.content.edit
import com.mg.player.core.log.AppLog
import com.mg.player.core.security.SecretCipher
import com.wireguard.crypto.KeyPair
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

/**
 * Surfshark im eingebauten VPN: Server wählen, fertig.
 *
 * Der WireGuard-Schlüssel gilt bei Surfshark für alle Server. Er kommt aus
 * - einer einmal übertragenen Surfshark-.conf (zuverlässig – Surfshark erzeugt den Schlüssel im Konto) oder
 * - einer Anmeldung mit E-Mail/Passwort (Surfshark blockiert das für Apps oft per Cloudflare).
 * Die Serverliste ist öffentlich. Passwort wird nie gespeichert; Schlüssel und Tokens nur verschlüsselt.
 */
class SurfsharkAccount(
    private val prefs: SharedPreferences,
    private val api: SurfsharkApi,
    private val importer: VpnImporter,
    private val store: VpnStore,
    private val encrypt: (String) -> String = SecretCipher::encrypt,
    private val decrypt: (String) -> String = SecretCipher::decrypt,
    private val newKeyPair: () -> Pair<String, String> = { KeyPair().let { it.privateKey.toBase64() to it.publicKey.toBase64() } },
) {
    private val _account = MutableStateFlow<String?>(null)

    /** Woher der Schlüssel stammt („m…@gmail.com“ bzw. „Surfshark-Datei“); null = noch keiner. */
    val account: StateFlow<String?> = _account.asStateFlow()

    @Volatile
    private var cachedServers: Pair<Long, List<SurfsharkServer>>? = null

    /** Schlüssel suchen (gespeichert oder aus einem übertragenen Surfshark-Standort). Nicht im Hauptthread aufrufen. */
    fun refresh() {
        if (secret(K_PRIVATE) == null) adoptKeyFromProfiles()
        _account.value = when {
            secret(K_PRIVATE) == null -> null
            else -> secret(K_EMAIL)?.let(::maskEmail) ?: "Schlüssel aus Surfshark-Datei"
        }
    }

    suspend fun login(email: String, password: String): Result<Unit> = guarded {
        require(email.contains('@') && password.isNotEmpty()) { "Bitte E-Mail und Passwort eingeben." }
        val tokens = api.login(email, password)
        val (privateKey, publicKey) = withContext(Dispatchers.Default) { newKeyPair() }
        api.registerKey(tokens.token, publicKey, KEY_NAME)
        prefs.edit {
            putString(K_EMAIL, encrypt(email.trim()))
            putString(K_PRIVATE, encrypt(privateKey))
        }
        AppLog.log("VPN", "Surfshark angemeldet")
        refresh()
    }

    /** Öffentliche Serverliste (1 Stunde zwischengespeichert). */
    suspend fun servers(): Result<List<SurfsharkServer>> = guarded {
        cachedServers?.takeIf { System.currentTimeMillis() - it.first < CACHE_MS }?.let { return@guarded it.second }
        val list = api.servers()
        if (list.isEmpty()) throw SurfsharkException("Surfshark hat keine WireGuard-Server geliefert – bitte später erneut versuchen.")
        cachedServers = System.currentTimeMillis() to list
        list
    }

    /** Ein Klick: Server übernehmen (ersetzt den vorigen hier gewählten Surfshark-Standort) und verbinden. */
    fun use(server: SurfsharkServer): Result<VpnProfile> {
        val privateKey = secret(K_PRIVATE) ?: return Result.failure(SurfsharkException("Zuerst eine Surfshark-Datei übertragen oder anmelden."))
        val previous = prefs.getString(K_PROFILE, null)?.takeIf { id -> store.profiles.value.any { it.id == id } }
        return importer.import(configFor(privateKey, server), server.host, name = server.label, country = server.countryCode, replaces = previous)
            .onSuccess { profile -> prefs.edit { putString(K_PROFILE, profile.id) } }
    }

    /** Schlüssel vergessen. Übertragene bzw. gewählte Standorte bleiben nutzbar, bis man sie löscht. */
    fun logout() {
        prefs.edit {
            remove(K_EMAIL)
            remove(K_PRIVATE)
            putBoolean(K_NO_ADOPT, true)
        }
        cachedServers = null
        _account.value = null
        AppLog.log("VPN", "Surfshark-Schlüssel entfernt")
    }

    /** Privaten Schlüssel aus einem übertragenen Surfshark-Standort übernehmen (gilt für alle Surfshark-Server). */
    private fun adoptKeyFromProfiles() {
        if (prefs.getBoolean(K_NO_ADOPT, false)) return
        val key = store.profiles.value.firstNotNullOfOrNull { profile -> store.configText(profile.id)?.let(::surfsharkKeyOf) } ?: return
        prefs.edit { putString(K_PRIVATE, encrypt(key)) }
    }

    private fun secret(key: String): String? = prefs.getString(key, null)?.let(decrypt)?.takeIf { it.isNotBlank() }

    /** Fehler als Ergebnis – nie ein Absturz, nie ein Passwort oder Schlüssel in der Meldung. */
    private suspend fun <T> guarded(block: suspend () -> T): Result<T> = try {
        Result.success(block())
    } catch (e: CancellationException) {
        throw e
    } catch (e: SurfsharkException) {
        Result.failure(e)
    } catch (e: IllegalArgumentException) {
        Result.failure(e)
    } catch (e: Exception) {
        AppLog.log("VPN", "Surfshark: ${e.javaClass.simpleName}")
        Result.failure(SurfsharkException("Surfshark ist gerade nicht erreichbar – bitte später erneut versuchen."))
    }

    /** Beim Übertragen einer neuen Surfshark-Datei wieder automatisch übernehmen. */
    fun allowAdopt() {
        prefs.edit { remove(K_NO_ADOPT) }
    }

    companion object {
        private const val K_EMAIL = "email"
        private const val K_PRIVATE = "private_key"
        private const val K_PROFILE = "profile_id"
        private const val K_NO_ADOPT = "no_adopt"
        private const val KEY_NAME = "GuevenPlayer"
        private const val CACHE_MS = 3_600_000L

        private val ENDPOINT = Regex("""(?im)^\s*Endpoint\s*=\s*([^\s:]+)""")
        private val PRIVATE_KEY = Regex("""(?im)^\s*PrivateKey\s*=\s*([A-Za-z0-9+/]{42,43}=)""")

        /** Privater Schlüssel, wenn die .conf zu einem Surfshark-Server gehört. */
        fun surfsharkKeyOf(conf: String): String? {
            val host = ENDPOINT.find(conf)?.groupValues?.get(1)?.lowercase() ?: return null
            if (!host.endsWith(".surfshark.com")) return null
            return PRIVATE_KEY.find(conf)?.groupValues?.get(1)
        }

        /** WireGuard-Konfiguration wie in Surfsharks „Manuelle Einrichtung“ (Adresse und DNS von Surfshark). */
        fun configFor(privateKey: String, server: SurfsharkServer): String = """
            [Interface]
            PrivateKey = $privateKey
            Address = 10.14.0.2/16
            DNS = 162.252.172.57, 149.154.159.92

            [Peer]
            PublicKey = ${server.publicKey}
            AllowedIPs = 0.0.0.0/0
            Endpoint = ${server.host}:51820
            PersistentKeepalive = 25
        """.trimIndent()

        fun maskEmail(email: String): String {
            val at = email.indexOf('@')
            return if (at <= 1) "…" + email.substring(at.coerceAtLeast(0)) else email.take(1) + "…" + email.substring(at)
        }
    }
}
