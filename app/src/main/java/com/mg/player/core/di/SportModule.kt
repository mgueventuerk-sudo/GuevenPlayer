package com.mg.player.core.di

import com.mg.player.core.device.DeviceTier
import com.mg.player.core.util.PrefsStreamStore
import com.mg.player.data.db.dao.SportDao
import com.mg.player.data.importer.EventGroupRefresh
import com.mg.player.data.sport.BzzoiroSource
import com.mg.player.data.sport.ChannelEventMatcher
import com.mg.player.data.sport.EspnSource
import com.mg.player.data.sport.SportRepository
import com.mg.player.data.sport.SportmonksSource
import com.mg.player.data.sport.guide.GuideSeries
import com.mg.player.data.sport.guide.KickoffCheck
import com.mg.player.data.sport.guide.SportChannelFinder
import com.mg.player.data.sport.guide.SportGuideStore
import com.mg.player.data.sport.proof.ProofActions
import com.mg.player.data.sport.proof.ProofHunter
import com.mg.player.data.sport.proof.ProofLook
import com.mg.player.data.sport.proof.ProofShareClient
import com.mg.player.data.sport.proof.ProofShareServer
import com.mg.player.data.sport.proof.ProofStore
import com.mg.player.data.sport.rights.SportRights
import com.mg.player.domain.model.BufferProfile
import com.mg.player.domain.model.ChannelKeyRow
import com.mg.player.domain.model.HwDecoding
import com.mg.player.domain.model.SportEvent
import com.mg.player.domain.sport.EventCountdownTicker
import com.mg.player.player.engine.StreamRequest
import com.mg.player.player.proof.ProofProbe
import java.io.File
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.map

/** Teil-Container Sport (aus [AppContainer] herausgelöst): Quellen, Zuordnung zu Sendern, öffentliches Programm. */
class SportModule(private val container: AppContainer) {
    /** Sport-Events: immer online (ESPN; Sportmonks nur mit eigenem, verschlüsselt gespeichertem Schlüssel). */
    val repository: SportRepository by lazy {
        val dao = container.database.sportDao()
        val sources = listOf(
            EspnSource(container.httpFetcher),
            SportmonksSource(container.httpFetcher) { container.settingsRepository.current().sportmonksToken },
            BzzoiroSource(
                container.httpFetcher,
                token = { container.settingsRepository.current().bzzoiroToken },
                countries = { container.settingsRepository.current().sportCountries },
            ),
            GuideSeries(finder::guide),
        )
        SportRepository(
            dao,
            container.settingsRepository,
            sources,
            ChannelEventMatcher(dao, finder, proofs),
            container.appScope,
            hasPlaylists = { container.database.playlistDao().all().isNotEmpty() },
            // Nach jeder Aktualisierung – und nach jedem Neuaufbau von „Alle Zugänge“, der erst danach fertig wird
            // (Protokoll 04.11.: „162 Events, 0 mit Sender“ direkt nach dem Aufbau).
            contentUpdated = kotlinx.coroutines.flow.combine(
                container.syncManager.state.map { it.finishedAtMs },
                container.combined.builder.stats.map { it?.atMs ?: 0L },
            ) { synced, combined -> synced + combined },
            kickoff = KickoffCheck(dao, container.epgRepository::liveShortEpg, refreshGroups = eventGroupRefresh::refresh),
            proofs = proofs,
            hunter = hunter,
        )
    }

    /** Sport- und Event-Sender der Zugänge sowie das öffentliche Sport-Programm. */
    private val finder: SportChannelFinder by lazy {
        SportChannelFinder(
            container.database.sportDao(),
            guideStore,
            guideEnabled = { container.settingsRepository.current().let { it.onlineExtras && it.sportGuide } },
        )
    }

    /**
     * Sender, in denen ein Spiel laufen könnte (Plan „Spiel-Finder“ D/I): erst die bisherigen Kandidaten (sicherste zuerst),
     * dann die Sport-Sender der Familien mit Rechten (Rechte-Tabelle), höchstens [limit]. Für Suche und Mosaik.
     */
    suspend fun candidateChannels(event: SportEvent, limit: Int): List<ChannelKeyRow> {
        val dao = container.database.sportDao()
        val matched = dao.matchesFor(listOf(event.id)).sortedByDescending { it.score }.map { it.channelId }
        val families = SportRights.familiesFor(event, container.settingsRepository.current().sportCountries)
        val sport = finder.index().rows.filter { families.isNotEmpty() && SportRights.familyOf(it.name) in families }.map { it.id }
        val ids = (matched + sport).distinct().take(limit)
        val rows = dao.channelsByIds(ids).associateBy { it.id }
        return ids.mapNotNull(rows::get)
    }

    /** Beweise je Spiel und Sender (Bild, Zuschauen, „Falscher Sender“), über Neustarts hinweg. */
    val proofs: ProofStore by lazy { ProofStore(PrefsStreamStore(container.appContext, "sport_proofs")) }

    /** Beweise aus deiner Bedienung (Zuschauen, Mosaik, „Falscher Sender“). */
    val proofActions: ProofActions by lazy { ProofActions(repository, container.database.sportDao(), proofs) }

    /** Ein Blick in einen Sender (stumm, unsichtbar); auf schwachen Geräten ohne Texterkennung (nur Standbild-Prüfung). */
    private val probe: ProofProbe by lazy {
        ProofProbe(container.playback.engineFactory, readText = DeviceTier.of(container.appContext) != DeviceTier.LOW)
    }

