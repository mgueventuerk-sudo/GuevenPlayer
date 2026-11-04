package com.mg.player.core.log

import android.os.Process
import android.os.SystemClock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Messpunkt „Start bis Startbildschirm“.
 *
 * Nur bei einem echten Kaltstart ist der Prozessstart der richtige Bezug. Läuft der Prozess schon (das VPN hält
 * ihn als Dienst tagelang wach) und der Nutzer öffnet die App später, misst der Prozessstart das Alter des
 * Prozesses: Im Fire-TV-Protokoll standen deshalb Werte wie 86 606 484 ms (24 Stunden). Dann zählt die Zeit ab
 * dem Öffnen der Oberfläche ([uiCreated]).
 */
object StartupClock {

    /** Prozess läuft schon länger als das: Die Oberfläche wurde nicht beim Start geöffnet. */
    private const val COLD_WINDOW_MS = 15_000L
    private const val MINUTE_MS = 60_000L
    private const val START_WINDOW_MS = 20_000L

    @Volatile
    private var uiCreatedAt = 0L

    private val contentShown = MutableStateFlow(false)

    /** Erster Inhalt steht (reportFullyDrawn). Ab hier darf Hintergrundarbeit des Starts laufen. */
    fun contentShown() {
        contentShown.value = true
    }

    /**
     * Wartet, bis der erste Inhalt steht – höchstens [maxWaitMs] (Einrichtung, leere Playlist: dann trotzdem los).
     * Labor 3.23: Sport (10 Ligen parallel), Suchindex-Prüfung, DNS-Probe und WorkManager liefen im Startfenster
     * parallel zum ersten Bild; „Inhalt sichtbar“ kam im Emulator erst nach 23 s.
     */
    suspend fun awaitContentShown(maxWaitMs: Long = START_WINDOW_MS) {
        withTimeoutOrNull(maxWaitMs) { contentShown.first { it } }
    }

    /** Früh aufrufen (Application.onCreate). */
    fun touch() = Unit

    /** Die Oberfläche (Activity) entsteht – ab hier zählt bei einem warmen Start die Zeit. */
    fun uiCreated() {
        uiCreatedAt = SystemClock.elapsedRealtime()
    }

    fun sinceProcessStartMs(): Long = SystemClock.elapsedRealtime() - Process.getStartElapsedRealtime()

    /** Fertige Protokollzeile (erstes Bild, TTID) – unterscheidet Kaltstart und schon laufenden Prozess. */
    fun startupLine(): String = line("Startbildschirm")

    /**
     * Inhalt sichtbar (TTFD, reportFullyDrawn): erste Kategorien bzw. Startseiten-Reihen sind geladen. Erst dieser
     * Wert sagt, wann man wirklich etwas auswählen kann – das erste Bild ist oft nur der leere Rahmen.
     */
    fun fullyDrawnLine(): String = line("Inhalt sichtbar")

    private fun line(target: String): String {
        val ui = uiCreatedAt
        val processAge = sinceProcessStartMs()
        if (ui <= 0L) return "Start bis $target: $processAge ms"
        val uiAge = SystemClock.elapsedRealtime() - ui
        val processWhenUiStarted = processAge - uiAge
        return if (processWhenUiStarted <= COLD_WINDOW_MS) {
            "Start bis $target: $processAge ms"
        } else {
            "Oberfläche bis $target: $uiAge ms (Prozess lief schon ${processWhenUiStarted / MINUTE_MS} min)"
        }
    }
}
