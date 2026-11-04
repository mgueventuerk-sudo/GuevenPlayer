package com.mg.player.data.settings

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Eine beschädigte Einstellungsdatei setzt auf Standardwerte zurück, statt die App bei jedem Start abstürzen zu lassen. */
class SettingsCorruptionTest {

    @get:Rule
    val folder = TemporaryFolder()

    @Test
    fun corruptFileIsReplacedWithDefaults() = runBlocking {
        val file = folder.newFile("settings.preferences_pb").apply { writeBytes(byteArrayOf(0x7f, 0x00, 0x13, 0x37, 0x42)) }
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        try {
            val store = PreferenceDataStoreFactory.create(corruptionHandler = SETTINGS_CORRUPTION_HANDLER, scope = scope) { file }
            val key = stringPreferencesKey("theme_mode")
            assertNull(store.data.first()[key])
            // Danach ist die Datei wieder beschreibbar.
            store.updateData { it.toMutablePreferences().apply { set(key, "LIGHT") } }
            assertEquals("LIGHT", store.data.first()[key])
        } finally {
            scope.cancel()
        }
    }
}
