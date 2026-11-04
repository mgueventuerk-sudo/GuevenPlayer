package com.mg.player.ui.navigation

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import com.mg.player.core.di.AppContainer
import com.mg.player.core.log.AppLog
import com.mg.player.core.log.FrameStats
import com.mg.player.core.log.StartupClock
import com.mg.player.core.net.LocalNetwork
import com.mg.player.core.util.runCatchingCancellable
import com.mg.player.core.vpn.VpnState
import com.mg.player.data.combined.Combined
import com.mg.player.domain.model.Category
import com.mg.player.domain.model.PlayRequest
import com.mg.player.domain.model.Playlist
import com.mg.player.ui.common.AppLockGate
import com.mg.player.ui.common.CrashReportHost
import com.mg.player.ui.common.LoadingIndicator
import com.mg.player.ui.common.LocalAppContainer
import com.mg.player.ui.common.LocalNetworkPermissionEffect
import com.mg.player.ui.common.LocalParentalGate
import com.mg.player.ui.common.LocalToast
import com.mg.player.ui.common.ParentalGateHost
import com.mg.player.ui.common.ParentalGateState
import com.mg.player.ui.common.ReminderHost
import com.mg.player.ui.common.ToastHost
import com.mg.player.ui.common.ToastState
import com.mg.player.ui.common.TouchWindowMode
import com.mg.player.ui.common.isTouch
import com.mg.player.ui.detail.MovieDetailScreen
import com.mg.player.ui.detail.SeriesDetailScreen
import com.mg.player.ui.detail.TmdbCatalogTitleScreen
import com.mg.player.ui.home.HomeScreen
import com.mg.player.ui.live.MosaicScreen
import com.mg.player.ui.phone.PhoneHomeScreen
import com.mg.player.ui.phone.PhoneMovieDetail
import com.mg.player.ui.phone.PhoneSeriesDetail
import com.mg.player.ui.player.PlayerScreen
import com.mg.player.ui.settings.FavoritesOrderScreen
import com.mg.player.ui.settings.ManageCategoriesScreen
import com.mg.player.ui.settings.ShareMode
import com.mg.player.ui.settings.ShareScreen
import com.mg.player.ui.settings.SurfsharkScreen
import com.mg.player.ui.setup.CountryFilterScreen
import com.mg.player.ui.setup.EditPlaylistScreen
import com.mg.player.ui.setup.SetupScreen
import com.mg.player.ui.sport.SportAssignScreen
import com.mg.player.ui.sport.SportLeaguesScreen
import com.mg.player.ui.theme.BrandLogo
import com.mg.player.ui.vpn.LocalVpnState
import com.mg.player.ui.vpn.VpnBanner
import com.mg.player.ui.vpn.VpnPermissionHost
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal data class StartInfo(val screen: Screen, val resumeChannel: PlayRequest.Live?, val appLock: Boolean = false)

