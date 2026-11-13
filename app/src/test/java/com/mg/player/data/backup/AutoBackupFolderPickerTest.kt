package com.mg.player.data.backup

import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.ResolveInfo
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Manche Geräte (z. B. FireOS-Sticks) liefern keine App aus, die ACTION_OPEN_DOCUMENT_TREE zeigen kann -
 * dort stürzte die App beim Versuch, die automatische Sicherung einzurichten, mit einer ungefangenen
 * ActivityNotFoundException ab (Absturzbericht vom Gerät, 13.11., 13:50:45 Uhr).
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class AutoBackupFolderPickerTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun deviceWithoutADocumentsProviderIsReportedAsUnavailable() {
        assertFalse(AutoBackup.folderPickerAvailable(context))
    }

    @Test
    fun deviceWithADocumentsProviderIsReportedAsAvailable() {
        val resolveInfo = ResolveInfo().apply {
            activityInfo = ActivityInfo().apply {
                packageName = "com.android.documentsui"
                name = "com.android.documentsui.picker.PickActivity"
                applicationInfo = context.packageManager.getApplicationInfo(context.packageName, 0)
            }
        }
        shadowOf(context.packageManager).addResolveInfoForIntent(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE), resolveInfo)

        assertTrue(AutoBackup.folderPickerAvailable(context))
    }
}
