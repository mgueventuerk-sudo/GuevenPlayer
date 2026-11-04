package com.mg.player.core.security

import android.util.Base64
import java.security.SecureRandom
import org.json.JSONObject

/**
 * Übertragung vom Handy (VPN-Konfiguration, Zugangsdaten): Der Browser verschlüsselt mit ChaCha20-Poly1305
 * ([SealScript]), der Schlüssel steht nur im QR-Code (#k=…, das Fragment schickt der Browser nie mit). So liest
 * und verändert im Heimnetz niemand die Daten. Ein Schlüssel gilt für eine geöffnete Seite.
 */
class ShareKey(private val key: ByteArray = ByteArray(ChaCha20.KEY_BYTES).also(SecureRandom()::nextBytes)) {

    /** Base64url ohne Auffüllung – für das URL-Fragment. */
    val fragment: String = Base64.encodeToString(key, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)

    /** {"n": Nonce, "c": Geheimtext + Prüfwert, "f": Dateiname} → Klartext und Dateiname; null bei falschem Format oder veränderten Daten. */
    fun open(body: String): Pair<String, String?>? = runCatching {
        val root = JSONObject(body)
        val nonce = Base64.decode(root.getString("n"), Base64.DEFAULT)
        val sealed = Base64.decode(root.getString("c"), Base64.DEFAULT)
        val plain = ChaCha20Poly1305.open(key, nonce, sealed) ?: return@runCatching null
        String(plain, Charsets.UTF_8) to root.optString("f").takeIf { it.isNotBlank() }
    }.getOrNull()
}