    /** Sucht Beweise im Bild (im Vordergrund und im Hintergrund-Dienst; nie beides gleichzeitig). */
    val hunter: ProofHunter by lazy { hunter(container.database.sportDao()) }

    private fun hunter(dao: SportDao) = ProofHunter(
        dao = dao,
        proofs = proofs,
        look = { channelId -> probeChannel(channelId) },
        access = ProofHunter.Access(
            connections = { container.connectionMonitor.status.value },
            recheck = { playlistId -> container.connectionMonitor.checkNow(playlistId) },
            usedHere = container.connectionMonitor::recentlyUsedHere,
            accountOf = { playlistId -> accountKeyOf(playlistId) },
        ),
        inputs = ProofHunter.Inputs(
            scores = { repository.scores.value },
            countries = { container.settingsRepository.current().sportCountries },
            extra = { event -> candidateChannels(event, SWEEP_LIMIT) },
        ),
    )

    /** Prüfgerät: Beweise im Heimnetz teilen (Plan „Spiel-Finder“ O). */
    private val shareServer: ProofShareServer by lazy { ProofShareServer(container.appContext, proofs, release = { hunter.release(it) }) }

    /** Stick/Handy: Beweise vom Prüfgerät holen und „Ich brauche diese Playlist“ melden. */
    private val shareClient: ProofShareClient by lazy { ProofShareClient(container.appContext, container.httpFetcher, proofs) }

    /** Kennung des Zugangs einer Playlist, auf allen Geräten gleich (Adresse + Benutzer, gehasht). */
    private suspend fun accountKeyOf(playlistId: Long): String? {
        val playlist = container.database.playlistDao().get(playlistId) ?: return null
        val user = playlist.username.takeIf { it.isNotBlank() } ?: return null
        return ProofShareServer.accountKey(playlist.serverUrl, user)
    }

    /**
     * Läuft, solange die App lebt: Auf dem Prüfgerät den Heimnetz-Dienst bereitstellen (Code beim ersten Mal erzeugen),
     * auf den anderen Geräten jede Minute die Beweise vom Prüfgerät holen.
     */
    suspend fun shareLoop() {
        while (true) {
            val s = container.settingsRepository.current()
            if (s.sportProofDevice) {
                val code = s.sportHubCode.ifBlank {
                    ProofShareServer.newCode().also { c -> container.settingsRepository.update { it.copy(sportHubCode = c) } }
                }
                shareServer.start(code)
            } else {
                shareServer.stop()
                pullFromHub(s.sportHubJoinCode)
            }
            delay(SHARE_INTERVAL_MS)
        }
    }

    private suspend fun pullFromHub(code: String) {
        val changed = shareClient.pull(code)
        if (changed.isEmpty()) return
        val board = repository.board.value ?: return
        val events = (board.live + board.upcoming.values.flatten()).map { it.event }.filter { it.id in changed }
        if (events.isNotEmpty()) repository.rematch(events)
    }

    /** Vor dem Abspielen über [playlistId]: Das Prüfgerät gibt den Zugang frei (Regel „Freie Playlist“ 4). */
    suspend fun releaseForViewing(playlistId: Long) {
        val s = container.settingsRepository.current()
        val account = accountKeyOf(playlistId) ?: return
        if (s.sportProofDevice) hunter.release(account) else shareClient.release(s.sportHubJoinCode, account)
    }

    /** Stream-Adresse des Senders auflösen und hineinsehen; null = nicht abspielbar. */
    private suspend fun probeChannel(channelId: String): ProofLook? {
        val channel = container.liveRepository.channel(channelId) ?: return null
        val url = container.streamUrlResolver.resolve(channel.streamUrl, channel.id)
        val request = StreamRequest(
            url = url,
            title = channel.name,
            startPositionMs = 0,
            isLive = true,
            bufferProfile = BufferProfile.FAST,
            hwDecoding = HwDecoding.AUTO,
            allowInsecureHttp = container.streamUrlResolver.isHttpAllowed(channel.id),
        )
        return probe.look(request)
    }

    /** 15 min vor Anstoß: Event- und Sport-Gruppen der Xtream-Zugänge neu abfragen (umbenannte Event-Sender). */
    val eventGroupRefresh: EventGroupRefresh by lazy {
        EventGroupRefresh(
            container.database,
            container.xtreamApi,
            busy = { container.syncManager.state.value.running },
            onChanged = { playlistId, changed ->
                // Sofort (nicht später): So fällt das Neuladen der Senderliste in dasselbe Fenster wie die Namen.
                container.channelGroupIndex.rebuild(playlistId)
                container.searchIndexer.updateChannels(playlistId, changed.map { it.id to it.name })
            },
        )
    }

    /** Öffentliches Sport-Programm (nur Sport, gefiltert) im Cache – löschbar über den Schalter in den Einstellungen. */
    val guideStore: SportGuideStore by lazy { SportGuideStore(File(container.appContext.cacheDir, "sport_guide"), container.httpFetcher::openMaybeGzip) }
    val countdownTicker: EventCountdownTicker by lazy { EventCountdownTicker(container.appScope) }

    private companion object {
        /** So viele Sender je Spiel höchstens bei der Suche über die Sport-Gruppen. */
        const val SWEEP_LIMIT = 10
        const val SHARE_INTERVAL_MS = 60_000L
    }
}
