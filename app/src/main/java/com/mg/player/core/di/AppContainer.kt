package com.mg.player.core.di

import android.content.Context
import com.mg.player.data.handoff.HandoffClient
import com.mg.player.data.handoff.HandoffServer
import com.mg.player.data.reminder.LaunchRequest
import com.mg.player.core.log.AppLog
import com.mg.player.core.network.HttpFetcher
import com.mg.player.core.network.NetworkMonitor
import com.mg.player.core.network.PrivateDns
import com.mg.player.core.security.AppLockState
import com.mg.player.core.security.ParentalControl
import com.mg.player.core.security.PrefsPinAttempts
import com.mg.player.core.security.PrefsPinBackup
import com.mg.player.core.util.DelayedAction
import com.mg.player.core.util.runCatchingCancellable
import com.mg.player.core.vpn.AndroidVpnAlerts
import com.mg.player.core.vpn.SurfsharkAccount
import com.mg.player.core.vpn.SurfsharkApi
import com.mg.player.core.vpn.VpnController
import com.mg.player.core.vpn.VpnImporter
import com.mg.player.core.vpn.VpnState
import com.mg.player.core.vpn.VpnStore
import com.mg.player.core.vpn.WireGuardBackend
import com.mg.player.core.vpn.tunnelUps
import com.mg.player.data.backup.BackupManager
import com.mg.player.data.combined.Combined
import com.mg.player.data.combined.SortPolicy
import com.mg.player.data.combined.VariantUnifier
import com.mg.player.data.db.AppDatabase
import com.mg.player.data.filter.CountryFilter
import com.mg.player.data.importer.EpgImporter
import com.mg.player.data.importer.EpgSchedule
import com.mg.player.data.importer.FallbackEpgGate
import com.mg.player.data.importer.M3uImporter
import com.mg.player.data.importer.PrefsLongStore
import com.mg.player.data.importer.XtreamImporter
import com.mg.player.data.qr.QrSetupServer
import com.mg.player.data.recording.AutoRecorder
import com.mg.player.data.recording.RecordingManager
import com.mg.player.data.recording.StreamRecorder
import com.mg.player.data.reminder.ReminderManager
import com.mg.player.data.remote.AccountVerifier
import com.mg.player.data.remote.ConnectionMonitor
import com.mg.player.data.remote.StreamUrlResolver
import com.mg.player.data.remote.XtreamApi
import com.mg.player.data.repository.CategoryRepositoryImpl
import com.mg.player.data.repository.ChannelGroupIndex
import com.mg.player.data.repository.ChannelHabits
import com.mg.player.data.repository.CounterpartFinder
import com.mg.player.data.repository.EpgRepositoryImpl
import com.mg.player.data.repository.FavoriteOrderRepository
import com.mg.player.data.repository.LiveRepositoryImpl
import com.mg.player.data.repository.PlaybackRepositoryImpl
import com.mg.player.data.repository.PlaylistRepositoryImpl
import com.mg.player.data.repository.SearchRepositoryImpl
import com.mg.player.data.repository.SeriesRepositoryImpl
import com.mg.player.data.repository.VodRepositoryImpl
import com.mg.player.data.search.SearchIndexer
import com.mg.player.data.settings.EpgShifts
import com.mg.player.data.settings.SettingsCorruption
import com.mg.player.data.settings.SettingsRepositoryImpl
import com.mg.player.data.sync.AutoRefresher
import com.mg.player.data.sync.SyncManager
import com.mg.player.data.tmdb.TmdbApi
import com.mg.player.data.tmdb.TmdbRepositoryImpl
import com.mg.player.data.update.AppUpdater
import com.mg.player.domain.repository.CategoryRepository
import com.mg.player.domain.repository.EpgRepository
import com.mg.player.domain.repository.LiveRepository
import com.mg.player.domain.repository.PlaybackRepository
import com.mg.player.domain.repository.PlaylistRepository
import com.mg.player.domain.repository.SearchRepository
import com.mg.player.domain.repository.SeriesRepository
import com.mg.player.domain.repository.SettingsRepository
import com.mg.player.domain.repository.TmdbRepository
import com.mg.player.domain.repository.VodRepository
import com.mg.player.domain.usecase.AddPlaylistUseCase
import com.mg.player.domain.usecase.RefreshPlaylistUseCase
import com.mg.player.domain.usecase.ResolveNextEpisodeUseCase
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.io.File

