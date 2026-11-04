package com.mg.player.data.remote

import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

/**
 * Baut Catch-up-Adressen (zeitversetztes Fernsehen) für Xtream und für M3U-Listen
 * mit catchup="default|append|shift|flussonic" und catchup-source-Vorlagen.
 */
object CatchupUrlBuilder {

    /** Pfadsegment kodieren (wie Uri.encode, aber ohne Android-Abhängigkeit – damit testbar). */
    private fun encode(value: String): String = java.net.URLEncoder.encode(value, "UTF-8").replace("+", "%20")

    /** Xtream: /timeshift/user/pass/<Minuten>/<yyyy-MM-dd:HH-mm in Server-Zeitzone>/<streamId>.ts */
    fun xtream(baseUrl: String, username: String, password: String, streamId: String, startMs: Long, endMs: Long, serverTimezone: String): String {
        val minutes = ((endMs - startMs + 59_999) / 60_000).coerceAtLeast(1)
        val format = SimpleDateFormat("yyyy-MM-dd:HH-mm", Locale.US).apply {
            timeZone = TimeZone.getTimeZone(serverTimezone)
        }
        return "$baseUrl/timeshift/${encode(username)}/${encode(password)}/$minutes/${format.format(startMs)}/$streamId.ts"
    }

    fun m3u(streamUrl: String, type: String?, source: String?, startMs: Long, endMs: Long, nowMs: Long): String? {
        val t = type?.lowercase(Locale.ROOT)
        return when (t) {
            "append" -> source?.let { streamUrl + fill(it, startMs, endMs, nowMs) }

            "shift", "timeshift" -> {
                val separator = if ('?' in streamUrl) '&' else '?'
                "$streamUrl${separator}utc=${startMs / 1000}&lutc=${nowMs / 1000}"
            }

            "flussonic", "flussonic-hls", "flussonic-ts", "fs" -> flussonic(streamUrl, startMs, endMs)

            else -> source?.let { fill(it, startMs, endMs, nowMs) }
        }
    }

    private fun flussonic(streamUrl: String, startMs: Long, endMs: Long): String {
        val base = streamUrl.substringBefore('?')
        val query = streamUrl.substringAfter('?', "")
        val dir = base.substringBeforeLast('/')
        val start = startMs / 1000
        val duration = (endMs - startMs) / 1000
        val file = if (base.endsWith(".m3u8")) "index-$start-$duration.m3u8" else "timeshift_abs-$start.ts"
        return "$dir/$file" + if (query.isNotEmpty()) "?$query" else ""
    }

    private val durationDivider = Regex("\\{duration:(\\d+)\\}")

    /** Ersetzt die gängigen Platzhalter aus Kodi/TiviMate-Vorlagen. */
    fun fill(template: String, startMs: Long, endMs: Long, nowMs: Long): String {
        val start = startMs / 1000
        val end = endMs / 1000
        val now = nowMs / 1000
        val duration = end - start
        val cal = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply { timeInMillis = startMs }
        fun two(v: Int) = v.toString().padStart(2, '0')
        var result = durationDivider.replace(template) { m ->
            val divider = m.groupValues[1].toLongOrNull()?.takeIf { it > 0 } ?: 1
            (duration / divider).toString()
        }
        val replacements = listOf(
            "\${start}" to start, "{start}" to start, "{utc}" to start, "\${timestamp}" to start,
            "\${end}" to end, "{end}" to end, "{utcend}" to end,
            "\${now}" to now, "{now}" to now, "{lutc}" to now,
            "\${duration}" to duration, "{duration}" to duration,
            "\${offset}" to now - start, "{offset}" to now - start,
        )
        for ((key, value) in replacements) result = result.replace(key, value.toString())
        result = result
            .replace("{Y}", cal.get(Calendar.YEAR).toString())
            .replace("{m}", two(cal.get(Calendar.MONTH) + 1))
            .replace("{d}", two(cal.get(Calendar.DAY_OF_MONTH)))
            .replace("{H}", two(cal.get(Calendar.HOUR_OF_DAY)))
            .replace("{M}", two(cal.get(Calendar.MINUTE)))
            .replace("{S}", two(cal.get(Calendar.SECOND)))
        return result
    }
}
