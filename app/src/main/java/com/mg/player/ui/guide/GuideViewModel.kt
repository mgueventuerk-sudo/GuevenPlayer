package com.mg.player.ui.guide

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.PagingData
import androidx.paging.cachedIn
import com.mg.player.core.di.AppContainer
import com.mg.player.core.util.TimeUtils
import com.mg.player.core.util.runCatchingCancellable
import com.mg.player.data.remote.StreamUrlResolver
import com.mg.player.domain.model.Category
import com.mg.player.domain.model.Channel
import com.mg.player.domain.model.EpgProgram
import com.mg.player.domain.model.PlayRequest
import com.mg.player.domain.model.Recording
import com.mg.player.domain.model.Reminder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@Immutable
data class GuideFocus(val channel: Channel, val program: EpgProgram?)

/** Ergebnis eines Klicks im Guide. */
sealed interface GuideAction {
    data class Play(val request: PlayRequest) : GuideAction
    data class Info(val message: String) : GuideAction

    /** Kommende Sendung: Erinnerung setzen bzw. löschen. */
    data class Remind(val reminder: Reminder) : GuideAction
}

/**
 * TV-Guide (EPG-Raster): Sender untereinander, Zeit nebeneinander.
 * Das sichtbare Zeitfenster ist 3 Stunden breit und lässt sich stundenweise verschieben.
 */
class GuideViewModel(private val container: AppContainer) : ViewModel() {

