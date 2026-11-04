package com.mg.player.data.parser

import java.io.BufferedReader
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader

/**
 * Streaming-M3U-Parser: liest Zeile für Zeile über einen BufferedReader,
 * hält nie die ganze Playlist im Speicher (auch bei >50.000 Einträgen).
 */
object M3uParser {

    data class Entry(
        val name: String,
        val url: String,
        val logo: String?,
        val group: String,
        val tvgId: String,
        val tvgName: String?,
        val catchupType: String? = null,
        val catchupDays: Int = 0,
        val catchupSource: String? = null,
        /** HTTP-Header aus #EXTVLCOPT / #EXTHTTP (z. B. User-Agent, Referer). */
        val headers: Map<String, String> = emptyMap(),
    )

    /** Catch-up-Vorgaben aus dem #EXTM3U-Kopf, die für alle Einträge gelten. */
    private data class CatchupDefaults(val type: String?, val days: Int, val source: String?)

    /** Byte-Order-Mark am Dateianfang (manche Windows-Editoren schreiben sie). */
    private const val BOM = "\uFEFF"

    /** Längere Zeilen gibt es in einer echten M3U nicht – das ist eine Fehlerseite oder Binärdaten. */
    internal const val MAX_LINE = 64 * 1024

    /** Ab so vielen verworfenen Zeilen ist es keine M3U: abbrechen statt weiterzulesen. */
    internal const val MAX_DROPPED = 100

    const val NOT_M3U = "Die Antwort des Anbieters ist keine gültige M3U-Liste (Fehlerseite oder beschädigte Datei)."

    private val attributeRegex = Regex("([A-Za-z0-9_-]+)=\"([^\"]*)\"")

    suspend fun parse(input: InputStream, onHeaderEpg: suspend (String) -> Unit, onEntry: suspend (Entry) -> Unit) {
        BufferedReader(InputStreamReader(input, Charsets.UTF_8), 64 * 1024).use { reader ->
            var pendingInfo: String? = null
            var pendingGroup: String? = null
            val pendingHeaders = LinkedHashMap<String, String>()
            var defaults = CatchupDefaults(null, 0, null)
            val scanner = LineScanner(reader)
            var dropped = 0
            while (true) {
                // readLine() hätte eine Datei ohne Zeilenumbruch komplett in den Speicher geladen (OOM).
                val raw = when (scanner.next()) {
                    LineResult.END -> break

                    LineResult.TOO_LONG -> {
                        if (++dropped >= MAX_DROPPED) throw IOException(NOT_M3U)
                        continue
                    }

                    LineResult.OK -> scanner.line.toString()
                }
                val line = raw.trim().removePrefix(BOM)
                when {
                    line.isEmpty() -> Unit

                    line.startsWith("#EXTM3U", ignoreCase = true) -> {
                        val attrs = attributes(line)
                        val epg = attrs["url-tvg"] ?: attrs["x-tvg-url"] ?: attrs["tvg-url"]
                        if (!epg.isNullOrBlank()) onHeaderEpg(epg.split(',').first().trim())
                        defaults = CatchupDefaults(
                            type = attrs["catchup"]?.takeIf { it.isNotBlank() },
                            days = attrs["catchup-days"]?.toIntOrNull() ?: 0,
                            source = attrs["catchup-source"]?.takeIf { it.isNotBlank() },
                        )
                    }

                    line.startsWith("#EXTINF", ignoreCase = true) -> {
                        pendingInfo = line
                        pendingGroup = null
                        pendingHeaders.clear()
                    }

                    line.startsWith("#EXTVLCOPT:", ignoreCase = true) -> vlcOption(line.substringAfter(':'))?.let { (k, v) -> pendingHeaders[k] = v }

                    line.startsWith("#EXTHTTP:", ignoreCase = true) -> pendingHeaders.putAll(httpJson(line.substringAfter(':')))

                    line.startsWith("#EXTGRP:", ignoreCase = true) -> pendingGroup = line.substringAfter(':').trim()

                    line.startsWith("#") -> Unit

                    else -> {
                        val info = pendingInfo
                        if (info != null) onEntry(buildEntry(info, pendingGroup, line, defaults).copy(headers = HashMap(pendingHeaders)))
                        pendingInfo = null
                        pendingGroup = null
                        pendingHeaders.clear()
                    }
                }
            }
        }
    }

    internal enum class LineResult { OK, TOO_LONG, END }

    /**
     * Zeilenleser mit Längengrenze und eigenem Puffer (BufferedReader.read() pro Zeichen wäre synchronisiert und
     * langsam). Zeilen über [MAX_LINE] Zeichen werden bis zum Zeilenende überlesen, ohne sie zu speichern.
     */
    internal class LineScanner(private val reader: java.io.Reader) {
        private val chars = CharArray(64 * 1024)
        private var pos = 0
        private var limit = 0
        private var skipLf = false
        val line = StringBuilder(512)

        private fun fill(): Boolean {
            limit = reader.read(chars)
            pos = 0
            return limit > 0
        }

