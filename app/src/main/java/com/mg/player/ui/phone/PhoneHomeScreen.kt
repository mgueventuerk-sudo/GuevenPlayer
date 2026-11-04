package com.mg.player.ui.phone

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.mg.player.ui.accounts.AccountButton
import com.mg.player.ui.accounts.AccountSwitcherDialog
import com.mg.player.ui.accounts.AccountsViewModel
import com.mg.player.ui.common.LocalAppContainer
import com.mg.player.ui.common.LocalBackdrop
import com.mg.player.ui.common.LocalToast
import com.mg.player.domain.model.AppSettings
import com.mg.player.ui.common.TopBarItem
import com.mg.player.ui.common.appViewModel
import com.mg.player.ui.home.HomeViewModel
import com.mg.player.ui.home.StreamingHomeScreen
import com.mg.player.ui.home.SyncBanner
import com.mg.player.ui.navigation.LocalNavigator
import com.mg.player.ui.navigation.Screen
import com.mg.player.ui.recordings.RecordingsScreen
import com.mg.player.ui.search.SearchScreen
import com.mg.player.ui.settings.SettingsScreen
import com.mg.player.ui.sport.SportScreen
import com.mg.player.ui.theme.AppIcons
import com.mg.player.ui.vpn.LocalVpnState
import com.mg.player.ui.vpn.VpnBadge
import kotlinx.coroutines.launch

internal val phoneTabs = listOf(
    TopBarItem(HomeViewModel.SECTION_HOME, "Start", AppIcons.Home),
    TopBarItem(HomeViewModel.SECTION_LIVE, "Live TV", AppIcons.LiveTv),
    TopBarItem(HomeViewModel.SECTION_MOVIES, "Filme", AppIcons.Movie),
    TopBarItem(HomeViewModel.SECTION_SERIES, "Serien", AppIcons.Series),
    TopBarItem(HomeViewModel.SECTION_GUIDE, "Guide", AppIcons.Guide),
    TopBarItem(HomeViewModel.SECTION_SEARCH, "Suche", AppIcons.Search),
    TopBarItem(HomeViewModel.SECTION_SETTINGS, "Einstellungen", AppIcons.Settings),
)

/**
 * Startseite am Handy: Kopfzeile (Logo, Aufnahmen, Zugang), darunter der Bereich und unten
 * die Navigationsleiste. Zurück führt zu Live-TV und von dort aus der App (wie bei Handy-Apps üblich).
 */
@Composable
fun PhoneHomeScreen() {
    val vm = appViewModel { HomeViewModel() }
    val section by vm.section.collectAsStateWithLifecycle()
    val container = LocalAppContainer.current
    val navigator = LocalNavigator.current
    val backdrop = LocalBackdrop.current
    val toast = LocalToast.current
    val settings by container.settingsRepository.settings.collectAsStateWithLifecycle(initialValue = AppSettings())
    var wasBackgrounded by remember { mutableStateOf(false) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_STOP -> wasBackgrounded = true
                Lifecycle.Event.ON_START -> if (wasBackgrounded) {
                    wasBackgrounded = false
                    vm.select(HomeViewModel.SECTION_LIVE)
                }
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    val sync by container.syncManager.state.collectAsStateWithLifecycle()
    val accounts = appViewModel { AccountsViewModel(it) }
    val playlists by accounts.playlists.collectAsStateWithLifecycle()
    val activeId by accounts.activeId.collectAsStateWithLifecycle()
    val activeAccount by accounts.active.collectAsStateWithLifecycle()
    var switcherOpen by remember { mutableStateOf(false) }
    val sportVisible by container.sport.repository.visible.collectAsStateWithLifecycle()
    LaunchedEffect(sportVisible, section) {
        if (!sportVisible && section == HomeViewModel.SECTION_SPORT) vm.select(HomeViewModel.SECTION_LIVE)
    }
    LaunchedEffect(settings.hideGuide, section) {
        if (settings.hideGuide && section == HomeViewModel.SECTION_GUIDE) {
            vm.select(HomeViewModel.SECTION_LIVE)
            toast.show("Guide ist ausgeblendet")
        }
    }

    LaunchedEffect(section) { backdrop.url = null }
    BackHandler(enabled = section != HomeViewModel.SECTION_LIVE && !switcherOpen) { vm.select(HomeViewModel.SECTION_LIVE) }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            PhoneTopBar {
                VpnBadge(LocalVpnState.current, iconOnly = true, onClick = { vm.select(HomeViewModel.SECTION_SETTINGS) })
                if (sportVisible) {
                    PhoneIconButton(
                        AppIcons.Sport,
                        "Sport",
                        onClick = { vm.select(HomeViewModel.SECTION_SPORT) },
                        active = section == HomeViewModel.SECTION_SPORT,
                    )
                }
                PhoneIconButton(
                    AppIcons.Recordings,
                    "Aufnahmen",
                    onClick = { vm.select(HomeViewModel.SECTION_RECORDINGS) },
                    active = section == HomeViewModel.SECTION_RECORDINGS,
                )
                activeAccount?.let { account -> AccountButton(account, onClick = { switcherOpen = true }) }
            }
            val content = Modifier.weight(1f).fillMaxWidth()
            when (section) {
                HomeViewModel.SECTION_HOME -> StreamingHomeScreen(content, onOpenSection = vm::select)
                HomeViewModel.SECTION_LIVE -> PhoneLiveScreen(content)
                HomeViewModel.SECTION_SPORT -> SportScreen(content)
                HomeViewModel.SECTION_GUIDE -> if (settings.hideGuide) PhoneLiveScreen(content) else PhoneGuideScreen(content)
                HomeViewModel.SECTION_MOVIES -> PhoneMoviesScreen(content)
                HomeViewModel.SECTION_SERIES -> PhoneSeriesScreen(content)
                HomeViewModel.SECTION_RECORDINGS -> RecordingsScreen(content)
                HomeViewModel.SECTION_SEARCH -> SearchScreen(content)
                else -> SettingsScreen(content)
            }
            PhoneBottomBar(
                phoneTabs.filter { !settings.hideGuide || it.id != HomeViewModel.SECTION_GUIDE },
                section,
                onSelect = { vm.select(it.id) },
            )
        }
        // Über der unteren Leiste, klein und einzeilig – verdeckt keine Inhalte oben.
        SyncBanner(sync, Modifier.align(Alignment.BottomCenter).padding(bottom = 76.dp))
    }

    if (switcherOpen) {
        AccountSwitcherDialog(
            playlists = playlists,
            activeId = activeId,
            onSelect = { account ->
                switcherOpen = false
                if (account.id != activeId) {
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
