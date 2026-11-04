package com.mg.player.core.security

import android.util.Base64
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** ChaCha20-Poly1305 nach RFC 8439 (gleiche Rechnung wie die Handy-Seiten im Browser) und die Übertragung vom Handy. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class ChaCha20Test {

    private val plain = "Ladies and Gentlemen of the class of '99: If I could offer you only one tip for the future, sunscreen would be it."

    @Test
    fun rfc8439TestVector() {
        val key = ByteArray(32) { it.toByte() }
        val nonce = byteArrayOf(0, 0, 0, 0, 0, 0, 0, 0x4a, 0, 0, 0, 0)
        val sealed = ChaCha20.xor(key, nonce, plain.toByteArray())
        assertEquals(
            "6e2e359a2568f98041ba0728dd0d6981e97e7aec1d4360c20a27afccfd9fae0bf91b65c5524733ab8f593dabcd62b3571639d624e65152ab8f530c359f0861d8" +
                "07ca0dbf500d6a6156a38e088a22b65e52bc514d16ccf806818ce91ab77937365af90bbf74a35be6b40b8eedf2785e42874d",
            sealed.joinToString("") { "%02x".format(it) },
        )
        assertEquals(plain, String(ChaCha20.xor(key, nonce, sealed)))
    }

    @Test
    fun shareKeyOpensWhatTheBrowserSent() {
        val keyBytes = ByteArray(32) { (it * 7).toByte() }
        val share = ShareKey(keyBytes)
        assertEquals(keyBytes.toList(), Base64.decode(share.fragment, Base64.URL_SAFE).toList())
        val nonce = ByteArray(12) { (it + 1).toByte() }
        val body = JSONObject()
            .put("n", Base64.encodeToString(nonce, Base64.NO_WRAP))
            .put("c", Base64.encodeToString(ChaCha20Poly1305.seal(keyBytes, nonce, "[Interface]\nPrivateKey = x".toByteArray()), Base64.NO_WRAP))
            .put("f", "de-fra.conf")
            .toString()
        assertEquals("[Interface]\nPrivateKey = x" to "de-fra.conf", share.open(body))
        assertNull(share.open("kein json"))
    }

    @Test
    fun poly1305Rfc8439Vector() {
        val key = hex("85d6be7857556d337f4452fe42d506a80103808afb0db2fd4abff6af4149f51b")
        assertEquals("a8061dc1305136c6c22b8baf0c0127a9", ChaCha20Poly1305.poly1305(key, "Cryptographic Forum Research Group".toByteArray()).hex())
    }

    @Test
    fun aeadRfc8439Vector() {
        val key = ByteArray(32) { (0x80 + it).toByte() }
        val nonce = hex("070000004041424344454647")
        val aad = hex("50515253c0c1c2c3c4c5c6c7")
        val sealed = ChaCha20Poly1305.seal(key, nonce, plain.toByteArray(), aad)
        assertEquals("d31a8d34648e60db7b86afbc53ef7ec2", sealed.copyOfRange(0, 16).hex())
        assertEquals("1ae10b594f09e26a7e902ecbd0600691", sealed.copyOfRange(sealed.size - 16, sealed.size).hex())
        assertEquals(plain, String(ChaCha20Poly1305.open(key, nonce, sealed, aad)!!))
    }

    /** Wer im WLAN mitliest, kennt den Aufbau einer .conf und kann ohne Prüfwert gezielt Bits kippen (z. B. im Endpoint). */
    @Test
    fun tamperedOrTruncatedUploadIsRejected() {
        val keyBytes = ByteArray(32) { (it * 7).toByte() }
        val share = ShareKey(keyBytes)
        val nonce = ByteArray(12) { (it + 1).toByte() }
        val sealed = ChaCha20Poly1305.seal(keyBytes, nonce, "Endpoint = 192.0.2.4:51820".toByteArray())
        fun body(c: ByteArray) = JSONObject()
            .put("n", Base64.encodeToString(nonce, Base64.NO_WRAP))
            .put("c", Base64.encodeToString(c, Base64.NO_WRAP))
            .toString()
        assertEquals("Endpoint = 192.0.2.4:51820", share.open(body(sealed))?.first)
        val flipped = sealed.copyOf().also { it[19] = (it[19].toInt() xor 0x01).toByte() }
        assertNull("Veränderter Geheimtext muss abgelehnt werden", share.open(body(flipped)))
        assertNull(share.open(body(sealed.copyOf(sealed.size - 1))))
        assertNull(share.open(body(ByteArray(8))))
        assertNull("Fremder Schlüssel", ShareKey(ByteArray(32)).open(body(sealed)))
    }

    private fun hex(s: String) = ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

    private fun ByteArray.hex() = joinToString("") { "%02x".format(it) }
}