    private val playlistId: StateFlow<Long?> = container.settingsRepository.settings
        .map { it.activePlaylistId }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val categories: StateFlow<List<Category>> = playlistId
        .flatMapLatest { id -> if (id == null) flowOf(emptyList()) else container.liveRepository.categories(id) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _selectedCategoryId = MutableStateFlow(Category.ALL)
    val selectedCategoryId: StateFlow<String> = _selectedCategoryId.asStateFlow()

    val channels: Flow<PagingData<Channel>> = combine(playlistId.filterNotNull(), _selectedCategoryId) { id, cat -> id to cat }
        .distinctUntilChanged()
        .flatMapLatest { (id, cat) -> container.liveRepository.channels(id, cat) }
        .cachedIn(viewModelScope)

    private val _windowStart = MutableStateFlow(defaultWindowStart())
    val windowStart: StateFlow<Long> = _windowStart.asStateFlow()

    /**
     * Sendungen je Sender als eigener State: Kommt die Antwort für einen Sender, zeichnet sich nur dessen
     * Zeile neu. Begrenzt auf die zuletzt gesehenen Sender, damit langes Scrollen den Speicher nicht füllt.
     */
    private val programStates = object : LinkedHashMap<String, MutableState<List<EpgProgram>>>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, MutableState<List<EpgProgram>>>?): Boolean = size > MAX_CACHED_CHANNELS
    }

    private val _focus = MutableStateFlow<GuideFocus?>(null)
    val focus: StateFlow<GuideFocus?> = _focus.asStateFlow()

    /** Sendungen, die gerade aufgenommen werden oder eingeplant sind (für die rote Markierung). */
    val recordingKeys: StateFlow<Set<String>> = container.recordingManager.recordings
        .map { list -> list.filter { it.isActive || it.isScheduled }.mapNotNull { it.programKey }.toSet() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptySet())

    private val loadedRanges = object : LinkedHashMap<String, LongRange>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, LongRange>?): Boolean = size > MAX_CACHED_CHANNELS
    }

    /**
     * Zählt hoch, sobald eine Aktualisierung fertig ist: Die Zeilen laden dann neu. Vorher blieb ein Guide, der während
     * des EPG-Imports geöffnet wurde, leer – das leere Ergebnis galt als "schon geladen".
     */
    val epgVersion: StateFlow<Long> = container.syncManager.state
        .map { it.finishedAtMs }
        .distinctUntilChanged()
        .onEach { loadedRanges.clear() }
        .stateIn(viewModelScope, SharingStarted.Eagerly, 0L)

    fun selectCategory(category: Category) {
        if (category.id == _selectedCategoryId.value) return
        _selectedCategoryId.value = category.id
    }

    /** State mit den Sendungen eines Senders (nur auf dem Main-Thread aufrufen). */
    fun programsFor(channelId: String): State<List<EpgProgram>> = programStates.getOrPut(channelId) { mutableStateOf(emptyList()) }

    /**
     * Lädt die Sendungen eines Senders, falls das sichtbare Fenster noch nicht abgedeckt ist. Läuft in der
     * Coroutine der Guide-Zeile: Scrollt die Zeile aus dem Bild, wird das Laden abgebrochen – schnelles
     * Scrollen erzeugt so keine Lawine von Anfragen an den Anbieter.
     */
    suspend fun loadPrograms(channel: Channel) {
        val start = _windowStart.value
        val needed = start..(start + WINDOW_MS)
        val loaded = loadedRanges[channel.id]
        if (loaded != null && needed.first >= loaded.first && needed.last <= loaded.last) return
        val from = start - 2 * HOUR
        val to = start + 8 * HOUR
        val list = try {
            container.epgRepository.programs(channel, from, to)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            emptyList()
        }
        // Leere Antwort nicht merken: Kommt das EPG später (Import läuft noch), lädt die Zeile beim nächsten Mal neu.
        if (list.isNotEmpty()) loadedRanges[channel.id] = from..to
        programsFor(channel.id).let { (it as MutableState).value = list }
    }

    fun shiftWindow(hours: Int) {
        val now = System.currentTimeMillis()
        val target = _windowStart.value + hours * HOUR
        _windowStart.value = target.coerceIn(now - 7 * DAY, now + 3 * DAY)
    }

    /** Fenster direkt auf eine Uhrzeit setzen (Handy: "Was läuft um …"). */
    fun setWindowStart(startMs: Long) {
        val now = System.currentTimeMillis()
        _windowStart.value = startMs.coerceIn(now - 7 * DAY, now + 3 * DAY)
    }

    fun resetWindow() {
        _windowStart.value = defaultWindowStart()
    }

    fun onFocus(channel: Channel, program: EpgProgram?) {
        _focus.value = GuideFocus(channel, program)
    }

    fun actionFor(channel: Channel, program: EpgProgram?): GuideAction {
        val now = System.currentTimeMillis()
        val category = _selectedCategoryId.value
        return when {
            program == null || (program.startMs <= now && program.endMs > now) ->
                GuideAction.Play(PlayRequest.Live(channel.id, category))

            program.endMs <= now && StreamUrlResolver.isInArchive(channel.catchupDays, program.startMs, now) ->
                GuideAction.Play(PlayRequest.Catchup(channel.id, category, program.startMs, program.endMs, program.title))

            program.endMs <= now -> GuideAction.Info("Für diese Sendung ist kein Archiv verfügbar")

            else -> GuideAction.Remind(Reminder(channel.id, channel.name, program.title, program.startMs))
        }
    }

    /**
     * Wie [actionFor], aber bei vergangenen Sendungen ohne Archiv auch die anderen Qualitäten desselben Senders prüfen:
     * Oft hat nur eine davon (z. B. „ZDF HD“, nicht „ZDF FHD“) Catch-up. [onAction] läuft auf dem Hauptthread.
     */
    fun resolveAction(channel: Channel, program: EpgProgram?, onAction: (GuideAction) -> Unit) {
        val direct = actionFor(channel, program)
        val now = System.currentTimeMillis()
        if (direct !is GuideAction.Info || program == null || program.endMs > now) return onAction(direct)
        viewModelScope.launch {
            val withArchive = runCatchingCancellable { container.liveRepository.variants(channel) }.getOrDefault(emptyList())
                .firstOrNull { StreamUrlResolver.isInArchive(it.catchupDays, program.startMs, now) }
            onAction(
                if (withArchive == null) {
                    direct
                } else {
                    GuideAction.Play(PlayRequest.Catchup(withArchive.id, _selectedCategoryId.value, program.startMs, program.endMs, program.title))
                },
            )
        }
    }

    /** OK auf einer kommenden Sendung: Erinnerung setzen/löschen. Liefert die Rückmeldung. */
    fun toggleReminder(reminder: Reminder): String = container.reminders.toggle(reminder)

    val reminderKeys: StateFlow<Set<String>> = container.reminders.items
        .map { list -> list.mapTo(HashSet()) { it.id } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptySet())

    /**
     * OK gedrückt halten: Laufende Sendung sofort aufnehmen, kommende Sendung einplanen
     * bzw. eine schon geplante Aufnahme wieder streichen. [onResult] liefert die Rückmeldung.
     */
    fun toggleRecording(channel: Channel, program: EpgProgram?, onResult: (String) -> Unit) {
        val manager = container.recordingManager
        viewModelScope.launch {
            val now = System.currentTimeMillis()
            val message = when {
                program == null -> "Ohne Programminfo bitte im Live-TV über „Aufnehmen“ starten"

                program.endMs <= now -> "Diese Sendung ist schon vorbei"

                program.startMs <= now -> manager.start(channel, program.title, program.endMs + POST_ROLL_MS, program.startMs)
                    ?: "Aufnahme läuft bis ${TimeUtils.formatClock(program.endMs + POST_ROLL_MS)}"

                else -> {
                    val key = Recording.programKey(channel.id, program.startMs)
                    val planned = manager.recordings.value.firstOrNull { it.isScheduled && it.programKey == key }
                    if (planned != null) {
                        manager.delete(planned.id)
                        "Geplante Aufnahme von „${program.title}“ gelöscht"
                    } else {
                        manager.schedule(channel, program)
                            ?: "Aufnahme geplant: „${program.title}“, ${TimeUtils.formatDate(program.startMs)} Uhr"
                    }
                }
            }
            onResult(message)
        }
    }

    companion object {
        const val HOUR = 3_600_000L
        const val DAY = 24 * HOUR
        const val WINDOW_MS = 3 * HOUR
        private const val POST_ROLL_MS = 2 * 60_000L
        private const val MAX_CACHED_CHANNELS = 300

        fun defaultWindowStart(): Long {
            val half = HOUR / 2
            return System.currentTimeMillis() / half * half - half
        }
    }
}
