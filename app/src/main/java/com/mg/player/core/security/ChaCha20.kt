package com.mg.player.core.security

import java.io.ByteArrayOutputStream
import java.math.BigInteger
import java.security.MessageDigest

/**
 * ChaCha20 (RFC 8439) für die Übertragung vom Handy (VPN-Konfiguration, Zugangsdaten) – nur zusammen mit
 * Poly1305 verwenden, siehe [ChaCha20Poly1305] und [ShareKey].
 */
object ChaCha20 {

    const val KEY_BYTES = 32
    const val NONCE_BYTES = 12

    /** Ver- und Entschlüsseln sind dieselbe Operation (XOR mit dem Schlüsselstrom). */
    fun xor(key: ByteArray, nonce: ByteArray, data: ByteArray, counter: Int = 1): ByteArray {
        require(key.size == KEY_BYTES) { "Schlüssel muss 32 Byte haben" }
        require(nonce.size == NONCE_BYTES) { "Nonce muss 12 Byte haben" }
        val state = IntArray(16)
        val work = IntArray(16)
        val out = ByteArray(data.size)
        var block = counter
        var offset = 0
        while (offset < data.size) {
            state[0] = 0x61707865
            state[1] = 0x3320646e
            state[2] = 0x79622d32
            state[3] = 0x6b206574
            for (i in 0 until 8) state[4 + i] = littleEndian(key, i * 4)
            state[12] = block++
            for (i in 0 until 3) state[13 + i] = littleEndian(nonce, i * 4)
            state.copyInto(work)
            repeat(10) {
                quarter(work, 0, 4, 8, 12)
                quarter(work, 1, 5, 9, 13)
                quarter(work, 2, 6, 10, 14)
                quarter(work, 3, 7, 11, 15)
                quarter(work, 0, 5, 10, 15)
                quarter(work, 1, 6, 11, 12)
                quarter(work, 2, 7, 8, 13)
                quarter(work, 3, 4, 9, 14)
            }
            for (i in 0 until 16) work[i] += state[i]
            val end = minOf(64, data.size - offset)
            for (i in 0 until end) {
                val stream = (work[i shr 2] ushr ((i and 3) * 8)) and 0xFF
                out[offset + i] = (data[offset + i].toInt() xor stream).toByte()
            }
            offset += 64
        }
        return out
    }

    private fun littleEndian(bytes: ByteArray, at: Int): Int = (bytes[at].toInt() and 0xFF) or
        ((bytes[at + 1].toInt() and 0xFF) shl 8) or
        ((bytes[at + 2].toInt() and 0xFF) shl 16) or
        ((bytes[at + 3].toInt() and 0xFF) shl 24)

    private fun quarter(x: IntArray, a: Int, b: Int, c: Int, d: Int) {
        x[a] += x[b]
        x[d] = Integer.rotateLeft(x[d] xor x[a], 16)
        x[c] += x[d]
        x[b] = Integer.rotateLeft(x[b] xor x[c], 12)
        x[a] += x[b]
        x[d] = Integer.rotateLeft(x[d] xor x[a], 8)
        x[c] += x[d]
        x[b] = Integer.rotateLeft(x[b] xor x[c], 7)
    }
}

/**
 * ChaCha20-Poly1305 (RFC 8439, Abschnitt 2.8): wie [ChaCha20], aber mit Prüfwert. Ohne ihn ließe sich der
 * Geheimtext unterwegs gezielt verändern (Bits kippen), ohne dass der Fernseher es merkt.
 */
@Suppress("MagicNumber") // Block-, Tag- und Längenfelder aus RFC 8439.
object ChaCha20Poly1305 {

    const val TAG_BYTES = 16

    private val P = BigInteger.ONE.shiftLeft(130).subtract(BigInteger.valueOf(5))
    private val R_CLAMP = BigInteger("0ffffffc0ffffffc0ffffffc0fffffff", 16)
    private val MASK_128 = BigInteger.ONE.shiftLeft(128).subtract(BigInteger.ONE)

    /** Geheimtext mit angehängtem 16-Byte-Prüfwert. */
    fun seal(key: ByteArray, nonce: ByteArray, plain: ByteArray, aad: ByteArray = ByteArray(0)): ByteArray {
        val cipher = ChaCha20.xor(key, nonce, plain, counter = 1)
        return cipher + tag(key, nonce, aad, cipher)
    }

    /** Klartext oder null, wenn Prüfwert, Schlüssel oder Nonce nicht passen (verändert, abgeschnitten, gefälscht). */
    fun open(key: ByteArray, nonce: ByteArray, sealed: ByteArray, aad: ByteArray = ByteArray(0)): ByteArray? {
        if (sealed.size < TAG_BYTES) return null
        val cipher = sealed.copyOfRange(0, sealed.size - TAG_BYTES)
        val received = sealed.copyOfRange(sealed.size - TAG_BYTES, sealed.size)
        if (!MessageDigest.isEqual(tag(key, nonce, aad, cipher), received)) return null
        return ChaCha20.xor(key, nonce, cipher, counter = 1)
    }

    private fun tag(key: ByteArray, nonce: ByteArray, aad: ByteArray, cipher: ByteArray): ByteArray {
        val oneTimeKey = ChaCha20.xor(key, nonce, ByteArray(32), counter = 0)
        val data = ByteArrayOutputStream().apply {
            write(aad)
            write(ByteArray(pad16(aad.size)))
            write(cipher)
            write(ByteArray(pad16(cipher.size)))
            write(littleEndian64(aad.size.toLong()))
            write(littleEndian64(cipher.size.toLong()))
        }.toByteArray()
        return poly1305(oneTimeKey, data)
    }

    /** Poly1305 (RFC 8439, Abschnitt 2.5) – BigInteger genügt für die wenigen KB, die vom Handy kommen. */
    internal fun poly1305(key: ByteArray, message: ByteArray): ByteArray {
        val r = littleEndian(key, 0, 16).and(R_CLAMP)
        val s = littleEndian(key, 16, 16)
        var acc = BigInteger.ZERO
        var offset = 0
        while (offset < message.size) {
            val len = minOf(16, message.size - offset)
            acc = acc.add(littleEndian(message, offset, len).setBit(8 * len)).multiply(r).mod(P)
            offset += 16
        }
        val tag = acc.add(s).and(MASK_128)
        return ByteArray(16) { i -> tag.shiftRight(8 * i).toInt().toByte() }
    }

    private fun pad16(size: Int) = (16 - size % 16) % 16

    private fun littleEndian64(value: Long) = ByteArray(8) { i -> (value ushr (8 * i)).toByte() }

    /** Vorzeichenlose Little-Endian-Zahl aus [len] Bytes ab [from]. */
    private fun littleEndian(bytes: ByteArray, from: Int, len: Int): BigInteger {
        val bigEndian = ByteArray(len + 1)
        for (i in 0 until len) bigEndian[len - i] = bytes[from + i]
        return BigInteger(bigEndian)
    }
}
