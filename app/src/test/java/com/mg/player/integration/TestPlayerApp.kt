package com.mg.player.integration

import com.mg.player.core.PlayerApp
import com.mg.player.core.di.AppContainer
import com.mg.player.domain.model.AppSettings
import com.mg.player.domain.repository.SettingsRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

/** Robolectric app with in-memory preferences; focused DataStore tests use the real persistence implementation. */
class TestPlayerApp : PlayerApp() {
    override fun createContainer() = AppContainer(this, TestSettingsRepository())
}

private class TestSettingsRepository : SettingsRepository {
    private val value = MutableStateFlow(AppSettings())
    override val settings: StateFlow<AppSettings> = value

    override suspend fun current(): AppSettings = value.value

    override suspend fun update(transform: (AppSettings) -> AppSettings) {
        value.update(transform)
    }
}
