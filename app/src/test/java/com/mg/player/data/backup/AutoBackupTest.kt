package com.mg.player.data.backup

import com.mg.player.domain.model.AppSettings
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoBackupPassphraseTest {

    @Test
    fun generatedPassphraseHasTheExpectedLengthAndCharset() {
        val passphrase = AutoBackupPassphrase.generate(Random(42))
        assertEquals(32, passphrase.length)
        assertTrue(passphrase.all { it.isLetterOrDigit() })
    }

    /** Das Kennwort wird von Hand abgeschrieben: Nichts, was man mit etwas anderem verwechselt (0/O, 1/l/I). */
    @Test
    fun passphraseHasNoLookAlikeCharacters() {
        val lookAlikes = "0O1lI"
        assertTrue(AutoBackupPassphrase.CHARS.none { it in lookAlikes })
        repeat(200) { seed ->
            val passphrase = AutoBackupPassphrase.generate(Random(seed))
            assertTrue(passphrase, passphrase.none { it in lookAlikes })
        }
    }

    @Test
    fun differentSeedsProduceDifferentPassphrases() {
        assertNotEquals(AutoBackupPassphrase.generate(Random(1)), AutoBackupPassphrase.generate(Random(2)))
    }
}

/** Reine Entscheidung ohne Dateizugriff - der SAF-Schreibvorgang selbst braucht einen echten DocumentsProvider
 *  (ACTION_OPEN_DOCUMENT_TREE) und lässt sich in diesem Rahmen nicht nachstellen; ungeprüft. */
class AutoBackupConfiguredTest {

    @Test
    fun noFolderMeansNotConfigured() {
        assertFalse(AutoBackup.isConfigured(AppSettings(autoBackupFolderUri = null, autoBackupPassphrase = "x".repeat(32))))
        assertFalse(AutoBackup.isConfigured(AppSettings(autoBackupFolderUri = "", autoBackupPassphrase = "x".repeat(32))))
    }

    @Test
    fun tooShortPassphraseMeansNotConfigured() {
        val tooShort = "x".repeat(BackupCrypto.MIN_PASSPHRASE - 1)
        assertFalse(AutoBackup.isConfigured(AppSettings(autoBackupFolderUri = "content://tree/fake", autoBackupPassphrase = tooShort)))
    }

    @Test
    fun folderAndValidPassphraseMeansConfigured() {
        val valid = "x".repeat(BackupCrypto.MIN_PASSPHRASE)
        assertTrue(AutoBackup.isConfigured(AppSettings(autoBackupFolderUri = "content://tree/fake", autoBackupPassphrase = valid)))
    }
}
