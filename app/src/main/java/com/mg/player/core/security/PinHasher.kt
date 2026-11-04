package com.mg.player.core.security

import android.content.Context
import androidx.core.content.edit
import com.mg.player.core.log.AppLog
import java.security.MessageDigest
import java.security.NoSuchAlgorithmException
import java.security.SecureRandom
import javax.crypto.Mac
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * PIN-Speicherung mit PBKDF2 (HMAC-SHA256, 100.000 Runden) und Zufallswert: „pbkdf2:Runden:SalzHex:HashHex“.
 * Gleiches Format wie Version 3.1 – eine dort gesetzte PIN funktioniert weiter.
 * Früher: SHA-256("player-pin:" + PIN) ohne Salz – vier Ziffern ließen sich daraus in Millisekunden zurückrechnen.
 * Alte Werte werden weiter erkannt und beim nächsten richtigen Eingeben ersetzt.
 */
object PinHasher {

    private const val PREFIX = "pbkdf2:"

    /** Von 3.2-Vorabständen auf Android 6/7 geschrieben – wird weiter erkannt und beim Entsperren ersetzt. */
    private const val PREFIX_SHA1 = "pbkdf2sha1:"
    const val ITERATIONS = 100_000
    private const val KEY_BITS = 256

    /** Immer HMAC-SHA256 (Format wie 3.1): Android 6/7 bieten das nicht an – dann rechnet die App es selbst. */
    fun hash(pin: String, iterations: Int = ITERATIONS): String {
        val salt = ByteArray(16).also(SecureRandom()::nextBytes)
        return "$PREFIX$iterations:${hex(salt)}:${hex(derive(pin, salt, iterations, SHA256))}"
    }

    fun matches(pin: String, stored: String): Boolean {
        val algorithm = when {
            stored.startsWith(PREFIX) -> SHA256
            stored.startsWith(PREFIX_SHA1) -> SHA1
            else -> return MessageDigest.isEqual(legacy(pin).toByteArray(), stored.toByteArray())
        }
        val parts = stored.substringAfter(':').split(':')
        if (parts.size != 3) return false
        val iterations = parts[0].toIntOrNull()?.takeIf { it in 1..10_000_000 } ?: return false
        val salt = unhex(parts[1]) ?: return false
        val expected = unhex(parts[2]) ?: return false
        return MessageDigest.isEqual(derive(pin, salt, iterations, algorithm), expected)
    }

    /** Alter Wert (ohne Salz): sollte nach dem nächsten Entsperren ersetzt werden. */
    fun isLegacy(stored: String): Boolean = !stored.startsWith(PREFIX)

    /** Bis Version 3.0 genutzt – nur noch zum Erkennen alter Werte. */
    fun legacy(pin: String): String = hex(MessageDigest.getInstance("SHA-256").digest("player-pin:$pin".toByteArray(Charsets.UTF_8)))

    private const val SHA256 = "PBKDF2WithHmacSHA256"
    private const val SHA1 = "PBKDF2WithHmacSHA1"

    private fun derive(pin: String, salt: ByteArray, iterations: Int, algorithm: String): ByteArray {
        val spec = PBEKeySpec(pin.toCharArray(), salt, iterations, KEY_BITS)
        return try {
            SecretKeyFactory.getInstance(algorithm).generateSecret(spec).encoded
        } catch (e: NoSuchAlgorithmException) {
            // Android 6/7: PBKDF2WithHmacSHA256 fehlt (3.1 stürzte dort beim Festlegen der PIN ab).
            if (algorithm != SHA256) throw e
            pbkdf2Sha256(pin.toByteArray(Charsets.UTF_8), salt, iterations, KEY_BITS / 8)
        } finally {
            spec.clearPassword()
        }
    }

    /** PBKDF2 nach RFC 8018 mit HMAC-SHA256 (gibt es auf allen Android-Versionen). */
    internal fun pbkdf2Sha256(password: ByteArray, salt: ByteArray, iterations: Int, length: Int): ByteArray {
        val mac = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(password, "HmacSHA256")) }
        val out = ByteArray(length)
        var block = 1
        var offset = 0
        while (offset < length) {
            mac.update(salt)
            mac.update(byteArrayOf((block ushr 24).toByte(), (block ushr 16).toByte(), (block ushr 8).toByte(), block.toByte()))
            var u = mac.doFinal()
            val t = u.copyOf()
            repeat(iterations - 1) {
                u = mac.doFinal(u)
                for (i in t.indices) t[i] = (t[i].toInt() xor u[i].toInt()).toByte()
            }
            t.copyInto(out, offset, 0, minOf(t.size, length - offset))
            offset += t.size
            block++
        }
        return out
    }

    private fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }

    private fun unhex(text: String): ByteArray? {
        if (text.length % 2 != 0) return null
        return runCatching { ByteArray(text.length / 2) { i -> text.substring(i * 2, i * 2 + 2).toInt(16).toByte() } }.getOrNull()
    }
}

/**
 * Zweite Kopie des PIN-Werts außerhalb der Einstellungsdatei. Wird die Einstellungsdatei beschädigt und
 * zurückgesetzt, bleibt die Kindersicherung damit aktiv.
 */
interface PinBackup {
    fun read(): String?
    fun write(value: String?)

    object None : PinBackup {
        override fun read(): String? = null
        override fun write(value: String?) = Unit
    }
}

/** PIN-Kopie in einer eigenen, app-privaten Datei (nicht Teil der Einstellungsdatei). */
class PrefsPinBackup(context: Context) : PinBackup {
    private val prefs = context.applicationContext.getSharedPreferences("parental_backup", Context.MODE_PRIVATE)

    override fun read(): String? = prefs.getString(KEY, null)

    override fun write(value: String?) {
        // commit statt apply: Die Kopie muss sicher auf dem Datenträger sein, bevor die App weiterläuft.
        if (!prefs.edit().apply { if (value == null) remove(KEY) else putString(KEY, value) }.commit()) {
            AppLog.log("Kindersicherung", "PIN-Kopie nicht speicherbar")
        }
    }

    private companion object {
        const val KEY = "pin"
    }
}

/**
 * Fehlversuche bei der PIN-Eingabe, dauerhaft gespeichert: Ein Neustart der App setzt die Sperre nicht zurück
 * (sonst ließen sich alle 10.000 PINs durchprobieren). [blockedUntilWallMs] ist Uhrzeit (System.currentTimeMillis).
 */
interface PinAttempts {
    fun read(): Pair<Int, Long>
    fun write(failures: Int, blockedUntilWallMs: Long)

    class Memory : PinAttempts {
        private var value = 0 to 0L
        override fun read() = value
        override fun write(failures: Int, blockedUntilWallMs: Long) {
            value = failures to blockedUntilWallMs
        }
    }
}

class PrefsPinAttempts(context: Context) : PinAttempts {
    private val prefs = context.applicationContext.getSharedPreferences("parental_attempts", Context.MODE_PRIVATE)

    override fun read(): Pair<Int, Long> = prefs.getInt(FAILURES, 0) to prefs.getLong(UNTIL, 0L)

    override fun write(failures: Int, blockedUntilWallMs: Long) {
        // commit: Die Sperre muss auf dem Datenträger sein, bevor jemand die App beenden kann.
        prefs.edit(commit = true) {
            putInt(FAILURES, failures)
            putLong(UNTIL, blockedUntilWallMs)
        }
    }

    private companion object {
        const val FAILURES = "failures"
        const val UNTIL = "blocked_until"
    }
}
