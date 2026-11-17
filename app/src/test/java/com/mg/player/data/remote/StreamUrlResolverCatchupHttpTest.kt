package com.mg.player.data.remote

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.mg.player.core.network.HttpFetcher
import com.mg.player.data.db.AppDatabase
import com.mg.player.data.db.entity.ChannelEntity
import com.mg.player.data.db.entity.PlaylistEntity
import com.mg.player.data.importer.XtreamImporter
import com.mg.player.domain.model.PlaylistType
import java.io.IOException
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Catch-up über Xtream trägt Benutzername und Passwort im Pfad. Live, Film und Serie laufen durch die HTTP-Freigabe
 * des Zugangs (requireHttpAllowed) - Catch-up umging sie, und nur Media3Engine sperrt Klartext selbst.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class StreamUrlResolverCatchupHttpTest {

    private val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AppDatabase::class.java)
        .allowMainThreadQueries().build()
    private val resolver = StreamUrlResolver(db, XtreamApi(HttpFetcher(OkHttpClient())))

    @After
    fun tearDown() = db.close()

    private suspend fun setUp(allowHttp: Boolean): String {
        val playlistId = db.playlistDao().insert(
            PlaylistEntity(
                name = "Test", type = PlaylistType.XTREAM.name, serverUrl = "http://anbieter.example:8080", username = "u",
                password = "", m3uUrl = "", customEpgUrl = "", headerEpgUrl = "", lastUpdatedMs = 0, allowInsecureHttp = allowHttp,
            ),
        )
        val id = "$playlistId:L:5"
        db.channelDao().insertAll(
            listOf(
                ChannelEntity(
                    id = id, playlistId = playlistId, categoryId = "c", remoteId = "5", name = "Sender", logoUrl = null,
                    streamUrl = "xt://$playlistId/live/5.ts", epgChannelId = "", normalizedName = "sender", epgKey = "", sortIndex = 0,
                    catchupDays = 3, catchupType = XtreamImporter.CATCHUP_XTREAM,
                ),
            ),
        )
        return id
    }

    @Test
    fun catchupOverPlainHttpNeedsTheAccountsHttpPermission() = runBlocking {
        val id = setUp(allowHttp = false)
        val now = System.currentTimeMillis()
        try {
            resolver.catchupUrl(id, now - 3_600_000, now - 1_800_000)
            fail("Catch-up über HTTP ohne Freigabe muss abgelehnt werden")
        } catch (e: IOException) {
            assertTrue(e.message, e.message.orEmpty().contains("nicht freigegeben"))
        }
    }

    @Test
    fun catchupOverPlainHttpWorksWithThePermission() = runBlocking {
        val id = setUp(allowHttp = true)
        val now = System.currentTimeMillis()
        val url = resolver.catchupUrl(id, now - 3_600_000, now - 1_800_000)
        assertTrue(url, url.orEmpty().startsWith("http://anbieter.example:8080/timeshift/u//30/"))
    }
}
