package com.mg.player.core

import android.app.Application
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.work.Configuration
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.disk.DiskCache
import coil3.disk.directory
import coil3.memory.MemoryCache
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import coil3.request.crossfade
import com.mg.player.BuildConfig
import com.mg.player.core.device.DeviceTier
import com.mg.player.core.device.PreloadPolicy
import com.mg.player.core.di.AppContainer
import com.mg.player.core.log.AppLog
import com.mg.player.core.log.DebugChecks
import com.mg.player.core.log.StartupClock
import com.mg.player.core.security.SecretCipher
import com.mg.player.data.sport.proof.ProofAlarm
import com.mg.player.data.sync.AutoRefreshWork
import com.mg.player.data.sync.AutoRefresher
import com.mg.player.domain.model.MatchSource
import com.mg.player.ui.common.ImagePrefetcher
import com.mg.player.ui.common.ImageUsage
import com.wireguard.android.backend.GoBackend
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

open class PlayerApp :
    Application(),
    SingletonImageLoader.Factory,
    Configuration.Provider {

    /** WorkManager startet bei Bedarf (erster Aufruf von WorkManager.getInstance), nicht in bindApplication. */
    override val workManagerConfiguration: Configuration get() = Configuration.Builder().build()

    lateinit var container: AppContainer
        private set

    protected open fun createContainer() = AppContainer(this)

    override fun onCreate() {
        super.onCreate()
        StartupClock.touch()
        // StrictMode zuerst: Vorher lagen AppLog.init, SecretCipher.init und der AppContainer-Konstruktor in einem
        // Fenster, das der Labor-Build (-PlabStrict=true, penaltyDeath) nicht sehen konnte - genau dort steckte der
        // mkdirs()-Zugriff von AppLog. DebugChecks.record() protokolliert ueber AppLog, das vertraegt ein noch
        // nicht gesetztes Ziel (write() bricht dann nach Log.i ab).
        if (BuildConfig.DEBUG) DebugChecks.install()
        AppLog.init(this)
        SecretCipher.init(this)
        container = createContainer()
        // Alles, was Dateien, Einstellungen oder die Datenbank anfasst, läuft im Hintergrund: Auf dem Fire TV
        // (Android 9) standen 24 StrictMode-Meldungen im Startpfad, der erste Bildschirm kam erst nach ~15 s.
        container.appScope.launch(Dispatchers.IO) {
            // Seit 3.32 gilt die EPG-Zeitverschiebung je Zugang: früheren gemeinsamen Wert einmal übertragen.
            // Nur wenn nötig – sonst würde die Datenbank hier schon im Startfenster geöffnet.
            runCatching { if (container.settingsRepository.current().epgShiftHours != 0) container.epgShifts.adoptLegacy() }
                .onFailure { AppLog.log("EPG", "Zeitverschiebung nicht übertragen: ${it.javaClass.simpleName}") }
            // Spiel-Finder: Wecker für die Beweissuche im Hintergrund (nur auf dem Prüfgerät) und Austausch im Heimnetz.
            // Wecker neu stellen, sobald „Dieses Gerät prüft im Hintergrund“ umgeschaltet wird (auch beim Start).
            // Beides im Hintergrund: Der App-Bereich läuft sonst im Haupt-Thread (Schlüsselableitung, Socket, Netzwerkdienst).
            container.appScope.launch(Dispatchers.IO) {
                container.settingsRepository.settings.map { it.sportProofDevice }.distinctUntilChanged()
                    .collect { ProofAlarm.scheduleNext(this@PlayerApp) }
            }
            container.appScope.launch(Dispatchers.IO) { container.sport.shareLoop() }
            // "Auf dem Fernseher weiterschauen": lauschen, sobald der Schalter an ist.
            container.appScope.launch(Dispatchers.IO) { container.handoffLoop() }
            // Kalte Starts zaehlen: Daraus folgt, ob die Einfuehrungstexte noch gezeigt werden (AppSettings.showHints).
            runCatching { container.settingsRepository.update { it.copy(coldStarts = (it.coldStarts + 1).coerceAtMost(HINT_CAP)) } }
                .onFailure { AppLog.log("Start", "Startzaehler nicht erhoeht: ${it.javaClass.simpleName}") }
            // Beweise und Trefferquoten (kleine Einstellungsdateien) hier lesen, nicht beim ersten Aufbau der Oberfläche.
            container.sport.proofs.recent()
            container.sport.stats.overallPercent(MatchSource.STATION)
            // Gedächtnis des Wächters (bis 2 000 Quellen) hier lesen, nicht beim ersten Öffnen des Players im Haupt-Thread.
            container.playback.sourceHealth.penalty("")
            // Aufnahmen: Unterbrochene markieren und geplante Aufnahmen wieder einplanen.
            container.recordingManager.awaitReady()
            // Erinnerungen: Wecker nach Neustart/Update neu stellen.
            container.reminders.rescheduleAll()
            // VPN: läuft ab App-Start dauerhaft (sofern eingerichtet). Startet Android den Dienst über „Durchgehend
            // aktives VPN“ selbst, wird ebenfalls verbunden.
            container.vpn.start()
            // Erst nach dem ersten Inhalt (Labor 3.23): WorkManager-Start und DNS-Probe liefen sonst im Startfenster.
            StartupClock.awaitContentShown()
            // Nachts im Leerlauf: EPG erneuern und Datenbank verkleinern (WorkManager, nur WLAN/LAN).
            runCatching { com.mg.player.data.sync.NightlyWorker.schedule(this@PlayerApp) }
                .onFailure { AppLog.log("Nacht", "Pflege nicht eingeplant: ${it.javaClass.simpleName}") }
            // Automatische Sicherung: harmlos, auch ohne gewählten Ordner – der Worker prüft das selbst und
            // tut dann nichts. Neu einplanen, falls WorkManagers eigener Zeitplan den Neustart nicht überlebt hat.
            runCatching { com.mg.player.data.backup.AutoBackupWorker.schedule(this@PlayerApp) }
                .onFailure { AppLog.log("Sicherung", "Automatische Sicherung nicht eingeplant: ${it.javaClass.simpleName}") }
            container.warmUpDns()
            // Automatische Aktualisierung je Zugang: sofort prüfen (Countdown lief auch, während die App aus war).
            container.autoRefresher.watch(container.appScope, container.playlistRepository.observePlaylists()) { next ->
                runCatching { AutoRefreshWork.plan(this@PlayerApp, next) }
                    .onFailure { AppLog.log(AutoRefresher.TAG, "Hintergrundlauf nicht eingeplant: ${it.javaClass.simpleName}") }
            }
        }
        container.startEpgWatch()
        container.watchTunnel()
        container.watchGroupSort()
        // „Ohne Verlauf“ schaltet auch das Aktivitätsprotokoll leise.
        container.appScope.launch {
            container.settingsRepository.settings.map { it.noHistory }.distinctUntilChanged().collect { AppLog.quiet = it }
        }
        // „Vorladen“ (Einstellungen → Wiedergabe): hier nur der Schalter; ob wirklich geladen wird, entscheidet der freie Speicher.
        container.appScope.launch {
            container.settingsRepository.settings.map { it.preload }.distinctUntilChanged().collect { PreloadPolicy.enabled = it }
        }
        GoBackend.setAlwaysOnCallback { container.appScope.launch { container.vpn.wake() } }
        // Kindersicherung: Freischaltung endet, sobald die App in den Hintergrund geht (Home-Taste, Standby).
        ProcessLifecycleOwner.get().lifecycle.addObserver(
            LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_STOP) {
                    container.parentalControl.lockAgain()
                    container.appLock.lock()
                }
            },
        )
    }

    /** Wenig Speicher (schwache TV-Sticks): Bilder im Arbeitsspeicher freigeben, bevor Android die App beendet. */
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        @Suppress("DEPRECATION")
        if (level >= android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) {
            PreloadPolicy.onTrim()
            runCatching { SingletonImageLoader.get(this).memoryCache?.clear() }
            // Der Bild-Cache ist leer: das Buch über Vorgeladenes im Speicher mit leeren.
            ImagePrefetcher.forgetAll()
            // LibVLC nur behalten, solange eine Wiedergabe sie nutzt.
            if (com.mg.player.player.engine.SharedLibVlc.trim()) AppLog.log("Speicher", "LibVLC freigegeben (wenig Speicher)")
        }
    }

    /** Bild-Cache für Poster und Senderlogos – schont die schwachen TV-Prozessoren beim Scrollen. */
    override fun newImageLoader(context: PlatformContext): ImageLoader = ImageLoader.Builder(this)
        // Feste Obergrenze je Geräteklasse, nie mehr als 20 % des Heaps: Mit largeHeap waren das auf 2-GB-Geräten
        // rund 100 MB nur für Bilder, die Videopuffer und Hintergrund fehlten.
        .memoryCache {
            val heapShare = (Runtime.getRuntime().maxMemory() * 0.2).toLong()
            MemoryCache.Builder().maxSizeBytes(minOf(DeviceTier.of(this).imageCacheBytes, heapShare)).build()
        }
        .diskCache {
            DiskCache.Builder()
                .directory(cacheDir.resolve("images"))
                .maxSizeBytes(256L * 1024 * 1024)
                .build()
        }
        // Eigener OkHttp-Client wie bisher bei Coil 2; Cache-Header der Server ignoriert Coil 3 von sich aus
        // (früher respectCacheHeaders(false)).
        .components {
            add(OkHttpNetworkFetcherFactory())
            // Zählt, woher angezeigte Bilder kommen (Speicher/Platte/Netz) – Nachweis für „Vorladen“ im Fehlerprotokoll.
            add(ImageUsage)
        }
        .crossfade(false)
        .build()
}

/** Deckel fuer den Startzaehler: Mehr als die Schwelle muss nicht gespeichert werden. */
private const val HINT_CAP = 99
