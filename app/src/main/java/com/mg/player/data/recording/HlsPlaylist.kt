package com.mg.player.data.recording

import java.io.InputStream
import java.nio.ByteBuffer
import javax.crypto.Cipher
import javax.crypto.CipherInputStream
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Eine HLS-Medien-Playlist, soweit die Aufnahme sie braucht: Segmente mit Sequenznummer und Schlüssel.
 * Bis 3.12.1 wurden verschlüsselte Segmente (#EXT-X-KEY, AES-128) unverändert gespeichert – die Aufnahme war dann
 * nicht abspielbar. Und Segmente wurden an ihrer Adresse erkannt: Bei Anbietern mit wechselnden Token-Adressen
 * landete dasselbe Segment mehrfach in der Datei. Jetzt zählt die Sequenznummer.
 */
data class HlsPlaylist(val segments: List<Segment>, val targetDurationSec: Double, val endList: Boolean, val mapUri: String?) {
    data class Key(val method: String, val uri: String?, val iv: ByteArray?) {
        val isAes128: Boolean get() = method.equals("AES-128", ignoreCase = true)
        val isNone: Boolean get() = method.equals("NONE", ignoreCase = true)
    }

    data class Segment(val uri: String, val sequence: Long, val key: Key?)

    companion object {
        private val ATTR = Regex("""([A-Z0-9-]+)=("[^"]*"|[^,]*)""")

        private fun attributes(line: String): Map<String, String> =
            ATTR.findAll(line.substringAfter(':')).associate { it.groupValues[1] to it.groupValues[2].removeSurrounding("\"") }

        fun parse(text: String): HlsPlaylist {
            var sequence = 0L
            var key: Key? = null
            var target = 6.0
            var endList = false
            var map: String? = null
            val segments = ArrayList<Segment>()
            var index = 0L
            for (raw in text.lineSequence()) {
                val line = raw.trim()
                when {
                    line.isEmpty() -> Unit

                    line.startsWith("#EXT-X-MEDIA-SEQUENCE:") -> sequence = line.substringAfter(':').trim().toLongOrNull() ?: 0L

                    line.startsWith("#EXT-X-TARGETDURATION:") -> target = line.substringAfter(':').trim().toDoubleOrNull() ?: 6.0

                    line.startsWith("#EXT-X-ENDLIST") -> endList = true

                    line.startsWith("#EXT-X-MAP") -> map = attributes(line)["URI"]

                    line.startsWith("#EXT-X-KEY") -> {
                        val a = attributes(line)
                        val method = a["METHOD"] ?: "NONE"
                        key = if (method.equals("NONE", ignoreCase = true)) null else Key(method, a["URI"], a["IV"]?.let(::hex))
                    }

                    line.startsWith("#") -> Unit

                    else -> segments += Segment(line, sequence + index++, key)
                }
            }
            return HlsPlaylist(segments, target, endList, map)
        }

        /** „0x00…“ → 16 Bytes. */
        internal fun hex(value: String): ByteArray? {
            val digits = value.removePrefix("0x").removePrefix("0X").padStart(32, '0')
            if (digits.length != 32) return null
            return ByteArray(16) { i -> digits.substring(i * 2, i * 2 + 2).toInt(16).toByte() }
        }

        /** Ohne IV-Angabe ist laut HLS-Standard die Sequenznummer (128 Bit, big endian) der IV. */
        fun ivFor(segment: Segment): ByteArray = segment.key?.iv ?: ByteBuffer.allocate(16).putLong(0L).putLong(segment.sequence).array()

        /** AES-128-CBC entschlüsseln, während gelesen wird (das Segment liegt nie ganz im Speicher). */
        fun decrypting(input: InputStream, key: ByteArray, iv: ByteArray): InputStream {
            val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
            return CipherInputStream(input, cipher)
        }
    }
}
