package com.mg.player.data.remote

import com.mg.player.core.security.SecretCipher
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.io.IOException

/** Verschlüsselt nur M3U-Ziele mit erkennbaren Zugangsdaten; öffentliche Stream-URLs bleiben platzsparend. */
internal object StreamSecretStorage {
    private const val PREFIX = "stream-secret:v1:"
    private val secretName = Regex(
        "(^|[^a-z0-9])(user(name)?|pass(word|wd)?|token|auth(orization)?|cookie|api[-_]?key|key|secret|" +
            "signature|sign|credential|session|jwt|hdnea|wmsauthsign|sig)([^a-z0-9]|$)",
        RegexOption.IGNORE_CASE,
    )
    private val credentialPath = Regex("/(live|movie|series|timeshift)/[^/?|]+/[^/?|]+/[^/?|]+", RegexOption.IGNORE_CASE)

    fun protect(raw: String): String {
        if (raw.startsWith(PREFIX) || !containsSecret(raw)) return raw
        return PREFIX + SecretCipher.encrypt(raw)
    }

    @Throws(IOException::class)
    fun reveal(stored: String): String {
        if (!stored.startsWith(PREFIX)) return stored
        val plain = SecretCipher.decrypt(stored.removePrefix(PREFIX))
        if (plain.isEmpty()) throw IOException("Stream-Zugangsdaten können nicht entschlüsselt werden")
        return plain
    }

    fun isProtected(stored: String): Boolean = stored.startsWith(PREFIX)

    fun containsSecret(raw: String): Boolean {
        if (CredentialMask.isMasked(raw)) return false
        val pipe = raw.indexOf('|')
        val url = if (pipe < 0) raw else raw.substring(0, pipe)
        // Zugangsdaten in der Adresse gibt es nur mit „@“ (user:pass@host) oder „?“ (Parameter). Ohne beides die Adresse
        // nicht zerlegen: Beim M3U-Import war das der größte App-Posten (Profil Labor 3.24, 10 000 Sender).
        val parsed = if ('@' in url || '?' in url) url.toHttpUrlOrNull() else null
        val userInfo = parsed?.let { it.username.isNotEmpty() || it.password.isNotEmpty() } == true
        val secretQuery = parsed?.queryParameterNames?.any { secretName.containsMatchIn(it) } == true
        val providerPath = credentialPath.containsMatchIn(url)
        val secretHeader = pipe >= 0 && raw.substring(pipe + 1).split('&').any { field ->
            val name = field.substringBefore('=').replace('+', ' ')
            !name.equals("User-Agent", ignoreCase = true) &&
                !name.equals("Referer", ignoreCase = true) &&
                secretName.containsMatchIn(name)
        }
        return userInfo || secretQuery || providerPath || secretHeader
    }
}
