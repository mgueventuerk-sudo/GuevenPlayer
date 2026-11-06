package com.mg.player.ui.player

import com.mg.player.core.log.AppLog
import com.mg.player.domain.model.PlayerEngineType
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

// Lebenszyklus des Players: Home-Taste, Standby, anderes App-Fenster.

fun PlayerViewModel.onLifecyclePause() {
    val engine = engineHolder.value ?: return
    if (uiState.value.engineType == PlayerEngineType.EXTERNAL) return
    pausedByLifecycle = true
    // Pausiert vom System (Dialog, anderes Fenster): ein laufender Start oder Hänger liegt nicht an der Quelle.
    sourceHealth.onInterrupted()
    progress.saveNow(uiState.value.playable, engine)
    engine.pause()
}

/**
 * App nicht mehr sichtbar: Player nach [RELEASE_DELAY_MS] komplett freigeben – Hardware-Decoder, Stream-Verbindung
 * und Puffer. Bis dahin bleibt er pausiert erhalten (Plan A5, 3.24): Wer nur kurz Home drückt, ist ohne Neuaufbau
 * zurück (Labor 3.23: Neuaufbau 7,9 s im Emulator). Gilt auch, wenn der Start noch läuft: Dann wird nicht abgespielt.
 */
fun PlayerViewModel.onLifecycleStop() {
    if (uiState.value.engineType == PlayerEngineType.EXTERNAL || stoppedByLifecycle) return
    stoppedByLifecycle = true
    sourceHealth.onInterrupted()
    standby.release()
    frameRate.cancelPending()
    infoSampler.stop()
    val engine = engineHolder.value ?: return
    progress.saveNow(uiState.value.playable, engine)
    if (uiState.value.playable?.isLive == false) engine.positionMs.takeIf { it > 0 }?.let { lastKnownPosition = it }
    pendingRelease?.cancel()
    pendingRelease = scope.launch {
        delay(RELEASE_DELAY_MS)
        releaseAfterStop()
    }
}

/** Haltezeit abgelaufen (oder Speicher knapp): jetzt wirklich freigeben. */
internal fun PlayerViewModel.releaseAfterStop() {
    pendingRelease = null
    if (!stoppedByLifecycle) return
    guard?.stop()
    engineReleasedByStop = true
    val engine = engineHolder.value ?: return
    engineStateJob?.cancel()
    engine.release()
}

/** Zurück im Vordergrund: innerhalb der Haltezeit weiterspielen, sonst neue Engine (Live von vorn, Filme an der Stelle). */
fun PlayerViewModel.onLifecycleStart() {
    if (!stoppedByLifecycle) return
    stoppedByLifecycle = false
    val held = pendingRelease?.isActive == true && !engineReleasedByStop
    pendingRelease?.cancel()
    pendingRelease = null
    if (held) {
        // Engine lebt noch und ist pausiert: onLifecycleResume setzt fort (auch Live – Taste „Live“ springt nach vorn).
        resumeFromHold = true
        guard?.onUserInterrupt()
        AppLog.log("Player", "Zurück ohne Neuaufbau (Engine gehalten)")
        return
    }
    pausedByLifecycle = false
    val restartEngine = engineReleasedByStop
    if (restartEngine) createEngine(uiState.value.engineType)
    // Noch kein Inhalt aufgelöst: Der laufende Start spielt dann selbst ab.
    val playable = uiState.value.playable ?: return
    if (restartEngine) {
        guard?.onPlaybackSessionRestored(playable)
        AppLog.log("Player", "Wiedergabeüberwachung nach Hintergrundfreigabe neu gestartet")
    } else {
        guard?.onUserInterrupt()
    }
    beginPlayback(playable, if (playable.isLive) 0 else lastKnownPosition)
}

fun PlayerViewModel.onLifecycleResume() {
    if (!pausedByLifecycle) return
    pausedByLifecycle = false
    val fromHold = resumeFromHold
    resumeFromHold = false
    val playable = uiState.value.playable ?: return
    if (playable.isLive && !fromHold) prepare(playable, 0) else engineHolder.value?.play()
}

/** Haltezeit nach ON_STOP, bevor der Player freigegeben wird. */
internal const val RELEASE_DELAY_MS = 30_000L