/** Startlogik + Darstellung des obersten Back-Stack-Eintrags. */
@Composable
fun AppRoot(container: AppContainer) {
    // Datenbank öffnen, Einstellungen und Playlists lesen: nie auf dem Hauptthread (sonst wartet der Startbildschirm).
    val start by produceState<StartInfo?>(null) { value = withContext(Dispatchers.IO) { resolveStart(container) } }
    val info = start
    if (info == null) {
        SplashScreen()
        return
    }
    val navigator = remember(info) { Navigator(info.screen) }
    val stateHolder = rememberSaveableStateHolder()
    navigator.onRemoved = { stateHolder.removeState(it.id) }

    LaunchedEffect(navigator) {
        // Messpunkt fürs Fehlerprotokoll: Prozessstart bis zum ersten Bild des Startbildschirms.
        withFrameNanos { }
        AppLog.log("Messung", StartupClock.startupLine())
        info.resumeChannel?.let { request ->
            // Mit App-Sperre erst nach der PIN abspielen (sonst liefe der Ton hinter der Sperre).
            if (info.appLock) container.appLock.locked.first { !it }
            navigator.push(Screen.Player(request))
        }
        // Wurden alle Playlists gelöscht, geht es zurück zur Einrichtung.
        container.playlistRepository.observePlaylists().collect { playlists ->
            if (playlists.isEmpty() && navigator.current.screen != Screen.Setup) navigator.resetTo(Screen.Setup)
        }
    }

    // Sport-Events nur online holen, solange die App sichtbar ist (im Hintergrund kein einziger Abruf).
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(container, lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            StartupClock.awaitContentShown()
            container.sport.repository.keepFresh()
        }
    }
    // „Automatisch aufnehmen“: nach EPG-Abrufen und alle 6 h einplanen (nur lokale Daten, keine Online-Anfrage).
    LaunchedEffect(container, lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) { container.autoRecorder.keepPlanned(container.syncManager.state.map { it.finishedAtMs }) }
    }

    val gate = remember(container) { ParentalGateState(container.parentalControl) }
    val toast = remember { ToastState() }
    val vpnState by container.vpn.state.collectAsStateWithLifecycle()
    VpnRestoredToast(vpnState, toast)
    CompositionLocalProvider(
        LocalNavigator provides navigator,
        LocalAppContainer provides container,
        LocalParentalGate provides gate,
        LocalToast provides toast,
        LocalVpnState provides vpnState,
    ) {
        BackHandler(enabled = navigator.canPop) { navigator.pop() }
        // IPTV-Server im Heimnetz (NAS, TV-Box): ab Android 17 nur mit „Geräte in der Nähe“ erreichbar.
        val lanServer by produceState(false, container) {
            if (!LocalNetwork.permissionRequired(container.appContext)) return@produceState
            container.playlistRepository.observePlaylists().collect { list ->
                value = withContext(Dispatchers.IO) {
                    list.any { p -> listOf(p.serverUrl, p.m3uUrl).any { it.isNotBlank() && LocalNetwork.pointsToLocal(it) } }
                }
            }
        }
        LocalNetworkPermissionEffect(needed = lanServer, deniedHint = "Ohne „Geräte in der Nähe“ ist der Server im Heimnetz nicht erreichbar.")
        val entry = navigator.current
        // Bildzeiten je Bildschirm; die Startseite meldet ihren Bereich (live, movies …) selbst.
        LaunchedEffect(entry.screen) { if (entry.screen != Screen.Home) FrameStats.screen(entry.screen::class.simpleName ?: "screen") }
        val touch = isTouch
        val video = entry.screen is Screen.Player
        // Handy: Player im Vollbild quer, alles andere randlos mit Abstand zu Status- und Navigationsleiste.
        TouchWindowMode(fullscreenVideo = video)
        Box(Modifier.fillMaxSize().then(if (touch && !video) Modifier.windowInsetsPadding(WindowInsets.safeDrawing) else Modifier)) {
            stateHolder.SaveableStateProvider(entry.id) {
                CompositionLocalProvider(LocalViewModelStoreOwner provides entry) {
                    when (val screen = entry.screen) {
                        // Nach dem Einrichten fragt GuevenPlayer, welche Länder angezeigt werden sollen.
                        Screen.Setup -> SetupScreen(onFinished = { navigator.resetTo(Screen.CountryFilter(firstRun = true)) })

                        Screen.AddPlaylist -> SetupScreen(
                            onFinished = { navigator.resetTo(Screen.CountryFilter(firstRun = true)) },
                            firstSetup = false,
                        )

                        is Screen.EditPlaylist -> EditPlaylistScreen(screen.playlistId, onDone = { navigator.pop() })


                        is Screen.CountryFilter -> CountryFilterScreen(
                            firstRun = screen.firstRun,
                            playlistId = screen.playlistId,
                            onDone = { if (screen.firstRun) navigator.resetTo(Screen.Home) else navigator.pop() },
                        )

                        Screen.Home -> if (touch) PhoneHomeScreen() else HomeScreen()

                        is Screen.MovieDetail -> if (touch) PhoneMovieDetail(screen.movieId) else MovieDetailScreen(screen.movieId)

                        is Screen.SeriesDetail -> if (touch) PhoneSeriesDetail(screen.seriesId) else SeriesDetailScreen(screen.seriesId)

                        is Screen.TmdbCatalogTitle -> TmdbCatalogTitleScreen(screen.recommendation)

                        is Screen.Player -> PlayerScreen(screen.request)

                        Screen.ManageCategories -> ManageCategoriesScreen()

                        is Screen.Share -> ShareScreen(screen.mode)

                        Screen.SportLeagues -> SportLeaguesScreen()

                        Screen.FavoritesOrder -> FavoritesOrderScreen()

                        Screen.Surfshark -> SurfsharkScreen()

                        is Screen.SportAssign -> SportAssignScreen(screen.leagueKey)

                        is Screen.Mosaic -> MosaicScreen(screen.categoryId, screen.startChannelId)
                    }
                }
            }
            // VPN getrennt: rotes Band oben über allem, auch im Vollbild-Player.
            VpnBanner(vpnState, touch, Modifier.align(Alignment.TopCenter))
            VpnPermissionHost(container.vpn)
            // Am Handy über der unteren Navigationsleiste.
            ToastHost(toast, Modifier.align(Alignment.BottomCenter).padding(bottom = if (touch && !video) 96.dp else 36.dp))
            // Erinnerungen und Starts aus Benachrichtigungen.
            ReminderHost(container, navigator, gate)
            // PIN-Abfrage der Kindersicherung liegt über allem.
            ParentalGateHost(gate)
            // Nach einem Absturz beim nächsten Start: Bericht anbieten.
            CrashReportHost(onOpenLog = { navigator.push(Screen.Share(ShareMode.LOG)) })
            // App-Sperre deckt alles ab, bis die PIN stimmt (als Letztes: liegt über allen anderen Fenstern).
            AppLockGate(container, info.appLock)
        }
    }
}

/** Kurze grüne Meldung, sobald ein getrenntes VPN wieder steht. */
@Composable
private fun VpnRestoredToast(state: VpnState, toast: ToastState) {
    var wasDown by remember { mutableStateOf(false) }
    LaunchedEffect(state) {
        if (state is VpnState.Connected && wasDown) toast.show("VPN wieder verbunden", success = true)
        wasDown = state is VpnState.Disconnected && state.announced
    }
}

