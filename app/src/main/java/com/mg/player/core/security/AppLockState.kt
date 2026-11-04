package com.mg.player.core.security

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Gesperrt beim Start und sobald die App in den Hintergrund geht (wirkt nur mit eingeschalteter App-Sperre). */
class AppLockState {
    private val _locked = MutableStateFlow(true)
    val locked: StateFlow<Boolean> = _locked.asStateFlow()

    fun lock() {
        _locked.value = true
    }

    fun unlock() {
        _locked.value = false
    }
}
