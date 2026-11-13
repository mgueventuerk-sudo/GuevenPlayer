package com.mg.player.ui.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.mg.player.domain.model.Playlist
import com.mg.player.ui.accounts.rememberConnections
import com.mg.player.ui.common.ConfirmDialog
import com.mg.player.ui.common.LocalAppContainer
import com.mg.player.ui.common.LocalParentalGate
import com.mg.player.ui.common.PinDialog
import com.mg.player.ui.common.ScreenTitle
import com.mg.player.ui.common.appViewModel
import com.mg.player.ui.common.isTouch
import com.mg.player.ui.common.requestFocusWhenReady
import com.mg.player.ui.navigation.LocalNavigator
import com.mg.player.ui.theme.AppColors
import com.mg.player.ui.vpn.LocalVpnState
import com.mg.player.ui.vpn.badgeLabel
import kotlinx.coroutines.delay

@Composable
fun SettingsScreen(modifier: Modifier = Modifier) {
    val vm = appViewModel { SettingsViewModel(it) }
    val s by vm.settings.collectAsStateWithLifecycle()
    val sportProblem by vm.sportProblem.collectAsStateWithLifecycle()
    val sportSummary by vm.sportSummary.collectAsStateWithLifecycle()
    val sportStations by vm.sportStations.collectAsStateWithLifecycle()
    val playlists by vm.playlists.collectAsStateWithLifecycle()
    val sync by vm.sync.collectAsStateWithLifecycle()
    val info by vm.info.collectAsStateWithLifecycle()
    val hasPin by vm.hasPin.collectAsStateWithLifecycle()
    val unlocked by vm.unlocked.collectAsStateWithLifecycle()
    val combined by vm.combined.collectAsStateWithLifecycle()
    val combinedStats by vm.combinedStats.collectAsStateWithLifecycle()
    var pinFlow by remember { mutableStateOf<PinFlow?>(null) }
    var autoRefreshFor by remember { mutableStateOf<Playlist?>(null) }
    var confirm by remember { mutableStateOf<ConfirmRequest?>(null) }
    // Gewählter Bereich und Unterseite bleiben beim Zurückkommen aus einem anderen Bildschirm (z. B. „Ligen wählen“).
    val nav = rememberSettingsNav()
    LaunchedEffect(info) {
        if (info != null) {
            delay(3_000)
            vm.consumeInfo()
        }
    }
    val status = info ?: sync.message.takeIf { sync.running || sync.error != null }
    val vpnStore = LocalAppContainer.current.vpnStore
    val vpnProfiles by vpnStore.profiles.collectAsStateWithLifecycle()
    val vpnActive by vpnStore.activeId.collectAsStateWithLifecycle()
    val vpnEnabled by vpnStore.enabled.collectAsStateWithLifecycle()
    val vpnSound by vpnStore.alertSound.collectAsStateWithLifecycle()
    val context = SettingsContext(
        vm,
        LocalNavigator.current,
        LocalParentalGate.current,
        SettingsState(
            s, playlists, rememberConnections(), sync, hasPin, unlocked, isTouch, sportProblem, sportSummary,
            VpnSettings(vpnProfiles, vpnActive, vpnEnabled, vpnSound), combined, combinedStats, sportStations,
        ),
        SettingsActions(
            onSetPin = { pinFlow = PinFlow() },
            onEditAutoRefresh = { autoRefreshFor = it },
            onOpen = nav::open,
            onConfirm = { confirm = it },
            returnFocus = { key -> Modifier.focusRequester(nav.requesterFor(key)) },
        ),
    )
    val vpnLine = LocalVpnState.current.badgeLabel()

    Box(modifier.fillMaxSize()) {
        if (context.touch) PhoneSettings(nav, context, status, vpnLine) else TvSettings(nav, context, status, vpnLine)

        autoRefreshFor?.let { playlist ->
            AutoRefreshDialog(
                name = playlist.name,
                currentDays = playlist.autoRefreshDays,
                onSave = { days ->
                    vm.setAutoRefreshDays(playlist.id, days)
                    autoRefreshFor = null
                },
                onDismiss = { autoRefreshFor = null },
            )
        }
        confirm?.let { request ->
            ConfirmDialog(
                title = request.title,
                message = request.message,
                confirmText = request.confirmText,
                dismissText = "Abbrechen",
                onConfirm = {
                    confirm = null
                    request.onConfirm()
                },
                onDismiss = { confirm = null },
            )
        }
        // Neue PIN: zweimal eingeben, damit kein Tippfehler zur Aussperrung führt.
        pinFlow?.let { flow ->
            PinDialog(
                title = if (flow.confirmStep) "PIN bestätigen" else "Neue PIN festlegen",
                subtitle = if (flow.confirmStep) "Bitte dieselbe PIN noch einmal eingeben." else "4 Ziffern – merk sie dir gut.",
                error = flow.error,
                onPinEntered = { pin ->
                    pinFlow = when {
                        !flow.confirmStep -> PinFlow(confirmStep = true, firstPin = pin)

                        pin == flow.firstPin -> {
                            vm.setPin(pin)
                            null
                        }

                        else -> PinFlow(error = "Die PINs stimmen nicht überein – bitte neu festlegen")
                    }
                },
                onDismiss = { pinFlow = null },
            )
        }
    }
}

