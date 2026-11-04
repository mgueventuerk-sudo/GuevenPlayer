package com.mg.player.data.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.mg.player.core.security.SecretCipher
import java.io.File
import kotlinx.coroutines.job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.rules.TemporaryFolder
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class SettingsSecretStorageTest {

    @get:Rule
    val folder = TemporaryFolder()

    @Test
    fun tmdbKeyIsEncryptedAtRestAndReadableByRepository() = runBlocking {
        SecretCipher.init(ApplicationProvider.getApplicationContext<Context>())
        val file = folder.newFile("settings.preferences_pb")
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        try {
            val store: DataStore<Preferences> = PreferenceDataStoreFactory.create(scope = scope) { file }
            val repository = SettingsRepositoryImpl(store)
            val token = "tmdb-secret-for-storage-test"
            repository.update { it.copy(tmdbApiKey = token) }

            assertFalse(String(file.readBytes(), Charsets.ISO_8859_1).contains(token))
            assertEquals(token, repository.current().tmdbApiKey)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun existingPlaintextTmdbKeyIsMigratedBeforeSettingsAreEmitted() = runBlocking {
        SecretCipher.init(ApplicationProvider.getApplicationContext<Context>())
        val file = folder.newFile("legacy-settings.preferences_pb")
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        try {
            val store: DataStore<Preferences> = PreferenceDataStoreFactory.create(scope = scope) { file }
            val tokenKey = stringPreferencesKey("tmdb_api_key")
            val token = "legacy-tmdb-secret"
            store.edit { it[tokenKey] = token }

            val repository = SettingsRepositoryImpl(store)
            assertEquals(token, repository.current().tmdbApiKey)
            assertFalse(String(file.readBytes(), Charsets.ISO_8859_1).contains(token))
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun automaticOptimizationPreferenceSurvivesRepositoryReload() = runBlocking {
        SecretCipher.init(ApplicationProvider.getApplicationContext<Context>())
        val file = folder.newFile("automatic-settings.preferences_pb")
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        try {
            val repository = SettingsRepositoryImpl(PreferenceDataStoreFactory.create(scope = scope) { file })
            repository.update { it.copy(automaticOptimization = false) }
            assertEquals(false, repository.current().automaticOptimization)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun guideIsVisibleByDefaultAndHidePreferencePersists() = runBlocking {
        val file = folder.newFile("guide-settings.preferences_pb")
        var scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        try {
            val repository = SettingsRepositoryImpl(PreferenceDataStoreFactory.create(scope = scope) { file })
            assertEquals(false, repository.current().hideGuide)
            repository.update { it.copy(hideGuide = true) }
            assertEquals(true, repository.current().hideGuide)
            // Erst abwarten, bis der alte DataStore die Datei freigegeben hat: cancel() allein wirkt nebenläufig,
            // der zweite Store auf derselben Datei scheiterte dadurch sporadisch („multiple DataStores active“).
            scope.coroutineContext.job.cancelAndJoin()

            scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
            val reloaded = SettingsRepositoryImpl(PreferenceDataStoreFactory.create(scope = scope) { file })
            assertEquals(true, reloaded.current().hideGuide)
            reloaded.update { it.copy(hideGuide = false) }
            assertEquals(false, reloaded.current().hideGuide)
        } finally {
            scope.cancel()
        }
    }
}
