package com.mg.player.ui.home

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.mg.player.core.log.FrameStats
import com.mg.player.data.sync.SyncState
import com.mg.player.domain.model.AppSettings
import com.mg.player.ui.accounts.AccountButton
import com.mg.player.ui.accounts.AccountSwitcherDialog
import com.mg.player.ui.accounts.AccountsViewModel
import com.mg.player.ui.common.ConfirmDialog
import com.mg.player.ui.common.LoadingIndicator
import com.mg.player.ui.common.LocalAllCategoryFocus
import com.mg.player.ui.common.LocalAppContainer
import com.mg.player.ui.common.LocalBackdrop
import com.mg.player.ui.common.LocalGroupFocus
import com.mg.player.ui.common.LocalToast
import com.mg.player.ui.common.TopBar
import com.mg.player.ui.common.TopBarItem
import com.mg.player.ui.common.appViewModel
import com.mg.player.ui.common.findActivity
import com.mg.player.ui.common.glass
import com.mg.player.ui.guide.GuideScreen
import com.mg.player.ui.library.MoviesScreen
import com.mg.player.ui.library.SeriesScreen
import com.mg.player.ui.live.LiveScreen
import com.mg.player.ui.navigation.LocalNavigator
import com.mg.player.ui.navigation.Screen
import com.mg.player.ui.recordings.RecordingsScreen
import com.mg.player.ui.search.SearchScreen
import com.mg.player.ui.settings.SettingsScreen
import com.mg.player.ui.sport.SportScreen
import com.mg.player.ui.theme.AppColors
import com.mg.player.ui.theme.AppIcons
import com.mg.player.ui.theme.Dimens
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class HomeViewModel : ViewModel() {
    private val _section = MutableStateFlow(SECTION_LIVE)
    val section: StateFlow<String> = _section.asStateFlow()

    fun select(id: String) {
        _section.value = id
    }

    companion object {
        const val SECTION_HOME = "home"
        const val SECTION_LIVE = "live"
        const val SECTION_SPORT = "sport"
        const val SECTION_GUIDE = "guide"
        const val SECTION_MOVIES = "movies"
        const val SECTION_SERIES = "series"
        const val SECTION_RECORDINGS = "recordings"
        const val SECTION_SEARCH = "search"
        const val SECTION_SETTINGS = "settings"
    }
}

private val allTabs = listOf(
    TopBarItem(HomeViewModel.SECTION_HOME, "Start", AppIcons.Home),
    TopBarItem(HomeViewModel.SECTION_LIVE, "Live TV", AppIcons.LiveTv),
    TopBarItem(HomeViewModel.SECTION_SPORT, "Sport", AppIcons.Sport),
    TopBarItem(HomeViewModel.SECTION_MOVIES, "Filme", AppIcons.Movie),
    TopBarItem(HomeViewModel.SECTION_SERIES, "Serien", AppIcons.Series),
    TopBarItem(HomeViewModel.SECTION_GUIDE, "TV-Guide", AppIcons.Guide),
    TopBarItem(HomeViewModel.SECTION_RECORDINGS, "Aufnahmen", AppIcons.Recordings),
    TopBarItem(HomeViewModel.SECTION_SEARCH, "Suche", AppIcons.Search),
    TopBarItem(HomeViewModel.SECTION_SETTINGS, "Einstellungen", AppIcons.Settings),
)

