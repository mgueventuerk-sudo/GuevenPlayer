package com.mg.player.core.util

import java.text.Normalizer

/**
 * Viele IPTV-Anbieter schreiben Namen in Zierschrift: "ᴅᴇ ┃ ᴢᴅꜰ ʜᴅ", "𝗭𝗗𝗙", "ＺＤＦ", "🅉🄳🄵". Für Anzeige, Ländererkennung,
 * Sender-Gruppierung, EPG-Zuordnung und Suche wird daraus normale Schrift – sonst findet die Suche "zdf" nicht
 * und das Land "ᴅᴇ" wird nicht erkannt.
 *
 * Kapitälchen (ᴀ ʙ ᴄ …) haben in Unicode keine Zerlegung und werden deshalb über eine Tabelle ersetzt
 * (als Großbuchstaben – so sehen sie auch aus). Alles andere erledigt NFKC.
 */
object TextFold {

    private val smallCapitals: Map<Char, Char> = mapOf(
        'ᴀ' to 'A', 'ʙ' to 'B', 'ᴄ' to 'C', 'ᴅ' to 'D', 'ᴇ' to 'E', 'ꜰ' to 'F', 'ɢ' to 'G', 'ʜ' to 'H', 'ɪ' to 'I',
        'ᴊ' to 'J', 'ᴋ' to 'K', 'ʟ' to 'L', 'ᴍ' to 'M', 'ɴ' to 'N', 'ᴏ' to 'O', 'ᴘ' to 'P', 'ꞯ' to 'Q', 'ʀ' to 'R',
        'ꜱ' to 'S', 'ᴛ' to 'T', 'ᴜ' to 'U', 'ᴠ' to 'V', 'ᴡ' to 'W', 'ʏ' to 'Y', 'ᴢ' to 'Z',
    )

    /** Schnelltest: Nur Texte mit Zeichen außerhalb von ASCII müssen überhaupt bearbeitet werden. */
    private fun needsWork(text: String): Boolean = text.any { it.code > 0x7F }

    fun plain(text: String): String {
        if (!needsWork(text)) return text
        val sb = StringBuilder(text.length)
        var i = 0
        while (i < text.length) {
            val cp = text.codePointAt(i)
            i += Character.charCount(cp)
            when (cp) {
                // Negativ eingekreiste bzw. negativ eingerahmte Buchstaben (🅐 🅰) – ebenfalls ohne Zerlegung.
                in 0x1F150..0x1F169 -> sb.append('A' + (cp - 0x1F150))

                in 0x1F170..0x1F189 -> sb.append('A' + (cp - 0x1F170))

                else -> {
                    val ch = if (cp <= 0xFFFF) cp.toChar() else null
                    val mapped = ch?.let { smallCapitals[it] }
                    if (mapped != null) sb.append(mapped) else sb.appendCodePoint(cp)
                }
            }
        }
        return Normalizer.normalize(sb, Normalizer.Form.NFKC)
    }
}
