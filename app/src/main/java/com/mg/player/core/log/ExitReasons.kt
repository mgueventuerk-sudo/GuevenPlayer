package com.mg.player.core.log

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.core.content.edit

/**
 * Wie endete der letzte Lauf? Ab Android 11 meldet das System auch Fälle, die kein Fehlerfänger sieht:
 * "App reagiert nicht" (ANR) und Abstürze im nativen Code (z. B. LibVLC). Jeder Fall wird nur einmal gemeldet.
 */
object ExitReasons {

    private const val PREFS = "exit_reasons"
    private const val KEY_SEEN = "last_seen_ms"
    private const val HISTORY = 5

    /** Zeitstempel des neuesten unerwarteten Endes aus (Zeit, Grund, Wichtigkeit), null = keins. */
    @RequiresApi(Build.VERSION_CODES.R)
    internal fun pickUnexpected(exits: List<Triple<Long, Int, Int>>): Long? =
        exits.sortedByDescending { it.first }.firstOrNull { (_, reason, importance) -> describe(reason, importance) != null }?.first

    /** Beschreibung des letzten unerwarteten Endes (mit Ablaufspur, falls vorhanden) oder null. */
    fun takeUnexpectedExit(context: Context): String? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        val manager = context.getSystemService(ActivityManager::class.java) ?: return null
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val seen = prefs.getLong(KEY_SEEN, 0L)
        // Die letzten 5 Enden: Ein ANR, auf den ein normales Ende (z. B. Aufnahme-Start im Hintergrund) folgt, soll
        // trotzdem gemeldet werden – mit nur dem letzten Eintrag ging er verloren.
        val recent = runCatching { manager.getHistoricalProcessExitReasons(context.packageName, 0, HISTORY) }
            .getOrNull().orEmpty().filter { it.timestamp > seen }
        if (recent.isEmpty()) return null
        prefs.edit { putLong(KEY_SEEN, recent.maxOf { it.timestamp }) }
        val last = pickUnexpected(recent.map { Triple(it.timestamp, it.reason, it.importance) })
            ?.let { pick -> recent.first { it.timestamp == pick } } ?: return null
        val kind = describe(last.reason, last.importance) ?: return null
        val trace = runCatching {
            last.traceInputStream?.use { input ->
                if (last.reason == ApplicationExitInfo.REASON_CRASH_NATIVE) NativeTombstone.summarize(input)
                else input.bufferedReader().readText().take(TRACE_CHARS)
            }
        }.getOrNull()
        val text = "$kind${last.description?.let { " – $it" }.orEmpty()}\n${trace?.take(TRACE_CHARS).orEmpty()}".trim()
        AppLog.log("Ende", text.lineSequence().first())
        return text
    }

    /**
     * Nur echte Probleme zählen – normales Beenden, Wischen aus der Übersicht usw. nicht. Speichermangel nur, wenn
     * die App dabei im Vordergrund lief: Im Hintergrund beendet Android Apps ständig, das ist kein Fehler.
     */
    @RequiresApi(Build.VERSION_CODES.R)
    internal fun describe(reason: Int, importance: Int): String? = when (reason) {
        ApplicationExitInfo.REASON_ANR -> "App reagierte nicht (ANR)"

        ApplicationExitInfo.REASON_CRASH -> "Absturz"

        ApplicationExitInfo.REASON_CRASH_NATIVE -> "Absturz im nativen Code"

        ApplicationExitInfo.REASON_INITIALIZATION_FAILURE -> "Start fehlgeschlagen"

        ApplicationExitInfo.REASON_LOW_MEMORY ->
            if (importance <= ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND) {
                "Vom System wegen Speichermangel beendet"
            } else {
                null
            }

        else -> null
    }

    private const val TRACE_CHARS = 20_000
}