@Composable
fun HomeScreen() {
    val vm = appViewModel { HomeViewModel() }
    val section by vm.section.collectAsStateWithLifecycle()
    val container = LocalAppContainer.current
    val navigator = LocalNavigator.current
    val backdrop = LocalBackdrop.current
    val sync by container.syncManager.state.collectAsStateWithLifecycle()
    val accounts = appViewModel { AccountsViewModel(it) }
    val playlists by accounts.playlists.collectAsStateWithLifecycle()
    val activeId by accounts.activeId.collectAsStateWithLifecycle()
    val activeAccount by accounts.active.collectAsStateWithLifecycle()
    var switcherOpen by remember { mutableStateOf(false) }
    var exitDialog by remember { mutableStateOf(false) }
    val topBarRequester = remember { FocusRequester() }
    var topBarFocused by remember { mutableStateOf(false) }
    val activity = LocalContext.current.findActivity()
    val toast = LocalToast.current
    // „Sport“ nur, wenn es live oder in den nächsten 7 Tagen etwas auf den eigenen Sendern gibt.
    val sportVisible by container.sport.repository.visible.collectAsStateWithLifecycle()
    val settings by container.settingsRepository.settings.collectAsStateWithLifecycle(initialValue = AppSettings())
    val tabs = remember(sportVisible, settings.hideGuide) {
        allTabs.filter { item ->
            (sportVisible || item.id != HomeViewModel.SECTION_SPORT) &&
                (!settings.hideGuide || item.id != HomeViewModel.SECTION_GUIDE)
        }
    }
    LaunchedEffect(sportVisible, section) { if (!sportVisible && section == HomeViewModel.SECTION_SPORT) vm.select(HomeViewModel.SECTION_LIVE) }
    LaunchedEffect(settings.hideGuide, section) {
        if (settings.hideGuide && section == HomeViewModel.SECTION_GUIDE) {
            vm.select(HomeViewModel.SECTION_LIVE)
            toast.show("TV-Guide ist ausgeblendet")
        }
    }
    // Jeder Bereich bringt sein eigenes Hintergrundbild mit (oder keins); Bildzeiten je Bereich messen.
    LaunchedEffect(section) {
        backdrop.url = null
        FrameStats.screen(section)
    }
    // Zurück-Taste auf der Startseite: Inhalt -> Navigationsleiste -> Live-TV -> "Beenden?" statt sofort zu schließen.
    // (Bildschirme im Inhalt, z. B. die Senderliste, fangen Zurück vorher selbst ab.)
    BackHandler(enabled = !switcherOpen && !exitDialog) {
        when {
            !topBarFocused -> runCatching { topBarRequester.requestFocus() }
            section != HomeViewModel.SECTION_LIVE -> vm.select(HomeViewModel.SECTION_LIVE)
            else -> exitDialog = true
        }
    }

    // Fokus in den Inhalt setzen: beim App-Start (sobald Daten da sind) und nach OK auf einem Bereich.
    val contentRequester = remember { FocusRequester() }
    val homeFocusRequester = remember { FocusRequester() }
    // Der Kaltstart und das Öffnen eines Tabs beginnen immer bei „Alle“.
    val allCategoryRequester = remember { FocusRequester() }
    // „Runter“ aus der Leiste: in Live-TV, Filme und Serien zuerst in die Gruppen (Fokus bleibt dort).
    val groupRequester = remember { FocusRequester() }
    val hasGroups = section == HomeViewModel.SECTION_LIVE || section == HomeViewModel.SECTION_MOVIES || section == HomeViewModel.SECTION_SERIES
    var focusTopBar by remember { mutableStateOf(true) }
    var focusContent by remember { mutableIntStateOf(1) }
    var wasBackgrounded by remember { mutableStateOf(false) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_STOP -> wasBackgrounded = true
                Lifecycle.Event.ON_START -> if (wasBackgrounded) {
                    wasBackgrounded = false
                    vm.select(HomeViewModel.SECTION_LIVE)
                    focusTopBar = true
                    focusContent++
                }
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(focusContent, section) {
        if (focusContent == 0) return@LaunchedEffect
        val attempts = if (focusTopBar) 40 else 12
        repeat(attempts) {
            val target = when {
                focusTopBar -> topBarRequester
                section == HomeViewModel.SECTION_HOME -> homeFocusRequester
                hasGroups -> allCategoryRequester
                else -> contentRequester
            }
            val focused = runCatching { target.requestFocus() }.getOrDefault(false)
            if (focused) {
                focusTopBar = false
                focusContent = 0
                return@LaunchedEffect
            }
            delay(150)
        }
        // Bereich ohne fokussierbaren Inhalt (z. B. noch keine Aufnahmen): Fokus auf den Tab, nie ins Leere.
        runCatching { topBarRequester.requestFocus() }
    }

    Box(Modifier.fillMaxSize()) {
        val contentModifier = Modifier
            .fillMaxSize()
            .padding(top = Dimens.ContentTop)
            .focusRequester(contentRequester)
        CompositionLocalProvider(
            LocalGroupFocus provides groupRequester.takeIf { hasGroups },
            LocalAllCategoryFocus provides allCategoryRequester.takeIf { hasGroups },
        ) {
            HomeSection(
                section = section,
                modifier = contentModifier,
                homeFocusRequester = homeFocusRequester,
                guideHidden = settings.hideGuide,
                onOpenSection = { next ->
                    vm.select(next)
                    focusContent++
                },
            )
        }
        TopBar(
            items = tabs,
            selectedId = section,
            onSelect = { vm.select(it.id) },
            onOpen = {
                vm.select(it.id)
                focusContent++
            },
            modifier = Modifier
                .padding(top = 14.dp)
                .onFocusChanged { topBarFocused = it.hasFocus },
            selectedRequester = topBarRequester,
            onDown = { hasGroups && runCatching { groupRequester.requestFocus() }.isSuccess },
            // Liquid Glass: Fokus im Inhalt → Leiste zieht sich ein.
            collapsed = !topBarFocused,
            trailing = {
                activeAccount?.let { account -> AccountButton(account, onClick = { switcherOpen = true }) }
            },
        )
        // Klein unten rechts statt über dem Inhalt: Aktualisierungen laufen im Hintergrund, die Bedienung bleibt frei.
        SyncBanner(sync, Modifier.align(Alignment.BottomEnd).padding(end = 18.dp, bottom = 14.dp))
    }

    if (exitDialog) {
        ConfirmDialog(
            title = "GuevenPlayer beenden?",
            confirmText = "Beenden",
            dismissText = "Abbrechen",
            onConfirm = { activity?.finish() },
            onDismiss = { exitDialog = false },
        )
    }

    if (switcherOpen) {
        AccountSwitcherDialog(
            playlists = playlists,
            activeId = activeId,
            onSelect = { account ->
                switcherOpen = false
                if (account.id != activeId) {
                    // Startseite komplett neu aufbauen, damit keine Kategorie des alten Zugangs hängen bleibt.
                    container.appScope.launch {
                        container.switchPlaylist(account.id)
                        navigator.resetTo(Screen.Home)
                    }
                }
            },
            onAdd = {
                switcherOpen = false
                navigator.push(Screen.AddPlaylist)
            },
            onDismiss = { switcherOpen = false },
            onRefreshAll = {
                switcherOpen = false
                container.syncManager.refreshEverything(playlists.map { it.id to it.name })
            },
        )
    }
}

/**
 * Dezente Anzeige, während Zugänge/EPG im Hintergrund aktualisiert werden: eine schmale, einzeilige Pille in einer
 * Ecke (nicht fokussierbar, fängt keine Tasten ab). Fertig-Meldung 4 s, Fehler 8 s; Einzelheiten unter Einstellungen.
 */
@Composable
internal fun SyncBanner(state: SyncState, modifier: Modifier = Modifier) {
    val container = LocalAppContainer.current
    LaunchedEffect(state.finishedAtMs) {
        if (!state.running && state.finishedAtMs > 0) {
            delay(if (state.error != null) 8_000 else 4_000)
            container.syncManager.clearMessage()
        }
    }
    AnimatedVisibility(visible = state.running || state.finishedAtMs > 0, enter = fadeIn(), exit = fadeOut(), modifier = modifier) {
        Row(
            Modifier
                .widthIn(max = 360.dp)
                .glass(RoundedCornerShape(50), fill = AppColors.Panel.copy(alpha = 0.82f))
                .padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (state.running) {
                LoadingIndicator(size = 14.dp)
                Spacer(Modifier.width(8.dp))
            }
            Text(
                syncLine(state),
                style = MaterialTheme.typography.labelMedium,
                color = if (state.error != null) AppColors.Danger else AppColors.TextSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** „2/3 · Anbieter: Lade EPG … 12.345“ – kurz, eine Zeile. */
internal fun syncLine(state: SyncState): String {
    val step = state.step?.let { "$it · " }.orEmpty()
    val count = if (state.running && state.count > 0) " · ${"%,d".format(state.count).replace(',', '.')}" else ""
    return step + state.message + count
}

/** Inhalt des gewählten Bereichs. */
@Composable
private fun HomeSection(
    section: String,
    modifier: Modifier,
    homeFocusRequester: FocusRequester,
    guideHidden: Boolean,
    onOpenSection: (String) -> Unit,
) {
    when (section) {
        HomeViewModel.SECTION_HOME -> StreamingHomeScreen(modifier, homeFocusRequester, onOpenSection)
        HomeViewModel.SECTION_LIVE -> LiveScreen(modifier)
        HomeViewModel.SECTION_SPORT -> SportScreen(modifier)
        HomeViewModel.SECTION_GUIDE -> if (guideHidden) LiveScreen(modifier) else GuideScreen(modifier)
        HomeViewModel.SECTION_MOVIES -> MoviesScreen(modifier)
        HomeViewModel.SECTION_SERIES -> SeriesScreen(modifier)
        HomeViewModel.SECTION_RECORDINGS -> RecordingsScreen(modifier)
        HomeViewModel.SECTION_SEARCH -> SearchScreen(modifier)
        else -> SettingsScreen(modifier)
    }
}
