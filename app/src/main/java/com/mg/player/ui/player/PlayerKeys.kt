package com.mg.player.ui.player

import android.view.KeyEvent as AndroidKeyEvent
import android.view.ViewConfiguration
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.nativeKeyCode
import androidx.compose.ui.input.key.type

/** Rückmeldungen der Fernbedienung an den Player-Screen. */
internal class PlayerKeyActions(
    val showOverlay: () -> Unit,
    val onDigit: (Int) -> Unit,
    val showChannelList: () -> Unit,
    /** Verlauf: zuletzt gesehene Sender (Rechts gedrückt halten). */
    val showHistory: () -> Unit = {},
    /** Filme/Serien: Zeitleiste öffnen und die Markierung verschieben (−1/+1, Taste gehalten). */
    val scrub: (direction: Int, repeat: Boolean) -> Unit = { _, _ -> },
)

/**
 * Fernbedienungs-Belegung im Vollbild (Overlay ausgeblendet):
 * Live: Hoch/Runter = Sender wechseln, Links = Senderliste mit EPG-Vorschau, Rechts kurz = Ping-Pong zum
 * zuletzt geschauten Sender, Rechts gedrückt halten = Verlauf, Ziffern = Sendernummer, "Letzter Sender" = vorheriger.
 * Filme, Serien, Catch-up: Links/Rechts und Spultasten = Zeitleiste mit Markierung (OK springt), Play/Pause-Taste.
 * OK öffnet überall das Overlay.
 */
internal fun handlePlayerKey(event: KeyEvent, isLive: Boolean, vm: PlayerViewModel, actions: PlayerKeyActions): Boolean {
    if (isLive && event.key == Key.DirectionRight) return handleRightKey(event, vm, actions)
    if (event.type != KeyEventType.KeyDown) return false
    val scrub = actions.scrub
    digitOf(event.key.nativeKeyCode)?.let { digit ->
        if (!isLive) return false
        actions.onDigit(digit)
        return true
    }
    return when (event.key) {
        Key.DirectionUp -> {
            if (isLive) vm.zap(-1) else actions.showOverlay()
            true
        }

        Key.DirectionDown -> {
            if (isLive) vm.zap(1) else actions.showOverlay()
            true
        }

        Key.ChannelUp -> {
            if (isLive) vm.zap(1)
            true
        }

        Key.ChannelDown -> {
            if (isLive) vm.zap(-1)
            true
        }

        Key.DirectionLeft -> {
            if (isLive) {
                actions.showChannelList()
            } else {
                scrub(-1, event.nativeKeyEvent.repeatCount > 0)
            }
            true
        }

        Key.DirectionRight -> {
            scrub(1, event.nativeKeyEvent.repeatCount > 0)
            true
        }

        Key.DirectionCenter, Key.Enter, Key.NumPadEnter, Key.Menu -> {
            actions.showOverlay()
            true
        }

        Key.MediaPlayPause, Key.MediaPlay, Key.MediaPause, Key.Spacebar -> {
            vm.togglePlayPause()
            true
        }

        Key.MediaFastForward -> {
            if (!isLive) scrub(1, event.nativeKeyEvent.repeatCount > 0)
            true
        }

        Key.MediaRewind -> {
            if (!isLive) scrub(-1, event.nativeKeyEvent.repeatCount > 0)
            true
        }

        Key.MediaNext -> {
            if (isLive) vm.zap(1) else vm.playNextNow()
            true
        }

        Key.MediaPrevious -> {
            if (isLive) vm.zap(-1)
            true
        }

        else -> if (event.key.nativeKeyCode == AndroidKeyEvent.KEYCODE_LAST_CHANNEL) {
            if (isLive) vm.previousChannel()
            true
        } else {
            false
        }
    }
}

/**
 * Solange ein Button im Overlay, im Seitenpanel oder bei "Intro überspringen" den Fokus hat,
 * gehören Pfeiltasten und OK der Fokus-Navigation. Hier werden dann nur Medientasten ausgewertet.
 */
internal fun handleMediaKey(event: KeyEvent, isLive: Boolean, vm: PlayerViewModel, scrub: (Int, Boolean) -> Unit = { _, _ -> }): Boolean {
    if (event.type != KeyEventType.KeyDown) return false
    return when (event.key) {
        Key.MediaPlayPause, Key.MediaPlay, Key.MediaPause -> {
            vm.togglePlayPause()
            true
        }

        Key.MediaFastForward -> {
            if (!isLive) scrub(1, event.nativeKeyEvent.repeatCount > 0)
            true
        }

        Key.MediaRewind -> {
            if (!isLive) scrub(-1, event.nativeKeyEvent.repeatCount > 0)
            true
        }

        Key.ChannelUp -> {
            if (isLive) vm.zap(1)
            true
        }

        Key.ChannelDown -> {
            if (isLive) vm.zap(-1)
            true
        }

        else -> false
    }
}

private fun digitOf(keyCode: Int): Int? = when (keyCode) {
    in AndroidKeyEvent.KEYCODE_0..AndroidKeyEvent.KEYCODE_9 -> keyCode - AndroidKeyEvent.KEYCODE_0
    in AndroidKeyEvent.KEYCODE_NUMPAD_0..AndroidKeyEvent.KEYCODE_NUMPAD_9 -> keyCode - AndroidKeyEvent.KEYCODE_NUMPAD_0
    else -> null
}

/**
 * Rechts im Live-Bild: kurz = vorheriger Sender (beim Loslassen), gedrückt halten = Verlauf. Erst beim Loslassen
 * entscheiden, sonst würde vor dem Verlauf noch umgeschaltet.
 */
private fun handleRightKey(event: KeyEvent, vm: PlayerViewModel, actions: PlayerKeyActions): Boolean {
    val native = event.nativeKeyEvent
    when (event.type) {
        // Manche Fernbedienungen setzen kein Long-Press-Flag: dann nach einigen Wiederholungen.
        KeyEventType.KeyDown -> if (native.isLongPress || native.repeatCount == HISTORY_REPEATS) actions.showHistory()

        KeyEventType.KeyUp -> if (native.eventTime - native.downTime < ViewConfiguration.getLongPressTimeout()) vm.previousChannel()
    }
    return true
}

private const val HISTORY_REPEATS = 10
