package com.mg.player.ui.player

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mg.player.core.log.AppLog
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope

/**
 * viewModelScope mit Auffangnetz: Ein unerwarteter Fehler in einer Nebenaufgabe (EPG laden, Zappen,
 * Quellenwechsel, Favorit) landet im Fehlerprotokoll, statt die ganze App zu beenden.
 */
internal fun ViewModel.guardedScope(tag: String): CoroutineScope = CoroutineScope(
    viewModelScope.coroutineContext + CoroutineExceptionHandler { _, e -> AppLog.error(tag, "Unerwarteter Fehler", e) },
)