/**
 * Manuelle Dependency Injection (kein Hilt/Dagger): Alle Abhängigkeiten werden hier
 * einmalig und lazy erzeugt.
 */
class AppContainer(context: Context, settingsOverride: SettingsRepository? = null) {

    val appContext: Context = context.applicationContext

    /**
     * App-weiter Scope für Hintergrundarbeit, die einzelne Screens überdauert. Ein unerwarteter Fehler in einer
     * Hintergrundaufgabe landet im Fehlerprotokoll, statt die ganze App zu beenden.
     */
    val appScope: CoroutineScope = CoroutineScope(
        SupervisorJob() + Dispatchers.Main.immediate +
            CoroutineExceptionHandler { _, e -> AppLog.error("App", "Unbehandelter Fehler im Hintergrund", e) },
    )

    internal val database: AppDatabase by lazy { AppDatabase.create(appContext) }
    internal val httpFetcher: HttpFetcher by lazy { HttpFetcher(HttpFetcher.createClient(PrivateDns(::wantsEncryptedDns))) }

    /** Verschlüsseltes DNS, solange das VPN nicht steht (sonst läuft DNS durch den Tunnel). */
    private val dohSetting by lazy { settingsRepository.settings.map { it.dohFallback }.stateIn(appScope, SharingStarted.Eagerly, true) }
    private fun wantsEncryptedDns(): Boolean = dohSetting.value && vpn.state.value !is VpnState.Connected
    internal val xtreamApi: XtreamApi by lazy { XtreamApi(httpFetcher) }

    /** App-Updates aus dem privaten GitHub-Repo (Schlüssel in den Einstellungen). */
    val appUpdater: AppUpdater by lazy { AppUpdater(appContext, httpFetcher) }

    val settingsRepository: SettingsRepository by lazy { settingsOverride ?: SettingsRepositoryImpl(appContext) }

    /** EPG-Zeitverschiebung je Zugang (bei „Alle Zugänge“ der Zugang, von dem das EPG stammt). */
    val epgShifts: EpgShifts by lazy { EpgShifts(database, settingsRepository) }
    private val epgShift: suspend (Long) -> Long = { epgShifts.forPlaylist(it) }

    val playlistRepository: PlaylistRepository by lazy {
        PlaylistRepositoryImpl(
            appContext,
            database,
            XtreamImporter(database, xtreamApi),
            M3uImporter(database, httpFetcher),
            searchIndexer,
            channelGroupIndex,
            appScope,
        )
    }

    /** true, solange Inhalte oder EPG aktualisiert werden (Listen übernehmen Import-Blöcke dann gesammelt). */
    private val syncRunning by lazy {
        syncManager.state.map { it.running }.distinctUntilChanged().stateIn(appScope, SharingStarted.Eagerly, false)
    }
    val channelHabits: ChannelHabits by lazy { ChannelHabits(appContext) }
    val startCache = StartCache()

    val autoRefresher by lazy { AutoRefresher({ playlistRepository.observePlaylists().first() }, { syncManager.refreshAll(it).join() }) }

    /** Senderliste: Schreibzugriffe sammeln, solange aktualisiert wird – auch während der Gruppen-Abfrage vor Anstoß. */
    private val liveWritesGated by lazy {
        combine(syncRunning, sport.eventGroupRefresh.running) { sync, groups -> sync || groups }
            .distinctUntilChanged().stateIn(appScope, SharingStarted.Eagerly, false)
    }
    val liveRepository: LiveRepository by lazy { LiveRepositoryImpl(database, epgShift, liveWritesGated, channelGroupIndex, channelHabits) }
    val vodRepository: VodRepository by lazy {
        VodRepositoryImpl(database, xtreamApi, syncRunning) { id -> SortPolicy.alphabetical(settingsRepository.current().groupSort, id) }
    }
    val seriesRepository: SeriesRepository by lazy {
        SeriesRepositoryImpl(
            database,
            xtreamApi,
            syncRunning,
            accountRank = { combined.settings.current().rankOf(it) },
            alphabetical = { id -> SortPolicy.alphabetical(settingsRepository.current().groupSort, id) },
        )
    }
    val searchIndexer: SearchIndexer by lazy { SearchIndexer(database, appContext.getSharedPreferences("search_index", Context.MODE_PRIVATE)) }
    val searchRepository: SearchRepository by lazy { SearchRepositoryImpl(database, searchIndexer, appScope) }

