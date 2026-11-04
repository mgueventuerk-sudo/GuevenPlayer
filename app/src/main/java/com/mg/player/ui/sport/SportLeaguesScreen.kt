package com.mg.player.ui.sport

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.mg.player.core.di.AppContainer
import com.mg.player.domain.model.SportGroups
import com.mg.player.domain.model.SportLeague
import com.mg.player.ui.common.ScreenTitle
import com.mg.player.ui.common.appViewModel
import com.mg.player.ui.common.isTouch
import com.mg.player.ui.settings.SettingsSection
import com.mg.player.ui.settings.ToggleRow
import com.mg.player.ui.theme.AppColors
import com.mg.player.ui.theme.AppIcons
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class SportLeaguesViewModel(private val container: AppContainer) : ViewModel() {
    private val sport = container.sport.repository
    private val _leagues = MutableStateFlow<List<SportLeague>?>(null)

    /** null = wird geladen (Sportmonks-Ligen kommen online, je nach Tarif). */
    val leagues: StateFlow<List<SportLeague>?> = _leagues.asStateFlow()
    val followed: StateFlow<Set<String>> = container.settingsRepository.settings.map { sport.followed(it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptySet())
    val problem: StateFlow<String?> = sport.problem

    init {
        viewModelScope.launch { _leagues.value = sport.catalog() }
    }

    fun toggle(key: String) {
        viewModelScope.launch {
            container.settingsRepository.update { s ->
                val current = sport.followed(s)
                s.copy(sportLeagues = if (key in current) current - key else current + key)
            }
        }
    }
}

/** Welchen Ligen man folgt – nur deren Spiele erscheinen in der Sport-Sektion. Änderungen werden sofort geladen. */
@Composable
fun SportLeaguesScreen() {
    val vm = appViewModel { SportLeaguesViewModel(it) }
    val leagues by vm.leagues.collectAsStateWithLifecycle()
    val followed by vm.followed.collectAsStateWithLifecycle()
    val problem by vm.problem.collectAsStateWithLifecycle()
    val touch = isTouch
    Column(Modifier.fillMaxSize().padding(start = if (touch) 16.dp else 64.dp, top = if (touch) 12.dp else 40.dp, end = if (touch) 16.dp else 64.dp)) {
        ScreenTitle("Ligen wählen")
        Text(
            "Spiele und Events der gewählten Ligen erscheinen unter „Sport“ – mit dem Sender aus deinem Zugang, den die App " +
                "selbst findet. Weitere Fußball-Ligen gibt es mit einem eigenen Bzzoiro- bzw. Sportmonks-Schlüssel.",
            style = MaterialTheme.typography.bodyMedium,
            color = AppColors.TextSecondary,
            modifier = Modifier.padding(top = 4.dp, bottom = 8.dp),
        )
        problem?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = AppColors.Danger, modifier = Modifier.padding(bottom = 8.dp)) }
        val list = leagues
        if (list == null) {
            Text("Wird geladen …", style = MaterialTheme.typography.bodyMedium, color = AppColors.TextMuted, modifier = Modifier.padding(top = 16.dp))
            return@Column
        }
        LazyColumn(
            modifier = Modifier.fillMaxSize().focusRestorer(FocusRequester.Default),
            contentPadding = PaddingValues(bottom = 40.dp),
        ) {
            // Nach Sportart statt nach Quelle; Fußball zuerst.
            val groups = list.groupBy { it.group }
            val order = SportGroups.ORDER.filter { it in groups } + groups.keys.filterNot { it in SportGroups.ORDER }.sorted()
            order.forEach { title ->
                val group = groups.getValue(title)
                item(key = "s-$title", contentType = "section") { SettingsSection(title) }
                items(group, key = { it.key }, contentType = { "league" }) { league ->
                    ToggleRow(league.name, league.key in followed, onToggle = { vm.toggle(league.key) }, icon = AppIcons.Sport)
                }
            }
        }
    }
}
