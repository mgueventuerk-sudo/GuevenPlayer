package com.mg.player.core.di

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
import com.mg.player.domain.sport.EventCountdownTicker
import java.io.File
import kotlinx.coroutines.flow.map

/** Teil-Container Sport (aus [AppContainer] herausgelöst): Quellen, Zuordnung zu Sendern, öffentliches Programm. */
class SportModule(private val container: AppContainer) {
    /** Sport-Events: immer online (ESPN; Sportmonks nur mit eigenem, verschlüsselt gespeichertem Schlüssel). */
    val repository: SportRepository by lazy {
        val dao = container.database.sportDao()
        val finder = SportChannelFinder(dao, guideStore, guideEnabled = { container.settingsRepository.current().let { it.onlineExtras && it.sportGuide } })
        val sources = listOf(
            EspnSource(container.httpFetcher),
            SportmonksSource(container.httpFetcher) { container.settingsRepository.current().sportmonksToken },
            BzzoiroSource(container.httpFetcher) { container.settingsRepository.current().bzzoiroToken },
            GuideSeries(finder::guide),
        )
        SportRepository(
            dao,
            container.settingsRepository,
            sources,
            ChannelEventMatcher(dao, finder),
            container.appScope,
            hasPlaylists = { container.database.playlistDao().all().isNotEmpty() },
            // Nach jeder Aktualisierung – und nach jedem Neuaufbau von „Alle Zugänge“, der erst danach fertig wird
            // (Protokoll 04.11.: „162 Events, 0 mit Sender“ direkt nach dem Aufbau).
            contentUpdated = kotlinx.coroutines.flow.combine(
                container.syncManager.state.map { it.finishedAtMs },
                container.combined.builder.stats.map { it?.atMs ?: 0L },
            ) { synced, combined -> synced + combined },
            kickoff = KickoffCheck(dao, container.epgRepository::liveShortEpg, refreshGroups = eventGroupRefresh::refresh),
        )
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
}
