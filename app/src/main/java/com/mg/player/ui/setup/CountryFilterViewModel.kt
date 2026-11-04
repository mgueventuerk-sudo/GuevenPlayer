package com.mg.player.ui.setup

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mg.player.core.di.AppContainer
import com.mg.player.data.filter.CountrySelection
import com.mg.player.data.filter.CountrySummary
import java.util.Locale
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

@Immutable
data class CountryFilterUi(
    val loading: Boolean = true,
    val summary: CountrySummary? = null,
    val selected: Set<String> = emptySet(),
    val keepUnknown: Boolean = true,
    val applying: Boolean = false,
    val hint: String? = null,
)

/** Länderauswahl für den aktiven Zugang – direkt nach dem Einrichten oder später aus den Einstellungen. */
class CountryFilterViewModel(private val container: AppContainer, private val firstRun: Boolean, private val forPlaylist: Long? = null) : ViewModel() {

    private val _ui = MutableStateFlow(CountryFilterUi())
    val ui: StateFlow<CountryFilterUi> = _ui.asStateFlow()

    /** true = Screen kann geschlossen werden (übernommen, übersprungen oder nichts zu wählen). */
    private val _done = MutableStateFlow(false)
    val done: StateFlow<Boolean> = _done.asStateFlow()

    private var playlistId: Long? = null

    init {
        viewModelScope.launch {
            val id = forPlaylist ?: container.settingsRepository.current().activePlaylistId
            if (id == null) {
                _done.value = true
                return@launch
            }
            playlistId = id
            val filter = container.countryFilter
            val summary = filter.summary(id)
            // Nur ein Land (oder gar keins) erkannt: Es gibt nichts auszuwählen.
            if (firstRun && summary.countries.size < 2) {
                container.syncManager.refreshEpg(id)
                _done.value = true
                return@launch
            }
            val existing = filter.selection(id)
            val codes = summary.countries.map { it.code }.toSet()
            val preselected = existing?.countries ?: defaultFor(Locale.getDefault().language)
            _ui.value = CountryFilterUi(
                loading = false,
                summary = summary,
                selected = preselected.filter { it in codes }.toSet(),
                keepUnknown = existing?.keepUnknown ?: true,
            )
        }
    }

    fun toggle(code: String) = _ui.update { state ->
        val next = if (code in state.selected) state.selected - code else state.selected + code
        state.copy(selected = next, hint = null)
    }

    fun toggleUnknown() = _ui.update { it.copy(keepUnknown = !it.keepUnknown, hint = null) }

    fun apply() {
        val state = _ui.value
        val id = playlistId ?: return
        if (state.selected.isEmpty()) {
            _ui.update { it.copy(hint = "Bitte mindestens ein Land auswählen – oder „Alles anzeigen“ wählen.") }
            return
        }
        _ui.update { it.copy(applying = true) }
        viewModelScope.launch {
            container.countryFilter.apply(id, CountrySelection(state.selected, state.keepUnknown))
            // EPG neu laden: nur sichtbare Gruppen bekommen Programmdaten.
            container.syncManager.refreshEpg(id)
            _done.value = true
        }
    }

    fun showAll() {
        val id = playlistId
        if (id == null) {
            _done.value = true
            return
        }
        _ui.update { it.copy(applying = true) }
        viewModelScope.launch {
            container.countryFilter.clear(id)
            container.syncManager.refreshEpg(id)
            _done.value = true
        }
    }

    private fun defaultFor(language: String): Set<String> = when (language) {
        "de" -> setOf("DE")
        "tr" -> setOf("TR")
        "ar" -> setOf("AR")
        "fr" -> setOf("FR")
        "it" -> setOf("IT")
        "es" -> setOf("ES")
        "en" -> setOf("UK", "US", "EN")
        else -> emptySet()
    }
}
