package com.mg.player.data.backup

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.mg.player.core.PlayerApp
import com.mg.player.core.di.AppContainer
import com.mg.player.core.log.AppLog
import com.mg.player.domain.model.AppSettings
import com.mg.player.domain.repository.SettingsRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.security.SecureRandom
import java.util.concurrent.TimeUnit
import kotlin.random.Random
import kotlin.random.asKotlinRandom

/**
 * Zufälliges Kennwort für die automatische Sicherung - niemand gibt es ein, also lang statt merkbar.
 *
 * Aus [SecureRandom], nicht aus `kotlin.random.Random`: Dessen Standardquelle ist kein kryptografischer Zufall – wer den
 * Startwert kennt oder eingrenzt, kann das Kennwort nachrechnen, und die Sicherung enthält Xtream-Passwörter und den
 * privaten WireGuard-Schlüssel. [random] bleibt austauschbar für deterministische Tests.
 */
object AutoBackupPassphrase {
    private const val LENGTH = 32
    private const val CHARS = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789"

    fun generate(random: Random = SecureRandom().asKotlinRandom()): String = (1..LENGTH).map { CHARS[random.nextInt(CHARS.length)] }.joinToString("")
}

/**
 * Wiederkehrende Sicherung in einen selbst gewählten Ordner (Storage Access Framework), ohne dass man die App
 * dafür öffnet. Dieselbe verschlüsselte Sicherung wie "Sicherung erstellen" - das Kennwort wird beim Einrichten
 * einmal zufällig erzeugt (AutoBackupPassphrase) und nur verschlüsselt auf dem Gerät gespeichert, nie im Backup
 * selbst (sonst könnte eine Sicherungsdatei ihr eigenes Kennwort enthalten - sinnlos und ein Risiko).
 *
 * Das Schreiben über die SAF-Baum-URI braucht einen echten DocumentsProvider (ACTION_OPEN_DOCUMENT_TREE) und lässt
 * sich in Robolectric nicht nachstellen - ungeprüft in diesem Rahmen, siehe AutoBackupTest für den Teil, der es ist
 * (ob überhaupt geschrieben werden soll).
 */
object AutoBackup {
    const val FILE_NAME = "guevenplayer-sicherung.json"

    /** Ordner gewählt und ein gültiges Kennwort gespeichert? Reine Entscheidung, ohne jeden Dateizugriff. */
    fun isConfigured(settings: AppSettings): Boolean =
        !settings.autoBackupFolderUri.isNullOrBlank() && settings.autoBackupPassphrase.length >= BackupCrypto.MIN_PASSPHRASE

    /**
     * Manche Geräte (z. B. FireOS-Sticks statt echtem Android) liefern keine App aus, die
     * ACTION_OPEN_DOCUMENT_TREE zeigen kann - dort stürzte die App vorher beim Versuch ab.
     */
    fun folderPickerAvailable(context: Context): Boolean =
        Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).resolveActivity(context.packageManager) != null

    /**
     * [folderUri] = null schaltet aus. Beim allerersten Einrichten wird einmalig ein Kennwort erzeugt;
     * ein späterer Ordnerwechsel behält das vorhandene.
     */
    suspend fun configure(settings: SettingsRepository, folderUri: String?) {
        settings.update { s ->
            s.copy(
                autoBackupFolderUri = folderUri,
                autoBackupPassphrase = if (folderUri == null) {
                    s.autoBackupPassphrase
                } else {
                    s.autoBackupPassphrase.takeIf { it.length >= BackupCrypto.MIN_PASSPHRASE } ?: AutoBackupPassphrase.generate()
                },
            )
        }
    }

    /** true = Sicherung geschrieben, false = nicht eingerichtet. */
    suspend fun run(container: AppContainer, context: Context): Boolean = withContext(Dispatchers.IO) {
        val settings = container.settingsRepository.current()
        if (!isConfigured(settings)) return@withContext false
        val tree = Uri.parse(settings.autoBackupFolderUri)
        val text = container.backupManager.export(settings.autoBackupPassphrase)
        write(context, tree, text)
        AppLog.log("Sicherung", "Automatische Sicherung geschrieben")
        true
    }

    private fun write(context: Context, tree: Uri, text: String) {
        val resolver = context.contentResolver
        val treeDocId = DocumentsContract.getTreeDocumentId(tree)
        val target = findExisting(context, tree, treeDocId)
            ?: DocumentsContract.createDocument(resolver, DocumentsContract.buildDocumentUriUsingTree(tree, treeDocId), MIME_TYPE, FILE_NAME)
            ?: error("Ordner „${tree}“ nicht beschreibbar")
        resolver.openOutputStream(target, "wt")?.use { it.write(text.toByteArray(Charsets.UTF_8)) } ?: error("Sicherungsdatei nicht beschreibbar")
    }

    private fun findExisting(context: Context, tree: Uri, treeDocId: String): Uri? {
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(tree, treeDocId)
        val columns = arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME)
        context.contentResolver.query(childrenUri, columns, null, null, null)?.use { cursor ->
            val idIndex = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
            val nameIndex = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
            while (cursor.moveToNext()) {
                if (cursor.getString(nameIndex) == FILE_NAME) return DocumentsContract.buildDocumentUriUsingTree(tree, cursor.getString(idIndex))
            }
        }
        return null
    }

    private const val MIME_TYPE = "application/octet-stream"
}

class AutoBackupWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val container = (applicationContext as? PlayerApp)?.container ?: return Result.success()
        return try {
            AutoBackup.run(container, applicationContext)
            Result.success()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AppLog.error("Sicherung", "Automatische Sicherung fehlgeschlagen (Versuch ${runAttemptCount + 1})", e)
            if (runAttemptCount < MAX_RETRIES) Result.retry() else Result.success()
        }
    }

    companion object {
        private const val NAME = "auto-backup"
        private const val MAX_RETRIES = 2
        private const val INTERVAL_HOURS = 24L
        private const val FLEX_HOURS = 6L

        /** Einmal am Tag; ist kein Ordner eingerichtet, prüft AutoBackup.run() das selbst und tut nichts. */
        fun schedule(context: Context) {
            val constraints = Constraints.Builder().setRequiresStorageNotLow(true).build()
            val request = PeriodicWorkRequestBuilder<AutoBackupWorker>(INTERVAL_HOURS, TimeUnit.HOURS, FLEX_HOURS, TimeUnit.HOURS)
                .setConstraints(constraints)
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(NAME, ExistingPeriodicWorkPolicy.KEEP, request)
        }

        /** Ordner abgewählt: den eingeplanten Lauf auch wirklich stoppen, nicht nur die Einstellung löschen. */
        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(NAME)
        }
    }
}
