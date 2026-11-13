package com.mg.player.ui.common

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import kotlinx.coroutines.delay

/**
 * Fokus-Speicher eines Screens: merkt sich das zuletzt fokussierte Element über eine feste ID und holt den Fokus
 * beim Zurückkehren (Zurück aus Player, Panel, Unterseite) genau dorthin. Gibt es das Element nicht mehr, greift
 * der übergebene Ersatz. Überlebt Navigation und Prozess-Neustart (rememberSaveable im Back-Stack-Eintrag).
 */
@Stable
class FocusMemory(initial: String?) {
    var key by mutableStateOf(initial)
        private set
    private val requesters = HashMap<String, FocusRequester>()

    fun requester(id: String): FocusRequester = requesters.getOrPut(id) { FocusRequester() }

    fun onFocused(id: String) {
        key = id
    }

    /** Fokus auf das gemerkte Element, sonst auf [fallback]. @return true, wenn eines von beiden den Fokus hat. */
    fun restore(fallback: FocusRequester): Boolean {
        val remembered = key?.let(requesters::get)
        return (remembered != null && remembered.tryRequestFocus()) || fallback.tryRequestFocus()
    }

    /**
     * Wie [restore], aber mit Wiederholung: Nach dem Zurückkehren auf einen Screen ist das Ziel oft erst nach ein,
     * zwei Bildern wieder fokussierbar. Bricht ab, sobald der Fokus sitzt.
     */
    suspend fun restoreWhenReady(fallback: FocusRequester, attempts: Int = 20, pauseMs: Long = 50): Boolean {
        repeat(attempts) {
            if (restore(fallback)) return true
            delay(pauseMs)
        }
        return false
    }

    companion object {
        val Saver: Saver<FocusMemory, String> = Saver(save = { it.key.orEmpty() }, restore = { FocusMemory(it.ifEmpty { null }) })
    }
}

@Composable
fun rememberFocusMemory(): FocusMemory = rememberSaveable(saver = FocusMemory.Saver) { FocusMemory(null) }

/** Meldet Fokus an [memory] und macht das Element für [FocusMemory.restore] erreichbar. */
fun Modifier.remembered(memory: FocusMemory, id: String): Modifier =
    focusRequester(memory.requester(id)).onFocusChanged { if (it.isFocused) memory.onFocused(id) }
