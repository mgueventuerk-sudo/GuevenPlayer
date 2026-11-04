package com.mg.player.core.security

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import java.io.File
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Verschlüsselt Zugangsdaten (Passwort, M3U-URL) mit einem Schlüssel aus dem Android Keystore.
 * Der Schlüssel verlässt das Gerät nie. Unverschlüsselte Altwerte werden unverändert gelesen.
 *
 * Manche günstigen TV-Boxen haben einen defekten Keystore. Dann wird ein zufälliger Schlüssel in einer
 * app-privaten Datei (nicht in Android-Backups) verwendet – schwächer als der Keystore, aber kein Klartext.
 * Geht auch das nicht, wird nichts gespeichert ([SecretStorageUnavailable]) – Klartext gibt es nie.
 */
object SecretCipher {

    private const val KEY_ALIAS = "player_credentials"
    private const val PREFIX = "enc1:"
    private const val FALLBACK_PREFIX = "enc0:"
    private const val FALLBACK_FILE = "credential.key"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val IV_LENGTH = 12
    private const val TAG_BITS = 128

    @Volatile
    private var cachedKey: SecretKey? = null

    @Volatile
    private var fallbackKey: SecretKey? = null

    @Volatile
    private var fallbackDirSource: (() -> File)? = null

    /**
     * Beim App-Start aufrufen: Ablageort für den Ersatzschlüssel. Nur gemerkt, nicht gelesen – `noBackupFilesDir`
     * legt das Verzeichnis an (Datenträgerzugriff) und stand im Fire-TV-Protokoll als erste StrictMode-Meldung
     * direkt im Startpfad. Gebraucht wird es erst, wenn der Keystore ausfällt.
     */
    fun init(context: Context) {
        val app = context.applicationContext
        fallbackDirSource = { app.noBackupFilesDir }
    }

    private val fallbackDir: File? get() = fallbackDirSource?.invoke()

    fun encrypt(plain: String): String {
        if (plain.isEmpty() || plain.startsWith(PREFIX) || plain.startsWith(FALLBACK_PREFIX)) return plain
        return try {
            PREFIX + seal(plain, key())
        } catch (e: Exception) {
            Log.w("SecretCipher", "Keystore nicht nutzbar – verwende Ersatzschlüssel", e)
            try {
                FALLBACK_PREFIX + seal(plain, fallbackKey())
            } catch (e2: Exception) {
                // Nie Klartext speichern: lieber abbrechen und dem Nutzer Bescheid geben.
                Log.w("SecretCipher", "Verschlüsselung nicht möglich", e2)
                throw SecretStorageUnavailable(e2)
            }
        }
    }

    /**
     * false, wenn ein verschlüsselter Wert nicht mehr lesbar ist – z. B. nach einer Geräte-Wiederherstellung
     * oder einem Firmware-Update, bei dem der Keystore-Schlüssel verloren ging.
     */
    fun isReadable(stored: String): Boolean = !isEncrypted(stored) || decryptOrNull(stored) != null

    fun decrypt(stored: String): String = decryptOrNull(stored) ?: ""

    private fun isEncrypted(stored: String) = stored.startsWith(PREFIX) || stored.startsWith(FALLBACK_PREFIX)

    private fun decryptOrNull(stored: String): String? {
        if (!isEncrypted(stored)) return stored
        return try {
            if (stored.startsWith(PREFIX)) open(stored.removePrefix(PREFIX), key()) else open(stored.removePrefix(FALLBACK_PREFIX), fallbackKey())
        } catch (e: Exception) {
            Log.w("SecretCipher", "Entschlüsselung fehlgeschlagen: ${e.javaClass.simpleName}")
            null
        }
    }

    private fun seal(plain: String, key: SecretKey): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        if (key is SecretKeySpec) {
            // Eigener Schlüssel: IV selbst erzeugen (nicht jeder Anbieter tut das bei GCM von allein).
            val iv = ByteArray(IV_LENGTH).also(SecureRandom()::nextBytes)
            cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(TAG_BITS, iv))
        } else {
            cipher.init(Cipher.ENCRYPT_MODE, key)
        }
        val encrypted = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(cipher.iv + encrypted, Base64.NO_WRAP)
    }

    private fun open(encoded: String, key: SecretKey): String {
        val bytes = Base64.decode(encoded, Base64.NO_WRAP)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, bytes, 0, IV_LENGTH))
        return String(cipher.doFinal(bytes, IV_LENGTH, bytes.size - IV_LENGTH), Charsets.UTF_8)
    }

    private fun fallbackKey(): SecretKey {
        fallbackKey?.let { return it }
        synchronized(this) {
            fallbackKey?.let { return it }
            val dir = fallbackDir ?: error("SecretCipher.init fehlt")
            val file = File(dir, FALLBACK_FILE)
            val bytes = if (file.length() == 32L) {
                file.readBytes()
            } else {
                val fresh = ByteArray(32).also(SecureRandom()::nextBytes)
                // Erst vollständig schreiben, dann umbenennen: ein Absturz hinterlässt nie einen halben Schlüssel.
                val tmp = File(dir, "$FALLBACK_FILE.tmp")
                tmp.writeBytes(fresh)
                check(tmp.renameTo(file)) { "Schlüsseldatei nicht speicherbar" }
                fresh
            }
            return SecretKeySpec(bytes, "AES").also { fallbackKey = it }
        }
    }

    private fun key(): SecretKey {
        cachedKey?.let { return it }
        synchronized(this) {
            cachedKey?.let { return it }
            val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            val existing = store.getKey(KEY_ALIAS, null) as? SecretKey
            val key = existing ?: KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
                init(
                    KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                        .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                        .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                        .setKeySize(256)
                        .build(),
                )
                generateKey()
            }
            cachedKey = key
            return key
        }
    }
}

/** Zugangsdaten lassen sich auf diesem Gerät gerade nicht verschlüsselt speichern. */
class SecretStorageUnavailable(cause: Throwable) :
    IllegalStateException(
        "Zugangsdaten können auf diesem Gerät nicht sicher gespeichert werden – bitte Gerät neu starten und erneut versuchen.",
        cause,
    )
