package com.mg.player.core.di

import android.content.Context
import com.mg.player.data.combined.ChannelCatalog
import com.mg.player.data.combined.ChannelCatalogUpdate
import com.mg.player.data.combined.Combined
import com.mg.player.data.combined.CombinedBuilder
import com.mg.player.data.combined.CombinedSettings
import kotlinx.coroutines.flow.first
import java.lang.ref.SoftReference

/**
 * Teil-Container „Alle Zugänge“: Einstellungen und Aufbau der Kombination. Neu aufgebaut wird nach jeder
 * Aktualisierung (Inhalte oder EPG), jedem gelöschten Zugang, jeder Änderung an ausgeblendeten/gesperrten Gruppen
 * eines echten Zugangs und jeder Einstellung hier – immer im Hintergrund und gebündelt.
 */
class CombinedModule(private val container: AppContainer) {

    val settings: CombinedSettings by lazy {
        CombinedSettings(container.appContext.getSharedPreferences("combined", Context.MODE_PRIVATE))
    }

    val builder: CombinedBuilder by lazy {
        CombinedBuilder(
            container.database,
            settings,
            container.appScope,
            container.channelGroupIndex,
            container.searchIndexer,
            syncBusy = { container.syncManager.state.value.running },
            catalog = ::catalog,
            beforeRebuild = { if (onlineAllowed) refreshCatalog(force = false) },
        )
    }

    /** Unit-Tests laufen ohne Internet (Gradle setzt die Eigenschaft); in der App immer erlaubt. */
    private val onlineAllowed = System.getProperty("player.offlineTests") != "true"

    /** Abgleich des Senderkatalogs mit dem Internet (iptv-org), höchstens wöchentlich bzw. auf Wunsch. */
    val catalogUpdate: ChannelCatalogUpdate by lazy {
        ChannelCatalogUpdate(container.appContext.filesDir, container.httpFetcher, container.appContext.getSharedPreferences("combined", Context.MODE_PRIVATE))
    }

    /** Katalog abgleichen; bei neuem Stand wird er beim nächsten Aufbau frisch gelesen. Fehler bleiben folgenlos. */
    suspend fun refreshCatalog(force: Boolean): ChannelCatalogUpdate.Outcome {
        val outcome = catalogUpdate.refresh(force)
        if (outcome is ChannelCatalogUpdate.Outcome.Updated) catalogRef = null
        return outcome
    }

    /**
     * Senderkatalog (~7 MB im Speicher): zwischen zwei Aufbauten wiederverwendet, darf aber bei Speichermangel weichen
     * (schwache Sticks) – dann wird er beim nächsten Aufbau neu gelesen.
     */
    @Volatile private var catalogRef: SoftReference<ChannelCatalog>? = null

    internal fun catalog(): ChannelCatalog =
        catalogRef?.get() ?: ChannelCatalog.load(container.appContext).also { catalogRef = SoftReference(it) }

    /**
     * Nach einem Update mit neuen Zuordnungsregeln: alle Senderlisten neu zusammenfassen und „Alle Zugänge“ neu
     * aufbauen – nicht erst bei der nächsten Aktualisierung.
     */
    suspend fun upgradeIfRulesChanged(playlistIds: List<Long>) {
        if (settings.builtWithMatcher == CombinedBuilder.MATCHER_VERSION) return
        playlistIds.forEach { container.channelGroupIndex.rebuild(it, unifyFirst = true) }
        if (playlistIds.size >= 2) builder.ensureLater() else settings.builtWithMatcher = CombinedBuilder.MATCHER_VERSION
    }

    /** Zugänge, deren EPG frisch gehalten wird: bei „Alle Zugänge“ alle echten, sonst der aktive. */
    suspend fun epgTargets(activeId: Long): List<Long> =
        if (Combined.isCombined(activeId)) container.playlistRepository.observePlaylists().first().map { it.id } else listOf(activeId)

    /** Nach einer Änderung an einem echten Zugang (nicht an der Kombination selbst). */
    fun onSourceChanged(playlistId: Long, reason: String) {
        if (!Combined.isCombined(playlistId)) builder.rebuildLater(reason)
    }
}
