package com.mg.player.data.tmdb

import java.util.Calendar
import java.util.Locale

/**
 * Macht aus IPTV-Titeln wie "DE: Der Pate (1972) FHD" oder "Dune.Part.Two.2024.MULTI.1080p"
 * einen suchbaren Titel ("Der Pate", "Dune Part Two") plus Jahr.
 */
object TitleCleaner {

    data class Cleaned(val title: String, val year: String?)

    private val prefix = Regex("^\\s*(\\|[^|]{1,8}\\||\\[[^\\]]{1,8}]|[A-Z]{2,3}\\s*[:|]\\s*|[A-Z]{2}\\s+-\\s+)")
    private val whitespace = Regex("\\s+")
    private val parenYear = Regex("[(\\[]((?:19|20)\\d{2})[)\\]]")
    private val releaseYear = Regex("[._]((?:19|20)\\d{2})(?=[._]|$)")
    private val trailingYear = Regex("\\s((?:19|20)\\d{2})\\s*$")
    private val brackets = Regex("[(\\[][^)\\]]*[)\\]]")
    private val junk = setOf(
        "4k", "uhd", "fhd", "hd", "sd", "hdr", "hdr10", "dv", "multi", "multisub", "multi-sub", "dual", "german", "deutsch",
        "dl", "ac3", "dts", "x264", "x265", "h264", "h265", "hevc", "1080p", "720p", "2160p", "480p", "web", "webrip",
        "web-dl", "bluray", "bdrip", "dvdrip", "remux", "imax", "vod", "ger", "eng", "sub", "subbed", "lq", "hq",
    )

    fun clean(raw: String): Cleaned {
        var s = prefix.replace(raw, "")
        var year: String? = null

        parenYear.find(s)?.let { match ->
            year = match.groupValues[1]
            s = s.substring(0, match.range.first)
        }
        if (year == null) {
            releaseYear.find(s)?.takeIf { plausible(it.groupValues[1]) }?.let { match ->
                year = match.groupValues[1]
                s = s.substring(0, match.range.first)
            }
        }
        if (year == null) {
            trailingYear.find(s)?.takeIf { plausible(it.groupValues[1]) }?.let { match ->
                year = match.groupValues[1]
                s = s.substring(0, match.range.first)
            }
        }
        s = brackets.replace(s, " ").replace('.', ' ').replace('_', ' ')
        val words = s.split(whitespace).filter { it.isNotBlank() && it.lowercase(Locale.ROOT) !in junk }
        val title = words.joinToString(" ").trim().trimEnd('-', ':', '|', ',').trim()
        return Cleaned(title.ifBlank { raw.trim() }, year)
    }

    /** Für den Vergleich von Titeln: nur Buchstaben/Ziffern, Umlaute ausgeschrieben. */
    fun normalize(title: String): String {
        val sb = StringBuilder()
        for (ch in title.lowercase(Locale.ROOT)) {
            when (ch) {
                'ä' -> sb.append("ae")
                'ö' -> sb.append("oe")
                'ü' -> sb.append("ue")
                'ß' -> sb.append("ss")
                else -> if (ch.isLetterOrDigit()) sb.append(ch)
            }
        }
        return sb.toString()
    }

    private fun plausible(year: String): Boolean {
        val y = year.toIntOrNull() ?: return false
        return y in 1900..(Calendar.getInstance().get(Calendar.YEAR) + 1)
    }
}