/** Ein gelöschter Zugang schließt seine Unterseite (sonst stünde dort „Zugang gelöscht“). */
@Composable
private fun CloseGoneAccount(nav: SettingsNav, c: SettingsContext) {
    val sub = nav.sub
    LaunchedEffect(sub, c.playlists) {
        if (sub is SettingsSub.Account && c.playlists.isNotEmpty() && c.playlists.none { it.id == sub.playlistId }) nav.close()
    }
}

/**
 * Fernseher: links die Bereiche mit ihrem Zustand (Fokus zeigt den Bereich sofort rechts an), rechts sein Inhalt.
 * OK bzw. Rechts springt in den Inhalt. Zurück: Unterseite → Bereich → Bereichsliste → Einstellungen verlassen.
 */
@Composable
private fun TvSettings(nav: SettingsNav, c: SettingsContext, status: String?, vpnLine: String) {
    val navRequester = remember { FocusRequester() }
    val contentRequester = remember { FocusRequester() }
    val contentListState = nav.listState(nav.page)
    var contentFocused by remember { mutableStateOf(false) }
    val contentEntryRequest = remember { mutableIntStateOf(0) }
    CloseGoneAccount(nav, c)
    BackHandler(enabled = nav.sub != null) { nav.close() }
    BackHandler(enabled = nav.sub == null && contentFocused) { runCatching { navRequester.requestFocus() } }
    // Unterseite geöffnet: Fokus in ihre Liste; geschlossen: zurück auf die Zeile, die sie geöffnet hat.
    LaunchedEffect(nav.sub) {
        delay(FOCUS_SETTLE_MS)
        if (nav.sub != null) contentRequester.requestFocusWhenReady() else nav.restoreFocus()
    }
    LaunchedEffect(nav.page, contentEntryRequest.intValue) {
        if (contentEntryRequest.intValue == 0) return@LaunchedEffect
        contentListState.scrollToItem(0)
        delay(FOCUS_SETTLE_MS)
        repeat(20) {
            if (runCatching { contentRequester.requestFocus() }.getOrDefault(false)) return@LaunchedEffect
            delay(50)
        }
    }
    Row(Modifier.fillMaxSize().padding(start = 24.dp, end = 24.dp), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
        Column(Modifier.width(NAV_WIDTH).fillMaxHeight()) {
            ScreenTitle("Einstellungen", Modifier.padding(start = 8.dp))
            SettingsStatus(status, Modifier.padding(start = 8.dp, top = 2.dp))
            LazyColumn(
                Modifier.fillMaxHeight().focusRequester(navRequester).focusRestorer(FocusRequester.Default),
                contentPadding = PaddingValues(top = 12.dp, bottom = 40.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                items(SettingsPage.entries, key = { it.name }) { p ->
                    SettingsNavItem(
                        page = p,
                        status = p.status(c, vpnLine),
                        selected = p == nav.page,
                        onFocused = { nav.select(p) },
                        onClick = {
                            nav.select(p)
                            contentEntryRequest.intValue++
                        },
                        onMoveRight = { contentEntryRequest.intValue++ },
                    )
                }
            }
        }
        // Je Bereich bzw. Unterseite eine eigene Liste; die Bereichsliste behält ihre Position beim Zurückkehren.
        val sub = nav.sub
        key(nav.page, sub?.code, contentEntryRequest.intValue) {
            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .focusRequester(contentRequester)
                    .focusRestorer(FocusRequester.Default)
                    .onFocusChanged { contentFocused = it.hasFocus },
                state = if (sub != null) rememberLazyListState() else contentListState,
                contentPadding = PaddingValues(top = 8.dp, bottom = 48.dp, end = 4.dp),
            ) { if (sub != null) settingsSubPage(sub, c) else settingsPage(nav.page, c) }
        }
    }
}

