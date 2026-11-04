package com.mg.player.data.share

import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.util.concurrent.atomic.AtomicInteger

/** Teilen per Handy: Eine Sicherung kommt nur an, wenn am Fernseher „Wiederherstellen“ offen ist. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class DeviceShareServerTest {

    private val client = OkHttpClient.Builder().proxy(java.net.Proxy.NO_PROXY).build()
    private val received = AtomicInteger()
    private val backup = """{"format":"mg-player-backup-1","playlists":[]}"""

    private fun postRestore(server: DeviceShareServer): Int {
        val base = server.start()
        assumeTrue(base != null)
        val request = Request.Builder().url(server.urlFor(base!!, "/restore")).post(backup.toRequestBody("application/json".toMediaType())).build()
        return client.newCall(request).execute().use { it.code }
    }

    /** So baut ShareViewModel den Server für „Protokoll teilen“: wer den Token im WLAN mitliest, darf nichts einspielen. */
    @Test
    fun restoreIsClosedOutsideImportMode() = runBlocking {
        val server = DeviceShareServer(exportBackup = { null }, readLog = { "" })
        try {
            assertEquals(404, postRestore(server))
        } finally {
            server.stop()
        }
    }

    @Test
    fun restoreWorksInImportMode() = runBlocking {
        val server = DeviceShareServer(
            exportBackup = { null },
            receiveBackup = {
                received.incrementAndGet()
                DeviceShareServer.Reply(true, "ok", "ok")
            },
            readLog = { "" },
        )
        try {
            assertEquals(200, postRestore(server))
            assertEquals(1, received.get())
        } finally {
            server.stop()
        }
    }

    /** Protokoll nur in „Protokoll teilen“ – beim Sichern/Wiederherstellen ist /log zu (Grundsatz: so wenig wie nötig). */
    @Test
    fun logOnlyInLogMode() = runBlocking {
        fun getLog(server: DeviceShareServer): Int {
            val base = server.start()
            assumeTrue(base != null)
            return client.newCall(Request.Builder().url(server.urlFor(base!!, "/log")).build()).execute().use { it.code }
        }
        val closed = DeviceShareServer(exportBackup = { null }, readLog = { "geheim" }, allowLog = false)
        val open = DeviceShareServer(exportBackup = { null }, readLog = { "Protokoll" }, allowLog = true)
        try {
            assertEquals(404, getLog(closed))
            assertEquals(200, getLog(open))
        } finally {
            closed.stop()
            open.stop()
        }
    }
}
