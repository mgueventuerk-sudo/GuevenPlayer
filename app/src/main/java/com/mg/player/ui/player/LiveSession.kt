package com.mg.player.ui.player

import com.mg.player.core.di.AppContainer
import com.mg.player.core.util.runCatchingCancellable
import com.mg.player.domain.model.Category
import com.mg.player.domain.model.Channel
import com.mg.player.domain.model.ContentType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Live-TV-Logik des Players: Senderliste für Schnell-Zapping, Sendernummer per Zifferntasten,
 * vorheriger Sender, Catch-up ("Von Anfang an"), EPG-Info, Favorit und "Zuletzt gesehen".
 */
class LiveSession(
    private val container: AppContainer,
    private val scope: CoroutineScope,
    /** Zustand des Players, den die Live-Logik mitpflegt (Sendernummer, EPG, Favorit). */
    private val ui: MutableStateFlow<PlayerUiState>,
    private val start: suspend (Playable) -> Unit,
) {

    private val resolver = PlayableResolver(container)
    private var channelIds: List<String> = emptyList()
    private var pendingIndex: Int? = null
    private var currentChannelId: String? = null
    private var previousChannelId: String? = null
    private var zapJob: Job? = null
    private var epgJob: Job? = null
    private var favoriteJob: Job? = null

    val hasPreviousChannel: Boolean get() = previousChannelId != null

    /** Ein Senderwechsel ist angestoßen, der neue Stream aber noch nicht gestartet. */
    val zapPending: Boolean get() = pendingIndex != null && zapJob?.isActive == true

    /** Zugang der aktuellen Senderliste (nicht zwingend der aktive – z. B. Treffer aus der Suche). */
    var listPlaylistId: Long? = null
        private set

    /** Kategorie der aktuellen Senderliste (fällt auf „Alle“ zurück, wenn die gewünschte leer ist). */
    var listCategoryId: String? = null
        private set

    suspend fun loadChannelList(playlistId: Long?, categoryId: String) {
        if (playlistId == null) return
        listPlaylistId = playlistId
        listCategoryId = categoryId
        channelIds = container.liveRepository.channelIds(playlistId, categoryId)
        if (channelIds.isEmpty()) {
            listCategoryId = Category.ALL
            channelIds = container.liveRepository.channelIds(playlistId, Category.ALL)
        }
    }

    fun onChannelStarted(playable: Playable, categoryId: String?) {
        val channel = playable.channel ?: return
        pendingIndex = null
        if (currentChannelId != null && currentChannelId != channel.id) previousChannelId = currentChannelId
        currentChannelId = channel.id
        val index = listIndexOf(playable)
        ui.update {
            it.copy(
                channelNumber = if (index >= 0) index + 1 else null,
                channelCount = channelIds.size,
                nowProgram = null,
                nextProgram = null,
            )
        }
        scope.launch {
            // Erst nach ein paar Sekunden auf dem Sender merken: Beim Durchzappen schrieb sonst jeder Sender die ganze
            // Einstellungsdatei neu (Flash-Schreiblast auf Sticks) und landete im Verlauf.
            delay(SETTLE_MS)
            if (currentChannelId != channel.id) return@launch
            container.playbackRepository.markChannelWatched(channel.id)
            // „Ohne Verlauf“: auch den letzten Sender nicht merken.
            if (!container.settingsRepository.current().noHistory) {
                container.settingsRepository.update {
                    it.copy(lastChannelId = channel.id, lastChannelCategoryId = categoryId ?: it.lastChannelCategoryId)
                }
            }
            // „Oft gesehen“: erst nach zwei Minuten auf demselben Sender zählen (Durchzappen zählt nicht).
            delay(HABIT_MS - SETTLE_MS)
            if (currentChannelId == channel.id && !container.settingsRepository.current().noHistory) {
                withContext(Dispatchers.IO) { container.channelHabits.record(channel.id) }
            }
        }
        observeChannel(channel)
        prepareNeighbours(channel.id)
    }

    /**
     * Senderwechsel ohne Warten: Die Nachbarsender (±1) werden kurz nach dem Start schon aufgelöst (Datenbank,
     * Varianten, Stream-Adresse, Namensauflösung). Ein zweiter Stream wird bewusst NICHT geöffnet – viele Anbieter
     * erlauben nur eine Verbindung.
     */
    private fun prepareNeighbours(channelId: String) {
        prepareJob?.cancel()
        prepareJob = scope.launch {
            delay(PREPARE_DELAY_MS)
            val index = channelIds.indexOf(channelId)
            if (index < 0 || channelIds.size < 2) return@launch
            val ids = listOf(index + 1, index - 1).map { channelIds[Math.floorMod(it, channelIds.size)] }.distinct()
            prepared.keys.retainAll(ids.toSet())
            for (id in ids) {
                val playable = runCatchingCancellable { resolver.channel(id) }.getOrNull() ?: continue
                prepared[id] = System.currentTimeMillis() to playable
                container.warmUpHost(playable.url)
            }
        }
    }

    private suspend fun preparedOrResolve(channelId: String): Playable? =
        prepared.remove(channelId)?.takeIf { System.currentTimeMillis() - it.first < PREPARED_TTL_MS }?.second ?: resolver.channel(channelId)

    private val prepared = HashMap<String, Pair<Long, Playable>>()
    private var prepareJob: Job? = null

    /** Auch bei Catch-up sollen EPG-Info und Favorit des Senders sichtbar sein. */
    fun observeChannel(channel: Channel) {
        favoriteJob?.cancel()
        favoriteJob = scope.launch {
            container.playbackRepository.isFavorite(ContentType.LIVE, channel.id).collect { fav ->
                ui.update { it.copy(isFavorite = fav) }
            }
        }
        epgJob?.cancel()
        epgJob = scope.launch {
            // Erst zum Ende der laufenden Sendung neu laden (statt jede Minute ein Fenster von 11 Stunden).
            while (true) delay(nextEpgReloadMs(loadEpg(channel), System.currentTimeMillis()))
        }
    }

    /**
     * Schnell-Zapping: Sendername und Nummer erscheinen sofort, der Stream startet nach
     * kurzer Pause – so kann man mit gedrückter Taste durch viele Sender springen.
     */
    fun zap(delta: Int) {
        if (channelIds.isEmpty()) return
        val playable = ui.value.playable?.takeIf { it.channel != null } ?: return
        val base = pendingIndex ?: listIndexOf(playable).coerceAtLeast(0)
        switchToIndex(Math.floorMod(base + delta, channelIds.size))
    }

    /**
     * Stelle des laufenden Senders in der Liste (-1 = nicht drin) – auch wenn gerade eine andere Quelle desselben Senders läuft (Start aus
     * Suche/Favoriten mit einer Variante, die nicht als Listeneintrag steht). Früher sprang Umschalten dann an den
     * Listenanfang.
     */
    private fun listIndexOf(playable: Playable): Int {
        val ids = listOfNotNull(playable.channel?.id) + playable.variants.map { it.id }
        return ids.asSequence().map { channelIds.indexOf(it) }.firstOrNull { it >= 0 } ?: -1
    }

    /** Sendernummer über die Zifferntasten (1 = erster Sender der aktuellen Liste). */
    fun jumpToNumber(number: Int): Boolean {
        if (number < 1 || number > channelIds.size) return false
        switchToIndex(number - 1)
        return true
    }

    fun switchToPrevious(): Boolean {
        val previous = previousChannelId ?: return false
        val index = channelIds.indexOf(previous)
        if (index >= 0) switchToIndex(index) else launchStart { resolver.channel(previous) }
        return true
    }

    /** Direkt zu einem Sender aus der eingeblendeten Senderliste. */
    fun zapTo(channelId: String) {
        val index = channelIds.indexOf(channelId)
        if (index >= 0) {
            switchToIndex(index)
            return
        }
        launchStart { resolver.channel(channelId) }
    }

    /**
     * Jeder Start (Zappen, Catch-up, zurück ins Live-Bild) läuft über denselben Job: Ein neuer bricht den
     * vorherigen ab. Vorher konnten sich "Live" und ein schneller Senderwechsel überholen – dann gewann der
     * langsamere, also oft der ältere Wunsch.
     */
    private fun launchStart(resolve: suspend () -> Playable?) {
        pendingIndex = null
        zapJob?.cancel()
        zapJob = scope.launch { resolve()?.let { start(it) } }
    }

    private fun switchToIndex(index: Int) {
        pendingIndex = index
        ui.update { it.copy(channelNumber = index + 1) }
        zapJob?.cancel()
        zapJob = scope.launch {
            val playable = preparedOrResolve(channelIds[index]) ?: return@launch
            ui.update { it.copy(playable = playable, nowProgram = null, nextProgram = null) }
            delay(ZAP_DELAY_MS)
            start(playable)
        }
    }

    /** "Von Anfang an": laufende Sendung aus dem Archiv neu starten. */
    fun restartProgram(): Boolean {
        val channel = ui.value.playable?.channel ?: return false
        val program = ui.value.nowProgram ?: return false
        if (!channel.hasArchive) return false
        launchStart { resolver.catchup(channel.id, program.startMs, program.endMs, program.title) }
        return true
    }

    /**
     * Live zurückspulen: Archiv ab „jetzt minus [minutes]“ abspielen (der Anbieter startet dort – kein Spulen im
     * Stream nötig). Ein zweites Mal geht weitere [minutes] zurück.
     */
    fun rewind(minutes: Int): Boolean {
        val playable = ui.value.playable ?: return false
        val channel = playable.channel ?: return false
        if (!channel.hasArchive) return false
        val now = System.currentTimeMillis()
        val from = (rewoundFrom?.takeIf { playable.kind == PlayableKind.CATCHUP } ?: now) - minutes * 60_000L
        rewoundFrom = from
        val program = ui.value.nowProgram
        val end = maxOf(program?.endMs ?: 0L, now + HOUR)
        val title = program?.title ?: channel.name
        launchStart { resolver.catchup(channel.id, from, end, title) }
        return true
    }

    private var rewoundFrom: Long? = null

    /** Aus Catch-up zurück ins Live-Bild. */
    fun goLive() {
        rewoundFrom = null
        val channel = ui.value.playable?.channel ?: return
        launchStart { resolver.channel(channel.id) }
    }

    /** Lädt „Jetzt/Danach“ und liefert das Ende der laufenden Sendung (null = keine Programminfo). */
    private suspend fun loadEpg(channel: Channel): Long? {
        val now = System.currentTimeMillis()
        val programs = container.epgRepository.programs(channel, now - 3 * HOUR, now + 8 * HOUR)
        val current = programs.firstOrNull { it.startMs <= now && it.endMs > now }
        val next = programs.firstOrNull { it.startMs >= (current?.endMs ?: now) }
        ui.update { if (it.playable?.channel?.id == channel.id) it.copy(nowProgram = current, nextProgram = next) else it }
        return current?.endMs
    }

    private companion object {
        const val ZAP_DELAY_MS = 250L
        const val HOUR = 3_600_000L
        const val HABIT_MS = 120_000L

        /** So lange muss ein Sender laufen, bevor er als „zuletzt gesehen“ bzw. letzter Sender gespeichert wird. */
        const val SETTLE_MS = 3_000L
        const val PREPARE_DELAY_MS = 1_500L
        const val PREPARED_TTL_MS = 90_000L
    }
}

/**
 * Nächstes Nachladen des EPG im Player: kurz nach Sendungsende (mindestens 30 s, höchstens 15 min);
 * ohne Programminfo alle 5 Minuten (das EPG kann noch im Hintergrund laden).
 */
internal fun nextEpgReloadMs(currentEndMs: Long?, nowMs: Long): Long =
    if (currentEndMs == null) 5 * 60_000L else (currentEndMs - nowMs + 2_000L).coerceIn(30_000L, 15 * 60_000L)
