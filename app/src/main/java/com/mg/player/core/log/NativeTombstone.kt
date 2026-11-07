package com.mg.player.core.log

import java.io.ByteArrayOutputStream
import java.io.InputStream

/** Liest die für einen Fehlerbericht nötigen Felder aus Androids binärem Tombstone-Protobuf. */
@Suppress("MagicNumber") // Feldnummern und Wire-Typen stammen aus AOSP debuggerd/proto/tombstone.proto.
internal object NativeTombstone {
    private const val MAX_BYTES = 2 * 1024 * 1024
    private const val MAX_FRAMES = 12

    fun summarize(input: InputStream): String = runCatching {
        val out = ByteArrayOutputStream()
        val chunk = ByteArray(8192)
        var count = input.read(chunk)
        while (count > 0 && out.size() < MAX_BYTES) {
            out.write(chunk, 0, count)
            count = input.read(chunk, 0, minOf(chunk.size, MAX_BYTES - out.size()))
        }
        parse(out.toByteArray())
    }.getOrNull() ?: "Nativer Tombstone konnte nicht ausgewertet werden."

    private fun parse(bytes: ByteArray): String? {
        val fields = Fields(bytes)
        var tid = -1L
        var signal = ""
        var thread = ""
        var frames = emptyList<String>()
        while (true) {
            val field = fields.next() ?: break
            when (field.number) {
                6 -> tid = field.numberValue ?: tid
                10 -> signal = field.bytes?.let(::signal) ?: signal
                16 -> {
                    val parsed = field.bytes?.let { readThreadEntry(it, tid) }
                    if (parsed != null) {
                        thread = parsed.first
                        frames = parsed.second
                    }
                }
            }
        }
        if (signal.isEmpty() && frames.isEmpty()) return null
        return buildString {
            append("Signal: ").append(signal.ifEmpty { "unbekannt" })
            if (thread.isNotEmpty()) append(" · Thread: ").append(thread).append(" (TID ").append(tid).append(')')
            frames.forEachIndexed { index, frame -> append("\n#").append(index).append(' ').append(frame) }
        }
    }

    private fun readThreadEntry(bytes: ByteArray, tid: Long): Pair<String, List<String>>? {
        val fields = Fields(bytes)
        var key: Long? = null
        var value: ByteArray? = null
        while (true) {
            val field = fields.next() ?: break
            if (field.number == 1) key = field.numberValue
            if (field.number == 2) value = field.bytes
        }
        return value?.takeIf { key == tid }?.let(::thread)
    }

    private fun signal(bytes: ByteArray): String {
        val fields = Fields(bytes)
        var name = ""
        var code = ""
        while (true) {
            val field = fields.next() ?: break
            if (field.number == 2) name = field.text()
            if (field.number == 4) code = field.text()
        }
        return listOf(name, code).filter { it.isNotEmpty() }.joinToString(" / ")
    }

    private fun thread(bytes: ByteArray): Pair<String, List<String>> {
        val fields = Fields(bytes)
        var name = ""
        val frames = ArrayList<String>()
        while (true) {
            val field = fields.next() ?: break
            if (field.number == 2) name = field.text()
            if (field.number == 4 && frames.size < MAX_FRAMES) field.bytes?.let { frame(it) }?.let(frames::add)
        }
        return name to frames
    }

    private fun frame(bytes: ByteArray): String? {
        val fields = Fields(bytes)
        var function = ""
        var file = ""
        while (true) {
            val field = fields.next() ?: break
            if (field.number == 4) function = field.text()
            if (field.number == 6) file = field.text().substringAfterLast('/')
        }
        if (function.isEmpty() && file.isEmpty()) return null
        return "$file: ${function.ifEmpty { "unbekannt" }}"
    }

    private data class Field(val number: Int, val numberValue: Long? = null, val bytes: ByteArray? = null) {
        fun text(): String = bytes?.toString(Charsets.UTF_8).orEmpty()
    }

    private class Fields(private val bytes: ByteArray) {
        private var offset = 0

        fun next(): Field? {
            if (offset >= bytes.size) return null
            val tag = varint().toInt()
            val number = tag ushr 3
            require(number > 0)
            return when (tag and 7) {
                0 -> Field(number, numberValue = varint())
                1 -> { skip(8); Field(number) }
                2 -> {
                    val length = varint()
                    require(length >= 0 && length <= bytes.size - offset)
                    val value = bytes.copyOfRange(offset, offset + length.toInt())
                    offset += length.toInt()
                    Field(number, bytes = value)
                }
                5 -> { skip(4); Field(number) }
                else -> error("Unbekannter Protobuf-Feldtyp")
            }
        }

        private fun skip(count: Int) {
            require(count <= bytes.size - offset)
            offset += count
        }

        private fun varint(): Long {
            var value = 0L
            for (shift in 0..63 step 7) {
                require(offset < bytes.size)
                val byte = bytes[offset++].toInt() and 0xff
                value = value or ((byte and 0x7f).toLong() shl shift)
                if (byte and 0x80 == 0) return value
            }
            error("Ungültige Protobuf-Zahl")
        }
    }
}
