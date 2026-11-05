package com.mg.player.core.di

import android.content.Context
import com.mg.player.core.util.PrefsStreamStore
import com.mg.player.core.util.SourceHealth
import com.mg.player.core.util.StreamMemory
import com.mg.player.data.playback.PrefsRecoveryMemory
import com.mg.player.player.TrackMemory
import com.mg.player.player.engine.PlayerEngineFactory
import com.mg.player.player.engine.PreviewHandoff
import com.mg.player.player.supervisor.ProviderBreaker
import com.mg.player.player.supervisor.RecoveryMemory

/** Teil-Container Wiedergabe (aus [AppContainer] herausgelöst). */
class PlaybackModule(private val container: AppContainer) {
    private val appContext get() = container.appContext

    val engineFactory: PlayerEngineFactory by lazy { PlayerEngineFactory(appContext, container.httpFetcher.client) }

    /** Vorschau → Vollbild: laufende Engine übernehmen statt neu zu laden. */
    val previewHandoff: PreviewHandoff by lazy { PreviewHandoff() }

    /** Gemerkte Tonspur je Sender/Serie/Film. */
    val trackMemory: TrackMemory by lazy { TrackMemory.prefs(appContext) }

    /** Sender-Gruppe -> Stream-Variante, die zuletzt funktioniert hat (Auto-Failover), über Neustarts hinweg. */
    val streamMemory: StreamMemory by lazy { StreamMemory(PrefsStreamStore(appContext)) }

    /** Hänger und Fehler je Stream-Quelle (gute Quellen zuerst), über Neustarts hinweg. */
    val sourceHealth: SourceHealth by lazy { SourceHealth(PrefsStreamStore(appContext, "source_health")) }

    /** Wiedergabe-Wächter: was je Sender/Film zuletzt geholfen hat (gilt app-weit). */
    val recoveryMemory: RecoveryMemory by lazy {
        PrefsRecoveryMemory(appContext.getSharedPreferences("playback_supervisor", Context.MODE_PRIVATE))
    }

    /** Schutzschalter je Anbieter für den Wiedergabe-Wächter (gilt app-weit, über alle Player-Bildschirme). */
    val providerBreaker = ProviderBreaker()
}
