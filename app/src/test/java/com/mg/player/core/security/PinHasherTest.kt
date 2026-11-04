package com.mg.player.core.security

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** PIN-Speicherung: PBKDF2 mit Salz, kompatibel zu Version 3.1 und zu den alten Werten ohne Salz. */
class PinHasherTest {

    @Test
    fun newHashMatchesOnlyTheRightPin() {
        val stored = PinHasher.hash("1234", iterations = 1_000)
        assertTrue(stored.startsWith("pbkdf2:1000:"))
        assertTrue(PinHasher.matches("1234", stored))
        assertFalse(PinHasher.matches("1235", stored))
        assertFalse(PinHasher.isLegacy(stored))
        // Zufälliges Salz: gleiche PIN, anderer Wert.
        assertNotEquals(stored, PinHasher.hash("1234", iterations = 1_000))
    }

    @Test
    fun readsValuesWrittenByVersion31() {
        // Mit Python erzeugt: pbkdf2_hmac('sha256', b'1234', salt, 100000, 32) – Format von Version 3.1.
        val stored = "pbkdf2:100000:00112233445566778899aabbccddeeff:d7eb973c765dbd5bc56c68688684a18d9d1dfb52c4fbb44d128851477cf7246b"
        assertTrue(PinHasher.matches("1234", stored))
        assertFalse(PinHasher.matches("4321", stored))
    }

    @Test
    fun legacyValuesStillWork() {
        val legacy = "de2dcd5c96fa178e65e7b39f05d2c8cfbfd18c606e857859466ad85f9db594e1"
        assertEquals(legacy, PinHasher.legacy("1234"))
        assertTrue(PinHasher.matches("1234", legacy))
        assertFalse(PinHasher.matches("0000", legacy))
        assertTrue(PinHasher.isLegacy(legacy))
    }

    @Test
    fun brokenValuesNeverMatch() {
        assertFalse(PinHasher.matches("1234", "pbkdf2:abc:00:00"))
        assertFalse(PinHasher.matches("1234", "pbkdf2:1000:zz:00"))
        assertFalse(PinHasher.matches("1234", "pbkdf2:1000:0011"))
    }

    /**
     * Android 6/7 haben kein PBKDF2WithHmacSHA256 (3.1 stürzte dort ab). Die eigene Berechnung muss exakt
     * dasselbe liefern (bekannte Prüfwerte und die 3.1-PIN von oben).
     */
    @Test
    fun ownPbkdf2Sha256MatchesTheStandard() {
        fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }
        assertEquals(
            "120fb6cffcf8b32c43e7225256c4f837a86548c92ccc35480805987cb70be17b",
            hex(PinHasher.pbkdf2Sha256("password".toByteArray(), "salt".toByteArray(), 1, 32)),
        )
        assertEquals(
            "ae4d0c95af6b46d32d0adff928f06dd02a303f8ef3c251dfd6e2d85a95474c43",
            hex(PinHasher.pbkdf2Sha256("password".toByteArray(), "salt".toByteArray(), 2, 32)),
        )
        val salt = ByteArray(16) { (it * 0x11).toByte() }
        assertEquals(
            "d7eb973c765dbd5bc56c68688684a18d9d1dfb52c4fbb44d128851477cf7246b",
            hex(PinHasher.pbkdf2Sha256("1234".toByteArray(), salt, 100_000, 32)),
        )
        // Länger als ein Block (64 Byte = zwei Blöcke).
        assertEquals(64, PinHasher.pbkdf2Sha256("p".toByteArray(), "s".toByteArray(), 3, 64).size)
    }
}
