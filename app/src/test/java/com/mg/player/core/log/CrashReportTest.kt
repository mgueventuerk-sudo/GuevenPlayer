package com.mg.player.core.log

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.mg.player.ui.common.crashReportText
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowActivityManager.ApplicationExitInfoBuilder

/** Absturzbericht: wird beim nächsten Start genau einmal angeboten – auch für "reagiert nicht" und native Abstürze. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class CrashReportTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun lastCrashIsReportedOnce() {
        File(context.filesDir, "logs").mkdirs()
        File(context.filesDir, "logs/last_crash.txt").writeText("10:00 · NullPointerException: weg\n\tat a.b.c")
        assertEquals("10:00 · NullPointerException: weg\n\tat a.b.c", AppLog.takeLastCrash())
        assertNull(AppLog.takeLastCrash())
    }

    @Test
    fun onlyRealProblemsCount() {
        val fg = ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND
        val bg = ActivityManager.RunningAppProcessInfo.IMPORTANCE_CACHED
        assertEquals("App reagierte nicht (ANR)", ExitReasons.describe(ApplicationExitInfo.REASON_ANR, fg))
        assertEquals("Absturz im nativen Code", ExitReasons.describe(ApplicationExitInfo.REASON_CRASH_NATIVE, fg))
        assertNotNull(ExitReasons.describe(ApplicationExitInfo.REASON_CRASH, bg))
        assertNull(ExitReasons.describe(ApplicationExitInfo.REASON_USER_REQUESTED, fg))
        assertNull(ExitReasons.describe(ApplicationExitInfo.REASON_EXIT_SELF, fg))
        assertNull(ExitReasons.describe(ApplicationExitInfo.REASON_SIGNALED, fg))
        // Im Hintergrund wegen Speichermangel beendet ist normal – nur im Vordergrund ein Problem.
        assertNull(ExitReasons.describe(ApplicationExitInfo.REASON_LOW_MEMORY, bg))
        assertNotNull(ExitReasons.describe(ApplicationExitInfo.REASON_LOW_MEMORY, fg))
    }

    @Test
    fun backgroundLowMemoryKillShowsNoDialog() {
        val manager = context.getSystemService(ActivityManager::class.java)
        shadowOf(manager).addApplicationExitInfo(
            ApplicationExitInfoBuilder.newBuilder()
                .setProcessName(context.packageName)
                .setReason(ApplicationExitInfo.REASON_LOW_MEMORY)
                .setImportance(ActivityManager.RunningAppProcessInfo.IMPORTANCE_CACHED)
                .setTimestamp(System.currentTimeMillis())
                .build(),
        )
        assertNull(ExitReasons.takeUnexpectedExit(context))
    }

    @Test
    fun anrFromSystemIsReportedOnce() {
        val manager = context.getSystemService(ActivityManager::class.java)
        shadowOf(manager).addApplicationExitInfo(
            ApplicationExitInfoBuilder.newBuilder()
                .setProcessName(context.packageName)
                .setReason(ApplicationExitInfo.REASON_ANR)
                .setTimestamp(System.currentTimeMillis())
                .setDescription("Input dispatching timed out")
                .build(),
        )
        val first = ExitReasons.takeUnexpectedExit(context)
        assertTrue(first.orEmpty().startsWith("App reagierte nicht (ANR) – Input dispatching timed out"))
        assertNull("nur einmal", ExitReasons.takeUnexpectedExit(context))
    }

    @Test
    fun reportPutsTheCauseFirst() {
        val text = crashReportText("Absturz: X", "Protokoll …")
        assertTrue(text.startsWith("Ursache:\nAbsturz: X"))
        assertTrue(text.endsWith("Protokoll …"))
        assertTrue(AppLog.report().contains("Android"))
    }
}
