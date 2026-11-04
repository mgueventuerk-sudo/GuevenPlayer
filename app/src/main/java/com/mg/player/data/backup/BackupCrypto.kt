package com.mg.player.data.backup

import com.mg.player.core.security.PinHasher
import org.json.JSONException
import org.json.JSONObject
import java.security.NoSuchAlgorithmException
import java.security.SecureRandom
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/**
 * Verschlüsselt Sicherungen mit einem Kennwort (PBKDF2 + AES-256-GCM). Die Sicherungsdatei enthält
 * Xtream-Passwörter, M3U-Links und den privaten WireGuard-Schlüssel – sie verlässt den Fernseher (Handy, Cloud)
 * und kann dort beliebig lange offline durchprobiert werden.
 *
 * Format 2 (ab 3.18): PBKDF2-HMAC-SHA256 mit 600 000 Runden (OWASP-Empfehlung). Android 7 bietet SHA256 für
 * PBKDF2 noch nicht an – dann rechnet [PinHasher.pbkdf2Sha256] (RFC 8018) dasselbe in Kotlin.
 * Format 1 (bis 3.17: PBKDF2-HMAC-SHA1, 120 000 Runden) wird weiter gelesen, aber nicht mehr geschrieben.
 */
@OptIn(ExperimentalEncodingApi::class)
object BackupCrypto {

    const val FORMAT = "mg-player-backup-enc-2"
    private const val FORMAT_V1 = "mg-player-backup-enc-1"
    const val MIN_PASSPHRASE = 8
    internal const val ITERATIONS = 600_000
    private const val MIN_ITERATIONS = 10_000
    private const val MAX_ITERATIONS = 2_000_000
    private const val KEY_BITS = 256
    private const val TAG_BITS = 128
    private const val TAG_BYTES = 16
    private const val KEY_BYTES = 32
    private const val SALT_BYTES = 16
    private const val IV_BYTES = 12
    private const val KDF_SHA256 = "PBKDF2WithHmacSHA256"
    private const val KDF_SHA1 = "PBKDF2WithHmacSHA1"

    class WrongPassphraseException(cause: Throwable? = null) : Exception("Falsches Kennwort für diese Sicherung.", cause)

    fun isEncrypted(text: String): Boolean = runCatching { JSONObject(text).optString("format") in setOf(FORMAT, FORMAT_V1) }.getOrDefault(false)

    fun encrypt(plain: String, passphrase: String, iterations: Int = ITERATIONS): String {
        require(passphrase.length >= MIN_PASSPHRASE) { "Das Kennwort braucht mindestens $MIN_PASSPHRASE Zeichen." }
        require(iterations in MIN_ITERATIONS..MAX_ITERATIONS) { "Ungültige Rundenzahl" }
        val random = SecureRandom()
        val salt = ByteArray(SALT_BYTES).also(random::nextBytes)
        val iv = ByteArray(IV_BYTES).also(random::nextBytes)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key(passphrase, salt, iterations, KDF_SHA256), GCMParameterSpec(TAG_BITS, iv))
        // Kopfdaten als AAD: Wer Format, Verfahren oder Rundenzahl in der Datei ändert, bricht die Prüfsumme.
        val header = header(FORMAT, KDF_SHA256, iterations)
        cipher.updateAAD(header.toByteArray(Charsets.UTF_8))
        val data = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        return JSONObject()
            .put("format", FORMAT)
            .put("kdf", KDF_SHA256)
            .put("iterations", iterations)
            .put("salt", Base64.encode(salt))
            .put("iv", Base64.encode(iv))
            .put("data", Base64.encode(data))
            .toString()
    }

    fun decrypt(envelope: String, passphrase: String): String {
        val root = parseEnvelope(envelope)
        val format = root.optString("format")
        val kdf = kdfOf(root, format)
        // Aus der Datei, also fremd: ohne Obergrenze rechnet PBKDF2 bei 2^31 Runden stundenlang.
        val iterations = root.optInt("iterations", -1)
        val salt = decodeField(root, "salt")
        val iv = decodeField(root, "iv")
        val data = decodeField(root, "data")
        val plausible = iterations in MIN_ITERATIONS..MAX_ITERATIONS && salt.size >= SALT_BYTES && iv.size == IV_BYTES && data.size >= TAG_BYTES
        if (!plausible) throw WrongPassphraseException()
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(passphrase, salt, iterations, kdf), GCMParameterSpec(TAG_BITS, iv))
        if (format == FORMAT) cipher.updateAAD(header(format, kdf, iterations).toByteArray(Charsets.UTF_8))
        return try {
            String(cipher.doFinal(data), Charsets.UTF_8)
        } catch (e: AEADBadTagException) {
            throw WrongPassphraseException(e)
        }
    }

    private fun parseEnvelope(envelope: String): JSONObject = try {
        JSONObject(envelope)
    } catch (e: JSONException) {
        throw IllegalArgumentException("Die Datei ist keine GuevenPlayer-Sicherung.", e)
    }

    /** Verfahren nur aus einer festen Liste – nie einen beliebigen Namen aus der Datei an SecretKeyFactory geben. */
    private fun kdfOf(root: JSONObject, format: String): String = when (format) {
        FORMAT -> KDF_SHA256.takeIf { root.optString("kdf") == it } ?: throw WrongPassphraseException()
        FORMAT_V1 -> KDF_SHA1
        else -> throw IllegalArgumentException("Unbekanntes Sicherungsformat.")
    }

    private fun header(format: String, kdf: String, iterations: Int) = "$format|$kdf|$iterations"

    private fun decodeField(root: JSONObject, name: String): ByteArray = try {
        Base64.decode(root.getString(name))
    } catch (e: JSONException) {
        throw WrongPassphraseException(e)
    } catch (e: IllegalArgumentException) {
        throw WrongPassphraseException(e)
    }

    private fun key(passphrase: String, salt: ByteArray, iterations: Int, kdf: String): SecretKeySpec {
        val spec = PBEKeySpec(passphrase.toCharArray(), salt, iterations, KEY_BITS)
        val bytes = try {
            SecretKeyFactory.getInstance(kdf).generateSecret(spec).encoded
        } catch (e: NoSuchAlgorithmException) {
            // Android 7: PBKDF2WithHmacSHA256 fehlt – gleiche Rechnung in Kotlin.
            if (kdf != KDF_SHA256) throw e
            PinHasher.pbkdf2Sha256(passphrase.toByteArray(Charsets.UTF_8), salt, iterations, KEY_BYTES)
        } finally {
            spec.clearPassword()
        }
        return SecretKeySpec(bytes, "AES")
    }
}
