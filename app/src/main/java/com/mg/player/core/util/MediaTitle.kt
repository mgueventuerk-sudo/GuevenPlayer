package com.mg.player.core.util

import androidx.compose.runtime.Immutable
import java.util.Locale

/** Bereinigter Titel plus herausgelöste Qualitätsmerkmale ("4K", "HEVC" …) und Jahr. */
@Immutable
data class TitleInfo(val title: String, val badges: List<String>, val year: String?) {
    /** Auflösung für die Ecke von Poster bzw. Senderkachel: "4K", "FHD", "HD" oder "SD". */
    val resolution: String? get() = badges.firstOrNull { it in RESOLUTIONS }

    /** Bis zu zwei sichtbare Abzeichen, z. B. "4K" und "HEVC". */
    val visibleBadges: List<String> get() = badges.filter { it != MediaTitle.BACKUP }.take(2)

    private companion object {
        val RESOLUTIONS = setOf("8K", "4K", "FHD", "HD", "SD")
    }
}

/**
 * Smart Title Cleaning für Sender, Filme und Serien:
 * "|DE| Avatar (2009) [4K] [HEVC]" -> Titel "Avatar", Jahr 2009, Abzeichen 4K + HEVC.
 * "DE: ZDF UHD" -> "ZDF" (4K), "GE - Sky Bundesliga 1 FHD" -> "Sky Bundesliga 1" (FHD).
 * Kanalnummern und Regionalnamen bleiben erhalten ("WDR (Köln)" bleibt "WDR (Köln)").
 */
object MediaTitle {

    const val BACKUP = "BACKUP"

    /** Qualitäts-Token -> sichtbares Abzeichen (null = nur entfernen). */
    private val qualityTokens: Map<String, String?> = mapOf(
        "8K" to "8K", "4K" to "4K", "UHD" to "4K", "2160P" to "4K", "FHD" to "FHD", "FULLHD" to "FHD",
        "1080P" to "FHD", "1080I" to "FHD", "HD" to "HD", "720P" to "HD", "SD" to "SD", "576P" to "SD",
        "480P" to "SD", "HEVC" to "HEVC", "H265" to "HEVC", "H.265" to "HEVC", "X265" to "HEVC", "H264" to null,
        "H.264" to null, "RAW" to "RAW", "50FPS" to "50FPS", "60FPS" to "50FPS", "HDR" to "HDR", "HDR10" to "HDR",
        "HDR10+" to "HDR", "HQ" to null, "LQ" to null, "BACKUP" to BACKUP, "VIP" to null, "ORIG" to null,
    )

    private val bracket = Regex("\\[([^\\]]{0,40})]|\\(([^)]{0,40})\\)|\\|([^|]{1,12})\\|")
    private val year = Regex("(19|20)\\d{2}")
    private val leadingCode = Regex("^\\s*([A-Za-z]{2,7})\\s*[:|▎┃•\\-–—]+\\s*")
    private val leadingSpaceCode = Regex("^\\s*(DE|AT|CH|TR|AR|UK|FR|ES|NL|PL|GE|GER|EN)\\s+(?=[\\p{L}\\d])")

    /** Buchstaben-Tags nur in Großschrift ("Raw" als Filmtitel bleibt), Zahlen-Tags egal wie geschrieben. */
    private val upperTag = Regex("(?<![\\p{L}\\d])(UHD|FHD|FULLHD|HEVC|RAW|HDR10\\+?|HDR|HD|SD|HQ|LQ|BACKUP|VIP|ORIG)(?![\\p{L}\\d])")
    private val numericTag = Regex(
        "(?<![\\p{L}\\d])(4K|8K|2160P|1080P|1080I|720P|576P|480P|50FPS|60FPS|[HX]\\.?26[45])(?![\\p{L}\\d])",
        RegexOption.IGNORE_CASE,
    )
    private val separatorsAtEdges = Regex("^[\\s|:•▎┃\\-–—_]+|[\\s|:•▎┃\\-–—_]+$")
    private val multiSpace = Regex("\\s{2,}")
    private val tokenSplit = Regex("[\\s,+/]+")
    private val umlauts = mapOf('ä' to "ae", 'ö' to "oe", 'ü' to "ue", 'ß' to "ss")