    /** Belegte Verbindungen je Zugang ("1/1") – für die Anzeige überall, wo Zugänge erscheinen. */
    val connectionMonitor: ConnectionMonitor by lazy { ConnectionMonitor(database, xtreamApi, appScope) }

    val categoryRepository: CategoryRepository by lazy {
        CategoryRepositoryImpl(database) { playlistId, becameVisible ->
            channelGroupIndex.rebuildLater(playlistId)
            combined.onSourceChanged(playlistId, "Gruppen")
            // Wieder eingeblendete Gruppen brauchen EPG – gesammelt, damit zehn Klicks nicht zehnmal laden.
            if (becameVisible && !Combined.isCombined(playlistId)) epgAfterUnhide.request(playlistId)
        }
    }

    /** Vorberechnete Sender-Gruppierung (beste Variante je Sender) für schnelle Senderlisten. */
    val channelGroupIndex: ChannelGroupIndex by lazy {
        ChannelGroupIndex(
            db = database,
            scope = appScope,
            unify = { id -> VariantUnifier.unify(database, id, combined.catalog()) },
            alphabetical = { id -> SortPolicy.alphabetical(settingsRepository.current().groupSort, id) },
        )
    }

    private val epgAfterUnhide: DelayedAction<Long> by lazy {
        DelayedAction(appScope, 30_000L) { playlistId: Long -> syncManager.refreshEpg(playlistId) }
    }
    val epgRepository: EpgRepository by lazy {
        val fallbackGate = FallbackEpgGate(PrefsLongStore(appContext.getSharedPreferences("epg_fallback", Context.MODE_PRIVATE)))
        EpgRepositoryImpl(appContext, database, EpgImporter(database, httpFetcher, xtreamApi, fallbackGate), xtreamApi, epgShift)
    }

    /** Derselbe Inhalt in anderen Zugängen (gemeinsamer Fortschritt, Nachfrage bei belegtem Zugang, Ausfall). */
    val counterpartFinder: CounterpartFinder by lazy { CounterpartFinder(database) { seriesRepository.loadEpisodes(it) } }
    val playbackRepository: PlaybackRepository by lazy {
        PlaybackRepositoryImpl(database, counterpartFinder, appScope) { settingsRepository.current().noHistory }
    }
    val tmdbRepository: TmdbRepository by lazy {
        TmdbRepositoryImpl(database, TmdbApi(httpFetcher.client), settingsRepository, searchRepository)
    }

    /** Baut Stream- und Catch-up-Adressen erst beim Abspielen (keine Zugangsdaten in der Datenbank). */
    val streamUrlResolver: StreamUrlResolver by lazy { StreamUrlResolver(database, xtreamApi) }

    val reminders: ReminderManager by lazy { ReminderManager(appContext) }

    val parentalControl: ParentalControl by lazy {
        ParentalControl(settingsRepository, appScope, PrefsPinBackup(appContext), SettingsCorruption::consume, attempts = PrefsPinAttempts(appContext))
    }

    /** App-Sperre (PIN beim Öffnen): gesperrt beim Start und beim Wechsel in den Hintergrund. */
    val appLock = AppLockState()

    val backupManager: BackupManager by lazy { BackupManager(database, playlistRepository, settingsRepository, vpnImporter) }

    val addPlaylist: AddPlaylistUseCase by lazy { AddPlaylistUseCase(playlistRepository, settingsRepository) }
    val refreshPlaylist: RefreshPlaylistUseCase by lazy {
        // Neue Gruppen nach der gespeicherten Länderauswahl ein- oder ausblenden – vor dem EPG,
        // damit für ausgeblendete Länder gar nicht erst EPG geladen wird.
        RefreshPlaylistUseCase(playlistRepository, epgRepository) { playlistId -> countryFilter.applyToNewGroups(playlistId) }
    }
    val resolveNextEpisode: ResolveNextEpisodeUseCase by lazy { ResolveNextEpisodeUseCase(seriesRepository) }

    val syncManager: SyncManager by lazy {
        SyncManager(appScope, refreshPlaylist).also { manager ->
            manager.onFinished = { id ->
                streamUrlResolver.clear()
                combined.onSourceChanged(id, "Aktualisierung")
            }
        }
    }

    /** Netzwerkzustand (online, VPN, WLAN/LAN) für Reconnect und Stream-Info. */
    val networkMonitor: NetworkMonitor by lazy { NetworkMonitor(appContext) }

