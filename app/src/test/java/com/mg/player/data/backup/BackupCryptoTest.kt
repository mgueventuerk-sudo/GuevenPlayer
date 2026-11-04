package com.mg.player.data.backup

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

@OptIn(ExperimentalEncodingApi::class)
class BackupCryptoTest {

    private val plain = """{"format":"mg-player-backup-1","playlists":[{"password":"geheim"}]}"""

    /** Schnelle Rundenzahl für Tests – die Logik ist dieselbe. */
    private val fast = 10_000

    @Test
    fun `verschlüsseln und entschlüsseln ergibt das Original`() {
        val encrypted = BackupCrypto.encrypt(plain, "kennwort1", fast)
        assertTrue(BackupCrypto.isEncrypted(encrypted))
        assertFalse(encrypted.contains("geheim"))
        assertEquals(plain, BackupCrypto.decrypt(encrypted, "kennwort1"))
    }

    @Test
    fun `neue Sicherungen nutzen SHA256 mit 600 000 Runden`() {
        val root = JSONObject(BackupCrypto.encrypt(plain, "kennwort1"))
        assertEquals("mg-player-backup-enc-2", root.getString("format"))
        assertEquals("PBKDF2WithHmacSHA256", root.getString("kdf"))
        assertEquals(600_000, root.getInt("iterations"))
    }

    @Test(expected = BackupCrypto.WrongPassphraseException::class)
    fun `falsches Kennwort wird erkannt`() {
        BackupCrypto.decrypt(BackupCrypto.encrypt(plain, "kennwort1", fast), "kennwort2")
    }

    @Test(expected = IllegalArgumentException::class)
    fun `zu kurzes Kennwort wird abgelehnt`() {
        BackupCrypto.encrypt(plain, "1234567")
    }

    @Test
    fun `alte unverschlüsselte Sicherung wird erkannt`() {
        assertFalse(BackupCrypto.isEncrypted(plain))
    }

    /** Die Iterationszahl steht in der hochgeladenen Datei: 2^31 Runden PBKDF2 blockieren den Fernseher stundenlang. */
    @Test(timeout = 5_000, expected = BackupCrypto.WrongPassphraseException::class)
    fun `fremde Iterationszahl wird begrenzt`() {
        val envelope = JSONObject(BackupCrypto.encrypt(plain, "kennwort1", fast)).put("iterations", Int.MAX_VALUE).toString()
        BackupCrypto.decrypt(envelope, "kennwort1")
    }

    /** Rundenzahl herabsetzen (schnelleres Durchprobieren) oder Verfahren tauschen fällt über die AAD auf. */
    @Test(expected = BackupCrypto.WrongPassphraseException::class)
    fun `geänderte Rundenzahl bricht die Prüfsumme`() {
        val envelope = JSONObject(BackupCrypto.encrypt(plain, "kennwort1", 20_000)).put("iterations", 10_000).toString()
        BackupCrypto.decrypt(envelope, "kennwort1")
    }

    @Test(expected = BackupCrypto.WrongPassphraseException::class)
    fun `fremdes Verfahren wird nicht ausgeführt`() {
        val envelope = JSONObject(BackupCrypto.encrypt(plain, "kennwort1", fast)).put("kdf", "PBKDF2WithHmacMD5").toString()
        BackupCrypto.decrypt(envelope, "kennwort1")
    }

    @Test(expected = BackupCrypto.WrongPassphraseException::class)
    fun `kaputtes Base64 ist ein Fehler, kein Absturz`() {
        val envelope = JSONObject(BackupCrypto.encrypt(plain, "kennwort1", fast)).put("iv", "%%%").toString()
        BackupCrypto.decrypt(envelope, "kennwort1")
    }

    @Test(expected = IllegalArgumentException::class)
    fun `keine JSON-Datei ist ein klarer Fehler`() {
        BackupCrypto.decrypt("das ist keine sicherung", "kennwort1")
    }

    /** Sicherungen aus 3.17 und älter (SHA1, ohne AAD, Kennwort ab 6 Zeichen) bleiben lesbar. */
    @Test
    fun `Format 1 wird weiter gelesen`() {
        val random = SecureRandom()
        val salt = ByteArray(16).also(random::nextBytes)
        val iv = ByteArray(12).also(random::nextBytes)
        val spec = PBEKeySpec("altes6".toCharArray(), salt, 120_000, 256)
        val key = SecretKeySpec(SecretKeyFactory.getInstance("PBKDF2WithHmacSHA1").generateSecret(spec).encoded, "AES")
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, iv)) }
        val v1 = JSONObject()
            .put("format", "mg-player-backup-enc-1")
            .put("kdf", "PBKDF2WithHmacSHA1")
            .put("iterations", 120_000)
            .put("salt", Base64.encode(salt))
            .put("iv", Base64.encode(iv))
            .put("data", Base64.encode(cipher.doFinal(plain.toByteArray())))
            .toString()
        assertTrue(BackupCrypto.isEncrypted(v1))
        assertEquals(plain, BackupCrypto.decrypt(v1, "altes6"))
    }
}