/** Gültiger aktiver Zugang: ein echter – oder „Alle Zugänge“, solange es mindestens zwei Zugänge gibt. */
private fun isValidActive(id: Long?, playlists: List<Playlist>): Boolean =
    if (Combined.isCombined(id)) playlists.size >= 2 else playlists.any { it.id == id }

/** „Alle Zugänge“ beim Start: Originale aktualisieren (Auto-Update) bzw. ihr EPG nachladen, wenn es fehlt. */
private suspend fun startCombined(container: AppContainer, autoUpdate: Boolean, playlists: List<Playlist>) {
    if (autoUpdate) {
        container.syncManager.refreshEverything(playlists.map { it.id to it.name })
        return
    }
    container.combined.builder.ensureLater()
    playlists.filter { container.epgRepository.shouldAutoLoad(it.id) }.forEach { container.syncManager.refreshEpg(it.id) }
}

internal suspend fun resolveStart(container: AppContainer): StartInfo {
    // Dienste, die die Oberfläche beim ersten Bild anfasst, hier (IO) anlegen: Ihre Konstruktoren lesen SharedPreferences
    // bzw. Dateien. Im Emulator-Labor (3.23) wartete der Hauptthread darauf während der Komposition der Startseite – mit
    // Tastendruck in dieser Zeit ein ANR („Input dispatching timed out“). StrictMode meldete zudem Sport, Sehgewohnheiten
    // und Auto-Aufnahme.
    warmUp(container.parentalControl, container.vpn, container.sport.repository, container.channelHabits, container.autoRecorder)
    val playlists = container.playlistRepository.observePlaylists().first()
    if (playlists.isEmpty()) return StartInfo(Screen.Setup, null, container.settingsRepository.current().appLock)
    var settings = container.settingsRepository.current()
    if (!isValidActive(settings.activePlaylistId, playlists)) {
        container.settingsRepository.update { it.copy(activePlaylistId = playlists.first().id) }
        settings = container.settingsRepository.current()
    }
    val activeId = settings.activePlaylistId
    // Live-Gruppen schon jetzt lesen (parallel zum Splash), damit „Alle Sender“ im ersten Bild steht (Plan B2).
    activeId?.let { id -> runCatchingCancellable { container.startCache.putLiveCategories(id, container.liveRepository.categories(id).first()) } }
    // Suchindex, Aktualisierung und EPG erst, wenn der erste Inhalt steht (Labor 3.23: sonst im Startfenster).
    if (activeId != null) {
        val autoUpdate = settings.autoUpdateOnStart
        container.appScope.launch(Dispatchers.IO) {
            StartupClock.awaitContentShown()
            // Suchindex nach einem Update im Hintergrund neu aufbauen – nicht erst bei der ersten Suche.
            container.searchRepository.prepare(activeId)
            // Neue Zuordnungsregeln nach einem Update: Senderlisten und „Alle Zugänge“ neu zusammenfassen.
            container.combined.upgradeIfRulesChanged(playlists.map { it.id })
            when {
                // „Alle Zugänge“: aktualisiert werden die Original-Zugänge; die Kombination folgt danach von selbst.
                Combined.isCombined(activeId) -> startCombined(container, autoUpdate, playlists)

                autoUpdate -> container.syncManager.refreshAll(activeId)

                // Nach dem Update auf EPG je Zugang (oder einem abgebrochenen Import) fehlt das EPG: nachladen.
                container.epgRepository.shouldAutoLoad(activeId) -> container.syncManager.refreshEpg(activeId)
            }
        }
    }
    val lastChannel = settings.lastChannelId
    val category = settings.lastChannelCategoryId ?: Category.ALL
    val resume = if (settings.startWithLastChannel && lastChannel != null &&
        !(settings.parentalPinHash != null && isLockedChannel(container, lastChannel, category))
    ) {
        PlayRequest.Live(lastChannel, category)
    } else {
        null
    }
    return StartInfo(Screen.Home, resume, settings.appLock)
}

/** Ein Sender aus einer gesperrten Gruppe startet nicht automatisch – das ginge an der PIN vorbei. */
private suspend fun isLockedChannel(container: AppContainer, channelId: String, categoryId: String): Boolean {
    val categories = container.categoryRepository
    if (categories.isLocked(categoryId)) return true
    val channel = container.liveRepository.channel(channelId) ?: return false
    return categories.isLocked(channel.categoryId)
}

/** Startbildschirm, solange Zugänge und Einstellungen geladen werden. */
@Composable
private fun SplashScreen() {
    Column(
        Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        BrandLogo(markSize = 72.dp, fontSize = 40.sp)
        Spacer(Modifier.height(32.dp))
        LoadingIndicator(size = 34.dp)
    }
}

/** Erzwingt das Anlegen verzögert erzeugter Dienste auf dem aufrufenden Thread (siehe [resolveStart]). */
@Suppress("UNUSED_PARAMETER", "UnusedParameter")
private fun warmUp(vararg services: Any) = Unit
