package com.mg.player

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.mg.player.core.security.PinHasher
import com.mg.player.data.backup.BackupCrypto
import java.security.NoSuchAlgorithmException
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Sicherungen auf echtem Android (Emulator API 24/28/37 in der CI).
 *
 * Hintergrund: [BackupCrypto] leitet den Schlüssel mit `SecretKeyFactory("PBKDF2WithHmacSHA256")` ab. Auf Android 7
 * gibt es dieses Verfahren nicht, dort rechnet [PinHasher.pbkdf2Sha256] dasselbe in Kotlin – und zwar über die
 * **UTF-8-Bytes** des Kennworts. Macht der Anbieter einer anderen Android-Version die Zeichen→Byte-Umwandlung
 * anders (etwa nur das untere Byte je Zeichen), ergeben dieselben Zeichen einen anderen Schlüssel: Eine auf
 * Android 7 mit Umlauten gesicherte Datei liesse sich auf Android 10+ nicht mehr öffnen – und das merkt der
 * Nutzer erst beim Wiederherstellen, wenn die Daten schon weg sind.
 *
 * Robolectric kann das nicht prüfen, weil es den JDK-Anbieter benutzt, nicht den von Android. Diese Tests laufen
 * deshalb auf dem Gerät und decken beide Seiten ab: dass eigene Rechnung und Anbieter dasselbe ergeben, und dass
 * eine Sicherung mit Nicht-ASCII-Kennwort auf diesem API-Level durch Ver- und Entschlüsseln unverändert übersteht.
 */
@RunWith(AndroidJUnit4::class)
class BackupCryptoDeviceTest {

    /**
     * Der Kern: Anbieter und eigene Rechnung müssen Zeichen gleich in Bytes verwandeln. Die Rundenzahl spielt für
     * diese Frage keine Rolle, deshalb hier klein – das Ergebnis ist davon unabhängig.
     *
     * Fehlt das Verfahren (Android 7), gibt es nichts zu vergleichen: Dort ist die eigene Rechnung der einzige Pfad.
     */
    @Test
    fun providerAndOwnPbkdf2AgreeOnNonAsciiPasswords() {
        val salt = ByteArray(SALT_BYTES) { (it * 7 + 1).toByte() }
        PASSWORDS.forEach { password ->
            val fromProvider = runCatching {
                val spec = PBEKeySpec(password.toCharArray(), salt, COMPARE_ITERATIONS, KEY_BITS)
                SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
            }.getOrElse { error ->
                // Android 7: Das Verfahren fehlt, es gibt also nichts zu vergleichen - genau dafuer gibt es den
                // Rueckfallpfad, den der naechste Test Ende zu Ende prueft. Jeder andere Fehler ist echt.
                assertTrue("Unerwarteter Fehler bei der Schluesselableitung: $error", error is NoSuchAlgorithmException)
                return@forEach
            }
            val fromOwn = PinHasher.pbkdf2Sha256(password.toByteArray(Charsets.UTF_8), salt, COMPARE_ITERATIONS, KEY_BYTES)
            assertEquals(
                "Anbieter und eigene Rechnung weichen bei \"$password\" ab: Sicherungen waeren zwischen " +
                    "Android-Versionen nicht austauschbar",
                hex(fromOwn),
                hex(fromProvider),
            )
        }
    }

    /** Ende zu Ende auf diesem API-Level: Was verschlüsselt wurde, kommt unverändert zurück – auch mit Umlauten. */
    @Test
    fun backupSurvivesEncryptAndDecryptOnThisApiLevel() {
        PASSWORDS.forEach { password ->
            val envelope = BackupCrypto.encrypt(PLAIN, password, MIN_ALLOWED_ITERATIONS)
            assertTrue("Sicherung nicht als verschlüsselt erkannt", BackupCrypto.isEncrypted(envelope))
            assertEquals("Inhalt nach dem Wiederherstellen verändert", PLAIN, BackupCrypto.decrypt(envelope, password))
        }
    }

    /** Falsches Kennwort muss scheitern – sonst wäre die Prüfsumme wirkungslos. */
    @Test(expected = BackupCrypto.WrongPassphraseException::class)
    fun aWrongPassphraseIsRejected() {
        val envelope = BackupCrypto.encrypt(PLAIN, PASSWORDS.first(), MIN_ALLOWED_ITERATIONS)
        BackupCrypto.decrypt(envelope, PASSWORDS.first() + "x")
    }

    private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it) }

    private companion object {
        /** ASCII, Latin-1 über 127 (zwei UTF-8-Bytes) und außerhalb von Latin-1 (drei UTF-8-Bytes). */
        val PASSWORDS = listOf("kennwort", "pässwörtß", "日本語kennwort")
        const val PLAIN = "{\"accounts\":[],\"note\":\"Prüfinhalt\"}"
        const val COMPARE_ITERATIONS = 1_000
        /** Untergrenze von [BackupCrypto]: kleiner nimmt encrypt() nicht an. Hier so klein wie erlaubt, der
         *  Emulator rechnet 600 000 Runden sonst minutenlang – fuer den Rundlauf ist die Zahl ohne Belang. */
        const val MIN_ALLOWED_ITERATIONS = 10_000
        const val SALT_BYTES = 16
        const val KEY_BITS = 256
        const val KEY_BYTES = 32
    }
}