/**
 * Handy: oben das Menü der Bereiche (mit Zustand), ein Tipp öffnet den Bereich als eigene Seite, darin ggf. eine
 * Unterseite. Zurück führt jeweils eine Ebene höher.
 */
@Composable
private fun PhoneSettings(nav: SettingsNav, c: SettingsContext, status: String?, vpnLine: String) {
    var showMenu by rememberSaveable { mutableStateOf(true) }
    CloseGoneAccount(nav, c)
    BackHandler(enabled = nav.sub != null) { nav.close() }
    BackHandler(enabled = nav.sub == null && !showMenu) { showMenu = true }
    val sub = nav.sub
    Column(Modifier.fillMaxSize()) {
        if (showMenu) {
            ScreenTitle("Einstellungen", Modifier.padding(start = 16.dp))
            SettingsStatus(status, Modifier.padding(start = 16.dp))
            PhoneSettingsMenu(c, vpnLine) { page ->
                nav.select(page)
                showMenu = false
            }
        } else {
            PhonePageBar(
                title = sub?.let { nav.page.label } ?: "Einstellungen",
                onBack = { if (sub != null) nav.close() else showMenu = true },
            )
            SettingsStatus(status, Modifier.padding(start = 16.dp))
            key(nav.page, sub?.code) {
                LazyColumn(
                    Modifier.fillMaxSize(),
                    state = if (sub != null) rememberLazyListState() else nav.listState(nav.page),
                    contentPadding = PaddingValues(start = 12.dp, end = 12.dp, bottom = 48.dp),
                ) { if (sub != null) settingsSubPage(sub, c) else settingsPage(nav.page, c) }
            }
        }
    }
}

@Composable
private fun SettingsStatus(status: String?, modifier: Modifier = Modifier) {
    if (status != null) {
        Text(status, modifier = modifier, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, color = AppColors.AccentText)
    }
}

/** Gewählter Bereich, offene Unterseite, Listenpositionen und Rückkehr-Fokus. */
internal class SettingsNav(
    private val pageState: androidx.compose.runtime.MutableState<SettingsPage>,
    private val subState: androidx.compose.runtime.MutableState<String?>,
    private val returnState: androidx.compose.runtime.MutableState<String?>,
) {
    private val requesters = HashMap<String, FocusRequester>()
    private val states = HashMap<SettingsPage, LazyListState>()

    val page: SettingsPage get() = pageState.value
    val sub: SettingsSub? get() = SettingsSub.decode(subState.value)

    fun select(p: SettingsPage) {
        if (p == pageState.value) return
        states.remove(p)
        pageState.value = p
        subState.value = null
        returnState.value = null
    }

    fun open(sub: SettingsSub, from: String) {
        pageState.value = sub.page
        returnState.value = from
        subState.value = sub.code
    }

    fun close() {
        subState.value = null
    }

    fun requesterFor(key: String): FocusRequester = requesters.getOrPut(key) { FocusRequester() }

    /**
     * Nach dem Schließen einer Unterseite: Fokus auf die Zeile, die sie geöffnet hat (falls sichtbar). Mit
     * Wiederholung – direkt nach dem Umbau der Liste ist die Zeile oft erst ein, zwei Bilder später fokussierbar.
     */
    suspend fun restoreFocus() {
        val key = returnState.value ?: return
        returnState.value = null
        requesters[key]?.requestFocusWhenReady()
    }

    @Composable
    fun listState(p: SettingsPage): LazyListState = states.getOrPut(p) { LazyListState() }
}

@Composable
private fun rememberSettingsNav(): SettingsNav {
    val page = rememberSaveable { mutableStateOf(SettingsPage.ACCOUNTS) }
    val sub = rememberSaveable { mutableStateOf<String?>(null) }
    val returnTo = rememberSaveable { mutableStateOf<String?>(null) }
    return remember { SettingsNav(page, sub, returnTo) }
}

private val NAV_WIDTH = 280.dp
private const val FOCUS_SETTLE_MS = 80L
