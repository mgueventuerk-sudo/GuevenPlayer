package com.mg.player.ui.sport

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.mg.player.core.di.AppContainer
import com.mg.player.core.util.TimeUtils
import com.mg.player.data.sport.leagueOfEvent
import com.mg.player.domain.model.ChannelKeyRow
import com.mg.player.domain.model.SportEvent
import com.mg.player.ui.common.CenteredMessage
import com.mg.player.ui.common.ScreenTitle
import com.mg.player.ui.common.TvTextField
import com.mg.player.ui.common.appViewModel
import com.mg.player.ui.common.isTouch
import com.mg.player.ui.settings.ActionRow
import com.mg.player.ui.theme.AppColors
import com.mg.player.ui.theme.AppIcons
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Sport: Ligen, deren Spiele kein Sender zugeordnet ist, bekommen von Hand einen Sender (gilt für alle Spiele der Liga). */
class SportAssignViewModel(private val container: AppContainer) : ViewModel() {
    data class LeagueRow(val key: String, val name: String, val unmatched: List<SportEvent>, val channelId: String?, val channelName: String?)

    private val repo = container.sport.repository

    /** Namen aller Ligen („bsd:5“ → „Bundesliga (Germany)“), einmal geladen. */
    private val leagueNames = flow { emit(repo.catalog().associate { it.key to it.name }) }

    val rows: StateFlow<List<LeagueRow>?> = combine(
        repo.unmatched,
        container.settingsRepository.settings.map { it.sportManualChannels }.distinctUntilChanged(),
        leagueNames,
    ) { events, manual, names -> Triple(events, manual, names) }
        .mapLatest { (events, manual, leagueNames) ->
            val names = repo.channelNames(manual.values)
            val byLeague = events.groupBy { leagueOfEvent(it.id) }
            (byLeague.keys + manual.keys).map { key ->
                val list = byLeague[key].orEmpty()
                LeagueRow(key, leagueNames[key] ?: list.firstOrNull()?.league ?: key, list, manual[key], manual[key]?.let(names::get))
            }.sortedBy { it.name.lowercase() }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val query = MutableStateFlow("Sport")

    val results: StateFlow<List<ChannelKeyRow>> = query.debounce(250).mapLatest { repo.searchChannels(it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun assign(leagueKey: String, channelId: String?) {
        viewModelScope.launch { repo.assign(leagueKey, channelId) }
    }
}

@Composable
fun SportAssignScreen(leagueKey: String? = null) {
    val vm = appViewModel { SportAssignViewModel(it) }
    val rows by vm.rows.collectAsStateWithLifecycle()
    var picking by remember { mutableStateOf<SportAssignViewModel.LeagueRow?>(null) }
    // Aus der Sport-Übersicht für eine bestimmte Liga geöffnet: gleich deren Senderauswahl.
    var opened by remember { mutableStateOf(leagueKey == null) }
    LaunchedEffect(rows) {
        if (!opened) {
            rows?.firstOrNull { it.key == leagueKey }?.let {
                picking = it
                opened = true
            }
        }
    }
    BackHandler(enabled = picking != null) { picking = null }
    val touch = isTouch
    Column(Modifier.fillMaxSize().padding(start = if (touch) 16.dp else 64.dp, top = if (touch) 12.dp else 40.dp, end = if (touch) 16.dp else 64.dp)) {
        val league = picking
        ScreenTitle(if (league == null) "Sport: Sender korrigieren" else "Sender für ${league.name}")
        Text(
            if (league == null) {
                "Normalerweise findet die App die Sender selbst. Nur wenn sie für eine Liga keinen findet, kannst du hier einen " +
                    "Ersatz festlegen – er gilt für alle Spiele der Liga, für die sonst nichts gefunden wird."
            } else {
                "Namen eingeben (z. B. „Sky Sport“, „DAZN“) und den Sender wählen."
            },
            style = MaterialTheme.typography.bodyMedium,
            color = AppColors.TextSecondary,
            modifier = Modifier.padding(top = 4.dp, bottom = 12.dp),
        )
        if (league == null) {
            LeagueList(rows, onPick = { picking = it })
        } else {
            ChannelPicker(vm, league, onDone = { picking = null })
        }
    }
}

@Composable
private fun LeagueList(rows: List<SportAssignViewModel.LeagueRow>?, onPick: (SportAssignViewModel.LeagueRow) -> Unit) {
    when {
        rows == null -> CenteredMessage(title = "Lädt…", loading = true)

        rows.isEmpty() -> CenteredMessage(title = "Alle Spiele haben einen Sender", subtitle = "Oder es stehen keine Spiele an.", icon = AppIcons.Sport)

        else -> LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 40.dp)) {
            items(rows, key = { it.key }) { row ->
                val next = row.unmatched.firstOrNull()
                val description = listOfNotNull(
                    when (row.unmatched.size) {
                        0 -> null
                        1 -> "1 Spiel ohne Sender"
                        else -> "${row.unmatched.size} Spiele ohne Sender"
                    },
                    next?.let { "nächstes: ${it.title}, ${TimeUtils.formatDate(it.startMs)}" },
                ).joinToString(" · ").ifEmpty { "Alle Spiele zugeordnet" }
                ActionRow(row.name, { onPick(row) }, description, trailing = row.channelName ?: row.channelId?.let { "Sender fehlt" }, icon = AppIcons.Sport)
            }
        }
    }
}

@Composable
private fun ChannelPicker(vm: SportAssignViewModel, league: SportAssignViewModel.LeagueRow, onDone: () -> Unit) {
    val query by vm.query.collectAsStateWithLifecycle()
    val results by vm.results.collectAsStateWithLifecycle()
    TvTextField(
        value = query,
        onValueChange = { vm.query.value = it },
        label = "Sendername",
        leadingIcon = AppIcons.Search,
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
    )
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 40.dp)) {
        if (league.channelId != null) {
            item(key = "remove") {
                ActionRow("Zuordnung entfernen", { vm.assign(league.key, null).also { onDone() } }, league.channelName, icon = AppIcons.Close)
            }
        }
        items(results, key = { it.id }) { channel ->
            ActionRow(
                channel.name,
                { vm.assign(league.key, channel.id).also { onDone() } },
                trailing = if (channel.id == league.channelId) "gewählt" else null,
                icon = AppIcons.LiveTv,
            )
        }
    }
}
