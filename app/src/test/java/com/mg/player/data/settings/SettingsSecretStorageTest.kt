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

    /**
     * Das Pairing-Geheimnis für Spiel-Finder-Austausch und TV-Handoff (HubAuth) gehört verschlüsselt auf die
     * Platte – wer es im Klartext liest, gibt sich im Heimnetz als Prüfgerät oder Fernseher aus.
     */
    @Test
    fun sportHubSecretsAreEncryptedAtRestAndReadableByRepository() = runBlocking {
        SecretCipher.init(ApplicationProvider.getApplicationContext<Context>())
        val file = folder.newFile("sport-hub-settings.preferences_pb")
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        try {
            val store: DataStore<Preferences> = PreferenceDataStoreFactory.create(scope = scope) { file }
            val repository = SettingsRepositoryImpl(store)
            val code = "sport-hub-pairing-secret"
            val joinCode = "sport-hub-join-secret"
            repository.update { it.copy(sportHubCode = code, sportHubJoinCode = joinCode) }

            val onDisk = String(file.readBytes(), Charsets.ISO_8859_1)
            assertFalse(onDisk.contains(code))
            assertFalse(onDisk.contains(joinCode))
            assertEquals(code, repository.current().sportHubCode)
            assertEquals(joinCode, repository.current().sportHubJoinCode)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun existingPlaintextSportHubSecretsAreMigratedBeforeSettingsAreEmitted() = runBlocking {
        SecretCipher.init(ApplicationProvider.getApplicationContext<Context>())
        val file = folder.newFile("legacy-sport-hub-settings.preferences_pb")
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        try {
            val store: DataStore<Preferences> = PreferenceDataStoreFactory.create(scope = scope) { file }
            val codeKey = stringPreferencesKey("sport_hub_code")
            val joinKey = stringPreferencesKey("sport_hub_join")
            val code = "legacy-sport-hub-secret"
            val joinCode = "legacy-sport-hub-join-secret"
            store.edit {
                it[codeKey] = code
                it[joinKey] = joinCode
            }

            val repository = SettingsRepositoryImpl(store)
            assertEquals(code, repository.current().sportHubCode)
            assertEquals(joinCode, repository.current().sportHubJoinCode)
            val onDisk = String(file.readBytes(), Charsets.ISO_8859_1)
            assertFalse(onDisk.contains(code))
            assertFalse(onDisk.contains(joinCode))
        } finally {
            scope.cancel()
        }
    }

    /**
     * Das Kennwort der automatischen Sicherung ist niemand zum Eingeben gedacht - wie die anderen Schlüssel
     * oben verschlüsselt speichern. Der Ordner selbst ist keine Zugangsdaten, nur eine Baum-URI.
     */
    @Test
    fun autoBackupSettingsAreEncryptedAtRestAndReadableByRepository() = runBlocking {
        SecretCipher.init(ApplicationProvider.getApplicationContext<Context>())
        val file = folder.newFile("auto-backup-settings.preferences_pb")
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        try {
            val store: DataStore<Preferences> = PreferenceDataStoreFactory.create(scope = scope) { file }
            val repository = SettingsRepositoryImpl(store)
            val passphrase = "auto-backup-secret-passphrase"
            val folderUri = "content://com.android.externalstorage.documents/tree/fake"
            repository.update { it.copy(autoBackupFolderUri = folderUri, autoBackupPassphrase = passphrase) }

            assertFalse(String(file.readBytes(), Charsets.ISO_8859_1).contains(passphrase))
            assertEquals(passphrase, repository.current().autoBackupPassphrase)
            assertEquals(folderUri, repository.current().autoBackupFolderUri)
        } finally {
            scope.cancel()
        }
    }
}