    /** Wiedergabe: Engines, Vorschau-Übergabe, Gedächtnis für Spuren, Varianten und den Wiedergabe-Wächter. */
    val playback = PlaybackModule(this)

    /** „Alle Zugänge“: Einstellungen und Aufbau der Kombination. */
    val combined = CombinedModule(this)

    /** Sport: Spielpläne, Sender-Zuordnung, öffentliches Sport-Programm. */
    val sport = SportModule(this)

    /** "Auf dem Fernseher weiterschauen": Dieses Geraet nimmt Wiedergabe entgegen (Schalter in den Einstellungen). */
    val handoffServer: HandoffServer by lazy { HandoffServer(appContext) { request -> LaunchRequest.play(request) } }

    /** Gegenstueck auf dem Handy: laufende Wiedergabe an den Fernseher geben. */
    val handoffClient: HandoffClient by lazy { HandoffClient(appContext, httpFetcher) }

    /**
     * Laeuft, solange die App lebt: lauschen, wenn der Schalter an ist - und sofort aufhoeren, wenn er ausgeht.
     * Das Geheimnis ist dasselbe wie beim Pruefgeraet: eine Kopplung je Haushalt, nicht zwei.
     */
    suspend fun handoffLoop() {
        settingsRepository.settings
            .map { it.tvHandoff to (if (it.sportProofDevice) it.sportHubCode else it.sportHubJoinCode) }
            .distinctUntilChanged()
            .collect { (listening, secret) ->
                withContext(Dispatchers.IO) {
                    runCatching { if (listening) handoffServer.start(secret) else handoffServer.stop() }
                        .onFailure { AppLog.log("Übergabe", "Fernseher nicht bereit: ${it.javaClass.simpleName}") }
                }
            }
    }

    val favoriteOrder: FavoriteOrderRepository by lazy { FavoriteOrderRepository(database) }

