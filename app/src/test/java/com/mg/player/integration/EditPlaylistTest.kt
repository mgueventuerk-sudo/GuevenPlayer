package com.mg.player.integration

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.mg.player.core.PlayerApp
import com.mg.player.core.security.SecretCipher
import com.mg.player.data.remote.AccountVerifier
import com.mg.player.domain.model.ContentType
import com.mg.player.domain.model.PlaylistType
import com.mg.player.domain.repository.PlaylistEdit
import com.mg.player.domain.repository.PlaylistInput
import com.mg.player.ui.setup.EditForm
import com.mg.player.ui.setup.EditPlaylistViewModel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** Plan 3.27: Zugang bearbeiten – ohne Datenverlust, Geheimnisse nur verschlüsselt, Prüfung vor dem Speichern. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class EditPlaylistTest {

    private val server = MockWebServer()
    private val container get() = ApplicationProvider.getApplicationContext<PlayerApp>().container

    @After
    fun tearDown() = server.shutdown()

    @Test
    fun editingKeepsIdFavoritesAndEncryptsSecrets() = runBlocking {
        server.enqueue(MockResponse().setBody(TestPlaylists.M3U))
        server.start()
        val repo = container.playlistRepository
        val url = server.url("/list.m3u?username=alt&password=geheim1").toString()
        val id = repo.addPlaylist(PlaylistInput("Alt", PlaylistType.M3U, m3uUrl = url, allowInsecureHttp = true))
        repo.refreshContent(id) {}
        val channel = container.searchRepository.search(id, "zdf").first { it.type == ContentType.LIVE }
        container.playbackRepository.toggleFavorite(ContentType.LIVE, channel.id)

        // Nur Name und Intervall: keine neuen Zugangsdaten → kein Neuladen nötig.
        val nameOnly = repo.updatePlaylist(id, PlaylistEdit("Neu", epgUrl = "", allowInsecureHttp = true, autoRefreshDays = 2))
        assertFalse(nameOnly.credentialsChanged)
        assertEquals("Neu", repo.getPlaylist(id)!!.name)
        assertEquals(2, repo.getPlaylist(id)!!.autoRefreshDays)
        assertEquals(url, repo.getPlaylist(id)!!.m3uUrl) // leer = unverändert

        val newUrl = server.url("/list.m3u?username=neu&password=geheim2").toString()
        val changed = repo.updatePlaylist(id, PlaylistEdit("Neu", m3uUrl = newUrl, allowInsecureHttp = true, autoRefreshDays = 2))
        assertTrue(changed.credentialsChanged)
        val raw = container.database.playlistDao().get(id)!!
        assertFalse("geheim2" in raw.m3uUrl) // nur verschlüsselt gespeichert
        assertEquals(newUrl, SecretCipher.decrypt(raw.m3uUrl))
        assertTrue(container.playbackRepository.isFavorite(ContentType.LIVE, channel.id).first())
        assertEquals(id, raw.id)
    }

    @Test
    fun xtreamPasswordEmptyMeansUnchanged() = runBlocking {
        val repo = container.playlistRepository
        val id = repo.addPlaylist(PlaylistInput("X", PlaylistType.XTREAM, "https://anbieter.example", "user", "pw1"))
        val r1 = repo.updatePlaylist(id, PlaylistEdit("X", "https://anbieter.example", "user", password = ""))
        assertFalse(r1.credentialsChanged)
        assertEquals("pw1", repo.getPlaylist(id)!!.password)
        val r2 = repo.updatePlaylist(id, PlaylistEdit("X", "https://anbieter.example", "user", password = "pw2"))
        assertTrue(r2.credentialsChanged)
        assertNotEquals("pw2", container.database.playlistDao().get(id)!!.password)
        assertEquals("pw2", repo.getPlaylist(id)!!.password)
    }

    @Test
    fun verifierAcceptsGoodAndRejectsBadAccounts() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"user_info":{"auth":1}}"""))
        server.enqueue(MockResponse().setBody("""{"user_info":{"auth":0}}"""))
        server.enqueue(MockResponse().setBody("#EXTM3U\n#EXTINF:-1,A\nhttp://x/a.ts"))
        server.enqueue(MockResponse().setBody("<html>Fehlerseite</html>"))
        server.start()
        val v = container.accountVerifier
        val base = server.url("/").toString().trimEnd('/')
        assertNull(v.verifyXtream(base, "u", "p", true))
        assertEquals(AccountVerifier.REJECTED, v.verifyXtream(base, "u", "falsch", true))
        assertNull(v.verifyM3u("$base/ok.m3u", true))
        assertEquals(AccountVerifier.NOT_M3U, v.verifyM3u("$base/kaputt.m3u", true))
        // Nicht erreichbar: verständliche Meldung statt Absturz.
        assertTrue(v.verifyM3u("http://127.0.0.1:9/x.m3u", true)!!.isNotBlank())
    }

    @Test
    fun formValidationAndMasking() {
        assertEquals(null, EditPlaylistViewModel.validate(PlaylistType.XTREAM, EditForm(server = "https://a.tv", username = "u")))
        assertTrue(EditPlaylistViewModel.validate(PlaylistType.XTREAM, EditForm(server = "", username = "u")) != null)
        assertTrue(EditPlaylistViewModel.validate(PlaylistType.M3U, EditForm(m3uUrl = "http://a.tv/x.m3u")) != null)
        assertTrue(EditPlaylistViewModel.validate(PlaylistType.M3U, EditForm(autoRefreshDays = "400")) != null)
        val masked = EditPlaylistViewModel.maskSecrets("https://user:pw@anbieter.tv/geheim/pfad/get.php?username=max&password=1234&type=m3u")
        assertEquals("https://anbieter.tv/…?username=•••&password=•••&type=•••", masked)
        assertFalse("1234" in masked || "max" in masked || "pw" in masked)
    }
}