        fun next(): LineResult {
            line.setLength(0)
            var tooLong = false
            var any = false
            while (true) {
                if (pos >= limit && !fill()) {
                    return if (!any) {
                        LineResult.END
                    } else if (tooLong) {
                        LineResult.TOO_LONG
                    } else {
                        LineResult.OK
                    }
                }
                val c = chars[pos++]
                if (skipLf) {
                    skipLf = false
                    if (c == '\n') continue
                }
                any = true
                when (c) {
                    '\n' -> return if (tooLong) LineResult.TOO_LONG else LineResult.OK

                    '\r' -> {
                        skipLf = true
                        return if (tooLong) LineResult.TOO_LONG else LineResult.OK
                    }

                    else -> if (!tooLong) {
                        if (line.length >= MAX_LINE) {
                            tooLong = true
                            line.setLength(0)
                        } else {
                            line.append(c)
                        }
                    }
                }
            }
        }
    }

    private fun buildEntry(info: String, extGroup: String?, url: String, defaults: CatchupDefaults): Entry {
        val attrs = attributes(info)
        val catchupType = attrs["catchup"]?.takeIf { it.isNotBlank() } ?: defaults.type
        val catchupDays = attrs["catchup-days"]?.toIntOrNull() ?: attrs["tvg-rec"]?.toIntOrNull() ?: defaults.days
        val name = displayName(info).ifBlank { attrs["tvg-name"].orEmpty() }.ifBlank { "Unbenannt" }
        return Entry(
            name = name,
            url = url,
            logo = attrs["tvg-logo"]?.takeIf { it.isNotBlank() },
            group = (attrs["group-title"] ?: extGroup)?.takeIf { it.isNotBlank() } ?: "Sonstige",
            tvgId = attrs["tvg-id"].orEmpty(),
            tvgName = attrs["tvg-name"],
            catchupType = catchupType,
            catchupDays = if (catchupType != null) catchupDays.coerceAtLeast(1) else 0,
            catchupSource = attrs["catchup-source"]?.takeIf { it.isNotBlank() } ?: defaults.source,
        )
    }

    /** `#EXTVLCOPT:http-user-agent=Foo` -> ("User-Agent", "Foo"); andere VLC-Optionen werden ignoriert. */
    private fun vlcOption(option: String): Pair<String, String>? {
        val name = option.substringBefore('=').trim().lowercase()
        val value = option.substringAfter('=', "").trim().removeSurrounding("\"")
        if (value.isEmpty()) return null
        return when (name) {
            "http-user-agent" -> "User-Agent" to value
            "http-referrer", "http-referer" -> "Referer" to value
            else -> null
        }
    }

    private val jsonPair = Regex("\"([^\"]+)\"\\s*:\\s*\"([^\"]*)\"")

    /** `#EXTHTTP:{"User-Agent":"Foo","Referer":"http://x"}` (flaches JSON, ohne Escapes). */
    private fun httpJson(json: String): Map<String, String> =
        jsonPair.findAll(json).associate { it.groupValues[1] to it.groupValues[2] }.filterValues { it.isNotBlank() }

    private fun attributes(line: String): Map<String, String> = attributeRegex.findAll(line).associate { it.groupValues[1].lowercase() to it.groupValues[2] }

    /** Der Name steht nach dem ersten Komma außerhalb von Anführungszeichen. */
    private fun displayName(info: String): String {
        var inQuotes = false
        for (i in info.indices) {
            val c = info[i]
            if (c == '"') inQuotes = !inQuotes
            if (c == ',' && !inQuotes) return info.substring(i + 1).trim()
        }
        return ""
    }
}

/** Ordnet M3U-Einträge Live-TV, Filmen oder Serien zu und erkennt S01E01-Muster. */
object M3uClassifier {

    enum class Kind { LIVE, MOVIE, SERIES }

    data class SeriesInfo(val seriesName: String, val season: Int, val episode: Int)

    private val videoExtensions = setOf("mkv", "mp4", "avi", "mov", "wmv", "m4v", "mpg", "mpeg", "flv", "webm")
    private val episodeRegex = Regex("(?i)^(.*?)[\\s._\\-|]*S(\\d{1,2})[\\s._\\-]*E(\\d{1,3})")
    private val altEpisodeRegex = Regex("(?i)^(.*?)[\\s._\\-|]+(\\d{1,2})x(\\d{1,3})\\b")
    private val yearRegex = Regex("[(\\[]((19|20)\\d{2})[)\\]]")

    fun classify(entry: M3uParser.Entry, extension: String): Kind {
        val url = entry.url.lowercase()
        return when {
            "/series/" in url -> Kind.SERIES
            "/movie/" in url -> Kind.MOVIE
            "/live/" in url -> Kind.LIVE
            extension in videoExtensions -> if (seriesInfo(entry.name) != null) Kind.SERIES else Kind.MOVIE
            else -> Kind.LIVE
        }
    }

    fun seriesInfo(name: String): SeriesInfo? {
        val match = episodeRegex.find(name) ?: altEpisodeRegex.find(name) ?: return null
        val seriesName = match.groupValues[1].trim().trimEnd('-', '|', ':').trim()
        val season = match.groupValues[2].toIntOrNull() ?: return null
        val episode = match.groupValues[3].toIntOrNull() ?: return null
        return SeriesInfo(seriesName.ifBlank { name }, season, episode)
    }

    fun year(name: String): String? = yearRegex.find(name)?.groupValues?.get(1)
}
