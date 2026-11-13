package com.mg.player.ui.common

import androidx.compose.foundation.focusGroup
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import kotlinx.coroutines.delay

/**
 * Fokus setzen, sobald das Element ihn annehmen kann. Ein frisch geöffnetes Dialogfenster braucht dafür
 * einen Moment – ein einzelner Versuch scheitert dort oft still, und der Fokus bliebe im Nichts.
 *
 * `requestFocus()` meldet dieses stille Scheitern als **false** (nicht als Ausnahme). Bis 3.57 wurde nur auf die
 * Ausnahme geprüft; die Schleife brach damit nach dem ersten Versuch ab – genau in dem Fall, für den sie da ist.
 * Folge am Fernseher: Dialog offen, nichts markiert, der erste Tastendruck „ging ins Leere“.
 *
 * @return true, sobald das Element den Fokus wirklich hat; false nach allen Versuchen.
 */
suspend fun FocusRequester.requestFocusWhenReady(attempts: Int = 20, pauseMs: Long = 50): Boolean {
    repeat(attempts) {
        if (tryRequestFocus()) return true
        delay(pauseMs)
    }
    return false
}

/** Fokus anfordern ohne Ausnahme (nicht angehängt) und mit ehrlichem Ergebnis (noch nicht fokussierbar = false). */
fun FocusRequester.tryRequestFocus(): Boolean = runCatching { requestFocus() }.getOrDefault(false)

/**
 * Hält den Fokus innerhalb dieses Bereichs (Seitenpanels über dem Player). Ohne das sprang der Fokus mit
 * "links" aus dem Ton-Panel auf die Player-Knöpfe dahinter, während das Panel offen blieb.
 */
fun Modifier.trapFocus(): Modifier = focusProperties { onExit = { cancelFocusChange() } }.focusGroup()

/**
 * Schluckt das Loslassen (und die Tasten-Wiederholungen) der OK-Taste, die dieses Element geöffnet hat.
 *
 * Die Flächen aus tv-material lösen `onClick` beim **Loslassen** von OK aus – auch wenn der Druck gar nicht auf ihnen
 * begann. Öffnet ein langer OK-Druck das Kontextmenü (oder ein OK-Druck das Player-Overlay), landet das Loslassen auf
 * dem gerade fokussierten ersten Eintrag und löst ihn sofort aus: Das Menü „schließt sich von selbst“ und startet
 * nebenbei die erste Aktion (Nutzerbericht 13.11.). Ein frischer Druck (KeyDown ohne Wiederholung) geht sofort durch,
 * ebenso alles danach. [key]: ein neuer Wert setzt die Sperre zurück (z. B. ein neues Menü in derselben Komposition).
 */
@Composable
fun Modifier.ignoreOpeningOkRelease(key: Any? = Unit): Modifier {
    var armed by remember(key) { mutableStateOf(false) }
    return onPreviewKeyEvent { event ->
        if (armed) return@onPreviewKeyEvent false
        val isOk = event.key == Key.DirectionCenter || event.key == Key.Enter || event.key == Key.NumPadEnter
        when {
            !isOk -> false

            event.type == KeyEventType.KeyUp -> {
                armed = true
                true
            }

            event.nativeKeyEvent.repeatCount > 0 -> true

            else -> {
                // Frischer Druck (das Loslassen des Öffnens ging verloren): durchlassen.
                armed = true
                false
            }
        }
    }
}
