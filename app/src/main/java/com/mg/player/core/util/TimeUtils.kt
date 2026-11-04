package com.mg.player.core.util

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

object TimeUtils {

    /** 3723000 -> "1:02:03", 83000 -> "1:23". */
    fun formatDuration(ms: Long): String {
        val totalSec = (ms.coerceAtLeast(0) / 1000)
        val h = totalSec / 3600
        val m = (totalSec % 3600) / 60
        val s = totalSec % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
    }

    /** Je Thread ein Formatierer: Listen rufen das pro Zeile auf, ein neuer SimpleDateFormat kostet jedes Mal. */
    private val clockFormat = object : ThreadLocal<SimpleDateFormat>() {
        override fun initialValue() = SimpleDateFormat("HH:mm", Locale.GERMANY)
    }

    fun formatClock(ms: Long): String {
        // Zeitzone jedes Mal übernehmen: Stellt jemand die Zeitzone um, stimmt die Uhr sofort.
        val format = clockFormat.get()!!.apply { timeZone = TimeZone.getDefault() }
        return format.format(Date(ms))
    }

    fun formatDate(ms: Long): String = SimpleDateFormat("dd.MM.yyyy, HH:mm", Locale.GERMANY).format(Date(ms))

    /** 1536 -> "1,5 KB", 2147483648 -> "2,0 GB". */
    fun formatBytes(bytes: Long): String {
        val units = listOf("B", "KB", "MB", "GB", "TB")
        var value = bytes.toDouble()
        var index = 0
        while (value >= 1024 && index < units.lastIndex) {
            value /= 1024
            index++
        }
        return if (index == 0) "$bytes B" else String.format(Locale.GERMANY, "%.1f %s", value, units[index])
    }

    fun formatMinutes(seconds: Int): String {
        val h = seconds / 3600
        val m = (seconds % 3600) / 60
        return if (h > 0) "$h Std. $m Min." else "$m Min."
    }

    /**
     * Schnelles Parsen von XMLTV-Zeitstempeln ("20240101203000 +0100") ohne SimpleDateFormat,
     * weil große EPG-Dateien Hunderttausende Einträge enthalten.
     */
    fun parseXmltvTime(value: String?): Long? {
        if (value == null || value.length < 12) return null
        return try {
            val year = value.substring(0, 4).toInt()
            val month = value.substring(4, 6).toInt()
            val day = value.substring(6, 8).toInt()
            val hour = value.substring(8, 10).toInt()
            val minute = value.substring(10, 12).toInt()
            val hasSeconds = value.length >= 14 && value[12].isDigit() && value[13].isDigit()
            val second = if (hasSeconds) value.substring(12, 14).toInt() else 0
            var epochSec = daysFromCivil(year, month, day) * 86_400L + hour * 3600L + minute * 60L + second
            // Zeitzone steht nach den Sekunden – oder direkt nach den Minuten, wenn die Sekunden fehlen.
            // „+0100“ und „+01:00“ (manche Generatoren schreiben den Doppelpunkt) gleich behandeln.
            val offsetPart = value.substring(minOf(if (hasSeconds) 14 else 12, value.length)).trim().replace(":", "")
            if (offsetPart.length >= 5 && (offsetPart[0] == '+' || offsetPart[0] == '-')) {
                val sign = if (offsetPart[0] == '-') -1 else 1
                val oh = offsetPart.substring(1, 3).toInt()
                val om = offsetPart.substring(3, 5).toInt()
                epochSec -= sign * (oh * 3600L + om * 60L)
            }
            epochSec * 1000L
        } catch (e: NumberFormatException) {
            null
        } catch (e: StringIndexOutOfBoundsException) {
            null
        }
    }

    /** Tage seit 1970-01-01 (Algorithmus von Howard Hinnant). */
    private fun daysFromCivil(y0: Int, m: Int, d: Int): Long {
        val y = if (m <= 2) y0 - 1 else y0
        val era = (if (y >= 0) y else y - 399) / 400
        val yoe = y - era * 400
        val doy = (153 * (if (m > 2) m - 3 else m + 9) + 2) / 5 + d - 1
        val doe = yoe * 365 + yoe / 4 - yoe / 100 + doy
        return era * 146_097L + doe - 719_468L
    }
}
