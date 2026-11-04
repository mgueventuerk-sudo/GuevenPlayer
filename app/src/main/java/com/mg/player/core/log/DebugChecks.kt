package com.mg.player.core.log

import android.os.Build
import android.os.StrictMode
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/**
 * Nur in der Debug-APK: StrictMode meldet Datenträger- und Netzzugriffe auf dem Hauptthread sowie nicht
 * geschlossene Dateien/Cursor. Jede Fundstelle steht einmal im Fehlerprotokoll („StrictMode: …“), höchstens [MAX].
 * Die Release-APK ist davon nicht betroffen.
 */
object DebugChecks {
    private const val MAX = 40
    private const val LIBRARY_FRAMES = 3

    // Nicht ConcurrentHashMap.newKeySet(): erst ab Android 7 (minSdk ist 23).
    private val seen: MutableSet<String> = Collections.newSetFromMap(ConcurrentHashMap())

    fun install() {
        val thread = StrictMode.ThreadPolicy.Builder().detectDiskReads().detectDiskWrites().detectNetwork().penaltyLog()
        val vm = StrictMode.VmPolicy.Builder().detectLeakedClosableObjects().detectLeakedSqlLiteObjects().penaltyLog()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val executor = Executors.newSingleThreadExecutor { r -> Thread(r, "strictmode").apply { isDaemon = true } }
            thread.penaltyListener(executor) { record(it) }
            vm.penaltyListener(executor) { record(it) }
        }
        // Labor-Build: jeder Fund beendet die App mit Ablaufspur – so findet der Monkey-Lauf jeden Hauptthread-Zugriff.
        if (com.mg.player.BuildConfig.LAB_STRICT) thread.penaltyDeath()
        StrictMode.setThreadPolicy(thread.build())
        StrictMode.setVmPolicy(vm.build())
    }

    private fun frame(f: StackTraceElement) = "${f.className.substringAfterLast('.')}.${f.methodName}:${f.lineNumber}"

    private fun record(violation: Throwable) {
        // Erste Stelle im eigenen Code – daran erkennt man die Fundstelle.
        val own = violation.stackTrace.firstOrNull { it.className.startsWith("com.mg.player") }
        // Nicht geschlossene Dateien/Cursor entstehen oft in einer Bibliothek (OkHttp, Room). Dann die obersten
        // Fremdstellen nennen, sonst stand im Protokoll nur „unbekannt“ und die Suche begann bei null.
        val where = own?.let { frame(it) }
            ?: violation.stackTrace.take(LIBRARY_FRAMES).takeIf { it.isNotEmpty() }?.joinToString(" ← ", transform = ::frame)
            ?: "unbekannt"
        val key = "${violation.javaClass.simpleName}@$where"
        if (seen.size >= MAX || !seen.add(key)) return
        AppLog.log("StrictMode", "${violation.javaClass.simpleName} in $where")
    }
}