    fun parse(raw: String): TitleInfo {
        val badges = LinkedHashSet<String?>()
        var foundYear: String? = null
        // Hochgestellte Qualitätskürzel zuerst als eigene Wörter (UHD/FHD vor HD – sonst bliebe aus "ᵁᴴᴰ" nur "HD"),
        // danach Zierschriften der Anbieter ("𝗣𝗿𝗼𝗦𝗶𝗲𝗯𝗲𝗻", "ＺＤＦ") in normale Buchstaben umwandeln.
        var s = TextFold.plain(raw.replace("ᵁᴴᴰ", " UHD ").replace("ᶠᴴᴰ", " FHD ").replace("ᴴᴰ", " HD "))

        // 1) Klammern: nur Qualitäts-, Länder- oder Jahresangaben fliegen raus, alles andere bleibt.
        s = bracket.replace(s) { match ->
            val inner = match.groupValues.drop(1).firstOrNull { it.isNotEmpty() }?.trim().orEmpty()
            when {
                year.matches(inner) -> {
                    foundYear = inner
                    " "
                }

                inner.isEmpty() -> " "

                else -> {
                    val tokens = inner.split(tokenSplit).filter { it.isNotEmpty() }
                    val classified = tokens.map { token -> classify(token) }
                    if (classified.all { it != null }) {
                        classified.forEach { tag -> if (tag is Tag.Quality) badges += tag.badge }
                        " "
                    } else {
                        match.value
                    }
                }
            }
        }
        // 2) Länderkürzel vorne: "DE: ", "GE - ", "TR | ", "DE " …
        leadingCode.find(s)?.let { m -> if (CountryDetector.codeFor(m.groupValues[1]) != null) s = s.substring(m.range.last + 1) }
        leadingSpaceCode.find(s)?.let { m -> s = s.substring(m.range.last + 1) }
        // 3) Freistehende Qualitäts-Tags.
        s = upperTag.replace(s) { m ->
            badges += qualityTokens[m.value.uppercase(Locale.ROOT)]
            " "
        }
        s = numericTag.replace(s) { m ->
            badges += qualityTokens[m.value.uppercase(Locale.ROOT)]
            " "
        }
        // 4) Aufräumen.
        s = multiSpace.replace(s, " ")
        s = separatorsAtEdges.replace(s, "")
        val title = s.ifBlank { raw.trim() }
        return TitleInfo(title, order(badges.filterNotNull()), foundYear)
    }

    /** Rang für die Stream-Reihenfolge innerhalb eines Senders (kleiner = besser). */
    fun qualityRank(badges: Collection<String>): Int {
        val base = when {
            "8K" in badges || "4K" in badges -> 0
            "RAW" in badges || "50FPS" in badges -> 10
            "FHD" in badges && "HEVC" in badges -> 20
            "FHD" in badges -> 30
            "HD" in badges -> 40
            "SD" in badges -> 60
            else -> 50
        }
        return if (BACKUP in badges) base + 5 else base
    }

    /**
     * Schlüssel zum Zusammenfassen von Sender-Varianten: Land + bereinigter Name ohne Satzzeichen.
     * "DE: ZDF UHD" und "DE: ZDF HD" -> "DE:zdf"; "Sky Bundesliga 1" ≠ "Sky Bundesliga 2".
     */
    fun groupKey(cleanTitle: String, country: String?): String {
        val sb = StringBuilder(cleanTitle.length)
        for (ch in cleanTitle.lowercase(Locale.ROOT)) {
            val replacement = umlauts[ch]
            when {
                replacement != null -> sb.append(replacement)
                ch.isLetterOrDigit() -> sb.append(ch)
                ch == '+' -> sb.append("plus")
            }
        }
        return "${country.orEmpty()}:$sb"
    }

    private sealed interface Tag {
        data class Quality(val badge: String?) : Tag
        data object Country : Tag
    }

    private fun classify(token: String): Tag? {
        val upper = token.uppercase(Locale.ROOT)
        if (upper in qualityTokens) return Tag.Quality(qualityTokens[upper])
        if (CountryDetector.codeFor(upper) != null) return Tag.Country
        return null
    }

    private fun order(badges: List<String>): List<String> {
        val priority = listOf("8K", "4K", "FHD", "HD", "SD", "RAW", "50FPS", "HDR", "HEVC", BACKUP)
        return badges.distinct().sortedBy { priority.indexOf(it).let { i -> if (i < 0) priority.size else i } }
    }
}
