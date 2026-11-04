package com.mg.player.ui.common

import androidx.compose.foundation.focusGroup
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import kotlinx.coroutines.delay

/**
 * Fokus setzen, sobald das Element ihn annehmen kann. Ein frisch geöffnetes Dialogfenster braucht dafür
 * einen Moment – ein einzelner Versuch scheitert dort oft still, und der Fokus bliebe im Nichts.
 */
suspend fun FocusRequester.requestFocusWhenReady(attempts: Int = 10, pauseMs: Long = 50) {
    repeat(attempts) {
        if (runCatching { requestFocus() }.isSuccess) return
        delay(pauseMs)
    }
}

/**
 * Hält den Fokus innerhalb dieses Bereichs (Seitenpanels über dem Player). Ohne das sprang der Fokus mit
 * "links" aus dem Ton-Panel auf die Player-Knöpfe dahinter, während das Panel offen blieb.
 */
fun Modifier.trapFocus(): Modifier = focusProperties { onExit = { cancelFocusChange() } }.focusGroup()