    /** Eingebautes WireGuard-VPN nur für GuevenPlayer (verschlüsselt gespeicherte Standorte). */
    val vpnStore: VpnStore by lazy { VpnStore(appContext.getSharedPreferences("vpn", Context.MODE_PRIVATE)) }
    val vpn: VpnController by lazy {
        // DoH nur als Rückfall, wenn das System-DNS die Serveradresse nicht kennt.
        val backend = WireGuardBackend(appContext, PrivateDns({ true }))
        val online = networkMonitor.status.map { it.online }.distinctUntilChanged().stateIn(appScope, SharingStarted.Eagerly, true)
        VpnController(
            backend,
            vpnStore,
            appScope,
            online,
            AndroidVpnAlerts(appContext, vpnStore),
            isForeground = {
                androidx.lifecycle.ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED)
            },
        )
    }

    val vpnImporter: VpnImporter by lazy { VpnImporter(vpnStore, vpn) }

    /** Surfshark-Konto (Anmeldung, Serverliste); Tokens und Schlüssel verschlüsselt, Passwort nie gespeichert. */
    val surfshark: SurfsharkAccount by lazy {
        SurfsharkAccount(appContext.getSharedPreferences("surfshark", Context.MODE_PRIVATE), SurfsharkApi(httpFetcher.client), vpnImporter, vpnStore)
    }

    /** Länderauswahl pro Zugang (blendet Gruppen anderer Länder aus). */
    val countryFilter: CountryFilter by lazy {
        CountryFilter(database, File(appContext.filesDir, "country_filters.json")) {
            channelGroupIndex.rebuildLater(it)
            combined.onSourceChanged(it, "Länder")
        }
    }

    /** Aufnahmen von Live-Sendern (laufen im App-Scope weiter, solange die App lebt). */
    val recordingManager: RecordingManager by lazy {
        RecordingManager(appContext, appScope, StreamRecorder(httpFetcher.client), streamUrlResolver) { id -> liveRepository.channel(id) }
    }
    val autoRecorder: AutoRecorder by lazy {
        AutoRecorder(appContext, database, recordingManager, {
            settingsRepository.current().activePlaylistId
        }, epgShift, channelLookup = { liveRepository.channel(it) })
    }

    /**
     * Wechselt den aktiven Zugang. Alle Zugänge bleiben in der Datenbank, der Wechsel ist also sofort da.
     * Ein Zugang, der noch nie geladen wurde, wird direkt im Hintergrund geladen. „Alle Zugänge“ wird bei Bedarf
     * aufgebaut; aktualisiert werden dort die Original-Zugänge.
     */
    suspend fun switchPlaylist(id: Long) {
        settingsRepository.update { it.copy(activePlaylistId = id, lastChannelId = null, lastChannelCategoryId = null) }
        if (Combined.isCombined(id)) return combined.builder.ensureLater()
        val playlist = playlistRepository.getPlaylist(id) ?: return
        when {
            playlist.lastUpdatedMs == 0L -> syncManager.refreshAll(id)
            epgRepository.shouldAutoLoad(id) -> syncManager.refreshEpg(id)
        }
    }

    /** Prüft alle 6 Stunden, ob das EPG des aktiven Zugangs veraltet ist – TV-Apps laufen oft tagelang durch. */
    fun startEpgWatch() {
        appScope.launch {
            while (isActive) {
                delay(EpgSchedule.CHECK_INTERVAL_MS)
                try {
                    val id = settingsRepository.current().activePlaylistId ?: continue
                    for (t in combined.epgTargets(id)) if (!syncManager.state.value.running && epgRepository.shouldAutoLoad(t)) syncManager.refreshEpg(t)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    AppLog.error("EPG", "Hintergrundprüfung fehlgeschlagen", e)
                }
            }
        }
    }

    /**
     * „Reihenfolge in Gruppen“ geändert: Senderlisten aller Zugänge neu ordnen (Filme/Serien laden ihre Listen selbst
     * neu). Reagiert auf die Einstellung selbst – egal, wo sie geändert wird (Einstellungen, Wiederherstellung).
     */
    fun watchGroupSort() {
        appScope.launch {
            settingsRepository.settings.map { it.groupSort }.distinctUntilChanged().drop(1).collect {
                val ids = playlistRepository.observePlaylists().first().map { p -> p.id } + Combined.ID
                ids.forEach(channelGroupIndex::rebuildLater)
            }
        }
    }

    /**
     * VPN steht (wieder): Verbindungen im Pool, die über die normale Leitung liefen, schließen – sonst nutzte OkHttp sie
     * weiter am Tunnel vorbei. Zweiter Durchgang nach 2 s für Verbindungen, die der Player gerade freigibt.
     */
    fun watchTunnel() {
        appScope.launch {
            // VPN-Steuerung auf IO anlegen (liest SharedPreferences, lädt WireGuard) – sonst geschieht es hier, in
            // Application.onCreate auf dem Hauptthread (StrictMode „VpnStore.rawEntries“, Labor 3.23).
            withContext(Dispatchers.IO) { vpn }.tunnelUps().collect {
                repeat(2) { round ->
                    if (round > 0) delay(2_000)
                    withContext(Dispatchers.IO) { httpFetcher.client.connectionPool.evictAll() }
                }
            }
        }
    }

    /** Beim Start die Adresse des aktiven Anbieters vorab auflösen – der erste Senderstart wartet dann nicht aufs DNS. */
    fun warmUpDns() {
        appScope.launch(Dispatchers.IO) {
            runCatchingCancellable {
                val id = settingsRepository.current().activePlaylistId ?: return@launch
                val playlist = playlistRepository.getPlaylist(id) ?: return@launch
                val url = playlist.serverUrl.ifBlank { playlist.m3uUrl }
                url.toHttpUrlOrNull()?.host?.let { httpFetcher.client.dns.lookup(it) }
            }
        }
    }

    /** Namensauflösung einer Stream-Adresse vorab (Nachbarsender beim Zappen) – ohne Verbindung zum Stream. */
    fun warmUpHost(url: String) {
        appScope.launch(Dispatchers.IO) { runCatching { url.substringBefore('|').toHttpUrlOrNull()?.host?.let { httpFetcher.client.dns.lookup(it) } } }
    }

    /** Öffentliche IP, wie Server sie sehen (für „Verbindung prüfen“) – läuft wie alle App-Anfragen ggf. durchs VPN. */
    suspend fun publicIp(): String? = withContext(Dispatchers.IO) {
        runCatching {
            val request = okhttp3.Request.Builder().url("https://api.ipify.org").build()
            httpFetcher.client.newCall(request).execute().use { r -> r.body.string().trim().takeIf { r.isSuccessful && it.length in 7..45 } }
        }.getOrNull()
    }

    fun createQrServer(): QrSetupServer = QrSetupServer()

    val accountVerifier by lazy { AccountVerifier(xtreamApi, httpFetcher) }
}
