package com.mg.player.ui.live

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.PagingData
import androidx.paging.cachedIn
import com.mg.player.core.device.DeviceTier
import com.mg.player.core.di.AppContainer
import com.mg.player.core.log.AppLog
import com.mg.player.core.vpn.tunnelUps
import com.mg.player.domain.model.Category
import com.mg.player.domain.model.Channel
import com.mg.player.domain.model.ContentType
import com.mg.player.domain.model.EpgProgram
import com.mg.player.domain.model.PlayerEngineType
import com.mg.player.domain.model.forAutomaticOptimization
import com.mg.player.domain.model.playlistIdOf
import com.mg.player.player.engine.StreamRequest
import com.mg.player.player.engine.VideoPlayerEngine
import com.mg.player.ui.player.EngineFallback
import com.mg.player.ui.player.PlayableResolver
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class LiveViewModel(private val container: AppContainer) : ViewModel() {

    private val settingsRepo = container.settingsRepository
    private val liveRepo = container.liveRepository
    private val playableResolver = PlayableResolver(container)

    private val playlistId: StateFlow<Long?> = settingsRepo.settings
        .map { it.activePlaylistId }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val categories: StateFlow<List<Category>> = playlistId
        .flatMapLatest { id -> if (id == null) flowOf(emptyList()) else liveRepo.categories(id) }
        // Startwert aus dem Vorab-Laden in resolveStart (Plan B2): „Alle Sender“ steht im ersten Bild.
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), container.startCache.takeLiveCategories())

    private val _selectedCategoryId = MutableStateFlow(Category.ALL)
    val selectedCategoryId: StateFlow<String> = _selectedCategoryId.asStateFlow()

    /** Ab welcher Position die Liste geladen wird (nach „Zurück“ rund um den zuletzt gesehenen Sender). */
    private val startAt = MutableStateFlow(0)

    val channels: Flow<PagingData<Channel>> = combine(playlistId.filterNotNull(), _selectedCategoryId, startAt) { id, cat, at -> Triple(id, cat, at) }
        .distinctUntilChanged()
        .flatMapLatest { (id, cat, at) -> liveRepo.channels(id, cat, at) }
        .cachedIn(viewModelScope)

    private val _focusedChannel = MutableStateFlow<Channel?>(null)
    val focusedChannel: StateFlow<Channel?> = _focusedChannel.asStateFlow()

    val epg: StateFlow<List<EpgProgram>> = _focusedChannel
        .debounce(300)
        .mapLatest { channel ->
            if (channel == null) {
                emptyList()
            } else {
                val now = System.currentTimeMillis()
                // Ein Datenbank- oder Netzfehler darf die Vorschau-Infos nicht für immer beenden.
                try {
                    container.epgRepository.programs(channel, now - 2 * HOUR, now + 12 * HOUR).filter { it.endMs > now }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    AppLog.error("EPG", "Programm für ${channel.name} nicht ladbar", e)
                    emptyList()
                }
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    // Kurz verzögert: Beim schnellen Durchblättern nicht für jeden Sender eine Datenbank-Beobachtung starten.
    val isFavorite: StateFlow<Boolean> = _focusedChannel
        .debounce(150)
        .flatMapLatest { ch -> if (ch == null) flowOf(false) else container.playbackRepository.isFavorite(ContentType.LIVE, ch.id) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    val syncState = container.syncManager.state

    // --- Live-Mini-Vorschau ---
    private val previewActive = MutableStateFlow(false)
    private val _previewEngine = MutableStateFlow<VideoPlayerEngine?>(null)
    val previewEngine: StateFlow<VideoPlayerEngine?> = _previewEngine.asStateFlow()
    private val _previewResolution = MutableStateFlow<PreviewResolution?>(null)
    internal val previewResolution: StateFlow<PreviewResolution?> = _previewResolution.asStateFlow()

    /** Was die Vorschau gerade spielt (für die Übergabe an den Vollbild-Player). */
    private var previewChannel: Channel? = null
    private var previewUrl: String? = null
    private var previewType: PlayerEngineType? = null

    /** Zugang, über den die Vorschau gerade streamt (für „eigene Nutzung“). */
    private var previewAccount: Long? = null
    private var previewStatsJob: Job? = null
    private var categoryJob: Job? = null

    /** Sender, auf dem die Liste nach „Zurück“ aus dem Vollbild steht: der zuletzt gesehene, auch nach dem Umschalten. */
    private val _restoreTarget = MutableStateFlow<String?>(null)
    val restoreTarget: StateFlow<String?> = _restoreTarget.asStateFlow()
    private var awaitingReturn = false
    private var returnTarget: String? = null

    init {
        reconnectPreviewOnTunnel()
        viewModelScope.launch {
            // Im Vollbild umgeschaltet: Die Liste folgt (Kategorie und Position), solange der Player offen ist.
            settingsRepo.settings.map { it.lastChannelId to it.lastChannelCategoryId }.distinctUntilChanged().collect { (id, cat) ->
                if (awaitingReturn && id != null) followPlayer(id, cat)
            }
        }
        viewModelScope.launch {
            combine(previewActive, _focusedChannel.debounce(700)) { active, ch -> if (active) ch else null }
                .distinctUntilChanged { a, b -> a?.id == b?.id }
                .collectLatest { channel ->
                    if (channel == null) {
                        releasePreview()
                    } else {
                        // Eine fehlgeschlagene Vorschau (Adresse nicht auflösbar, Engine-Fehler) darf weder die App
                        // beenden noch die Beobachtung abbrechen – sonst gäbe es bis zum Neustart keine Vorschau mehr.
                        try {
                            startPreview(channel)
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            AppLog.error("Vorschau", "Vorschau für ${channel.name} fehlgeschlagen", e)
                            releasePreview()
                        }
                    }
                }
        }
    }

    /** VPN steht (wieder): Vorschau neu verbinden, damit sie durch den Tunnel läuft. */
    private fun reconnectPreviewOnTunnel() {
        viewModelScope.launch { container.vpn.tunnelUps().collect { if (previewActive.value) previewChannel?.let { startPreview(it) } } }
    }

    /**
     * Startet die Vorschau. Alles, was unterbrechen kann (Einstellungen, Adresse), passiert vorher; danach wird
     * ohne weitere Unterbrechung geprüft, ob die Vorschau noch gewünscht ist, und erst dann eine Engine erzeugt.
     * So kann sie nach dem Öffnen des Vollbild-Players nicht mehr unsichtbar weiterlaufen.
     */
    private suspend fun startPreview(channel: Channel) {
        previewStatsJob?.cancel()
        _previewResolution.value = null
        val settings = settingsRepo.settings.first().forAutomaticOptimization(DeviceTier.of(container.appContext))
        // Vollbild und Vorschau müssen dieselbe Qualitätsvariante auflösen, sonst passt die
        // URL-Prüfung der Engine-Übergabe nicht und der Player verbindet sich erneut.
        val playable = playableResolver.channel(channel.id) ?: return
        val url = playable.url
        // Die Vorschau belegt eine Verbindung des Zugangs – das ist dieses Gerät, kein fremdes.
        previewAccount = playable.activePlaylistId ?: playlistIdOf(playable.id)
        previewAccount?.let(container.connectionMonitor::markOwnUse)
        currentCoroutineContext().ensureActive()
        if (!previewActive.value) return
        val type = EngineFallback.engineFor(settings.liveEngine, playable, preview = true)
        // Zurück aus dem Vollbild: Läuft genau dieser Sender dort noch, spielt er hier einfach weiter.
        container.playback.previewHandoff.take(channel.id, url, type)?.let { handed ->
            _previewEngine.value?.release()
            _previewEngine.value = handed
            previewChannel = channel
            previewUrl = url
            previewType = type
            observePreviewResolution(channel, handed)
            return
        }
        val engine = _previewEngine.value?.takeIf { previewType == type }
            ?: container.playback.engineFactory.create(type).also {
                _previewEngine.value?.release()
                _previewEngine.value = it
            }
        previewChannel = channel
        previewUrl = url
        previewType = type
        engine.prepare(
            StreamRequest(
                url = url,
                title = channel.name,
                startPositionMs = 0,
                isLive = true,
                bufferProfile = settings.bufferProfile,
                hwDecoding = settings.hwDecoding,
                audioPassthrough = settings.audioPassthrough,
                allowInsecureHttp = playable.allowInsecureHttp,
            ),
        )
        observePreviewResolution(channel, engine)
    }

    /** Pollt nur während der Live-Vorschau und zeigt ausschließlich echte Decodermaße an. */
    private fun observePreviewResolution(channel: Channel, engine: VideoPlayerEngine) {
        previewStatsJob?.cancel()
        previewStatsJob = viewModelScope.launch {
            while (previewActive.value && previewChannel?.id == channel.id && _previewEngine.value === engine) {
                val stats = runCatching { engine.stats() }.getOrNull()
                // Läuft die Vorschau lange, bleibt die eigene Nutzung frisch (Schonfrist zählt ab jetzt).
                previewAccount?.let(container.connectionMonitor::markOwnUse)
                _previewResolution.value = measuredPreviewResolution(channel.id, stats?.width, stats?.height)
                delay(1_000)
            }
        }
    }

    private fun releasePreview() {
        // Ende der Vorschau: Der Anbieter zählt die Verbindung noch kurz mit – als eigene Nutzung vermerken.
        previewAccount?.let(container.connectionMonitor::markOwnUse)
        previewStatsJob?.cancel()
        previewStatsJob = null
        _previewResolution.value = null
        _previewEngine.value?.release()
        _previewEngine.value = null
        previewChannel = null
    }

    /**
     * Vollbild für [channel]: Läuft genau dieser Sender in der Vorschau, wird die Engine dem Player übergeben statt
     * beendet – der Sender spielt ohne neues Laden weiter. Die Verbindung zählt als eigene Nutzung (keine Nachfrage
     * „Zugang belegt“ wegen der eigenen Vorschau).
     */
    fun handOffPreview(channel: Channel) {
        val engine = _previewEngine.value ?: return
        val url = previewUrl ?: return
        val type = previewType ?: return
        if (previewChannel?.id != channel.id) return
        previewStatsJob?.cancel()
        previewStatsJob = null
        _previewResolution.value = null
        container.connectionMonitor.markOwnUse(channel.playlistId)
        container.playback.previewHandoff.offer(channel.id, url, type, engine)
        _previewEngine.value = null
        previewChannel = null
    }

    /** Sender aus der Liste im Vollbild öffnen; [withPreview] = die Vorschau übernimmt ihn beim Zurückkommen. */
    fun onOpenChannel(channel: Channel, withPreview: Boolean) {
        awaitingReturn = true
        returnTarget = channel.id
        container.playback.previewHandoff.expectReturn = withPreview
    }

    /** Die Senderliste wird (wieder) angezeigt: Kommt sie aus dem Vollbild zurück, auf den Sender springen. */
    fun onListShown() {
        if (awaitingReturn) _restoreTarget.value = returnTarget
    }

    /** Die Liste steht wieder auf dem Sender (Fokus gesetzt). */
    fun onRestored() {
        awaitingReturn = false
        returnTarget = null
        _restoreTarget.value = null
    }

    private suspend fun followPlayer(channelId: String, categoryId: String?) {
        val playlist = playlistId.value ?: return
        val category = categoryId?.takeIf { canReopen(it) } ?: _selectedCategoryId.value
        val position = try {
            liveRepo.channelIds(playlist, category).indexOf(channelId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AppLog.error("Live", "Position von $channelId nicht ermittelbar", e)
            -1
        }
        if (position < 0) return // z. B. eine andere Qualität des Senders: Liste bleibt, wie sie war
        categoryJob?.cancel()
        _selectedCategoryId.value = category
        startAt.value = (position - ROWS_ABOVE_TARGET).coerceAtLeast(0)
        returnTarget = channelId
    }

    /** Gesperrte Gruppe nicht ungefragt wieder öffnen – sonst wäre ihr Inhalt ohne PIN sichtbar. */
    private suspend fun canReopen(categoryId: String): Boolean {
        val pin = settingsRepo.current().parentalPinHash
        return pin == null || container.parentalControl.unlocked.value || !container.categoryRepository.isLocked(categoryId)
    }

    fun setPreviewActive(active: Boolean) {
        previewActive.value = active
        if (!active) releasePreview()
    }

    fun onCategoryFocused(category: Category) {
        if (!container.parentalControl.isAccessible(category)) return
        categoryJob?.cancel()
        categoryJob = viewModelScope.launch {
            delay(250)
            selectCategory(category.id)
        }
    }

    fun onCategoryClicked(category: Category) {
        categoryJob?.cancel()
        selectCategory(category.id)
    }

    /** Leere Favoriten/Sehgewohnheiten: zurück zu allen Sendern. */
    fun showAll() {
        categoryJob?.cancel()
        selectCategory(Category.ALL)
    }

    private fun selectCategory(id: String) {
        if (_selectedCategoryId.value == id) return
        startAt.value = 0
        _selectedCategoryId.value = id
    }

    fun onChannelFocused(channel: Channel) {
        _focusedChannel.value = channel
    }

    fun toggleFavorite() = toggleFavorite(_focusedChannel.value) {}

    /** Aktueller Favoriten-Status (für die Beschriftung im Kontextmenü). */
    suspend fun isFavoriteNow(channel: Channel): Boolean = container.playbackRepository.isFavorite(ContentType.LIVE, channel.id).first()

    /** Schaltet den Favoriten-Status um und meldet den neuen Zustand zurück (für die Bestätigung). */
    fun toggleFavorite(channel: Channel?, onResult: (Boolean) -> Unit) {
        channel ?: return
        viewModelScope.launch {
            val repo = container.playbackRepository
            repo.toggleFavorite(ContentType.LIVE, channel.id)
            onResult(repo.isFavorite(ContentType.LIVE, channel.id).first())
        }
    }

    override fun onCleared() {
        releasePreview()
    }

    private companion object {
        const val HOUR = 3_600_000L

        /** So viele Sender stehen beim Zurückkommen noch über dem zuletzt gesehenen. */
        const val ROWS_ABOVE_TARGET = 4
    }
}
