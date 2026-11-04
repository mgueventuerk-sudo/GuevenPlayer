package com.mg.player.core.util

import java.util.Locale

/**
 * Bereinigt Sendernamen für das EPG-Fuzzy-Matching:
 * "DE: Das Erste HD" -> "daserste", "|UK| BBC One FHD" -> "bbcone".
 */
object NameNormalizer {

    private val countryPrefix = Regex("^\\s*(\\|[a-z]{2,4}\\||\\[[a-z]{2,4}]|[a-z]{2,4}\\s*:|[a-z]{2}\\s+-\\s+)\\s*")
    private val brackets = Regex("[\\[(][^\\])]*[\\])]")
    private val qualityTokens = setOf(
        "hd", "fhd", "uhd", "sd", "hq", "lq", "4k", "8k", "hevc", "h265", "h264", "raw",
        "50fps", "60fps", "1080p", "1080i", "720p", "2160p", "hdr", "backup", "orig", "vip",
    )
    private val umlauts = mapOf('ä' to "ae", 'ö' to "oe", 'ü' to "ue", 'ß' to "ss")

    fun normalize(name: String): String {
        var s = TextFold.plain(name).lowercase(Locale.ROOT)
        s = countryPrefix.replace(s, "")
        s = brackets.replace(s, " ")
        val sb = StringBuilder(s.length)
        for (ch in s) {
            val replacement = umlauts[ch]
            when {
                replacement != null -> sb.append(replacement)
                ch.isLetterOrDigit() -> sb.append(ch)
                ch == '+' -> sb.append(" plus ")
                else -> sb.append(' ')
            }
        }
        return sb.split(' ')
            .filter { it.isNotBlank() && it !in qualityTokens }
            .joinToString("")
    }

    /** "ARD.de" -> "ard", "DasErste.de" -> "daserste". */
    fun normalizeEpgId(id: String): String {
        val lower = id.lowercase(Locale.ROOT)
        val dot = lower.lastIndexOf('.')
        val base = if (dot > 0 && lower.length - dot in 3..4) lower.substring(0, dot) else lower
        return normalize(base)
    }
}
