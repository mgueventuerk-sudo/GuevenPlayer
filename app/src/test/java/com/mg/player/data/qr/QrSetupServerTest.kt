package com.mg.player.data.qr

import android.util.Base64
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.mg.player.core.security.ChaCha20Poly1305
import com.mg.player.core.security.ShareKey
import com.mg.player.domain.repository.PlaylistInput
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** Einrichtung per Handy: Zugangsdaten nur verschlüsselt und unverändert – Klartext aus dem WLAN wird abgelehnt. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class QrSetupServerTest {

    private val keyBytes = ByteArray(32) { (it * 11 + 5).toByte() }
    private val qr = QrSetupServer(ShareKey(keyBytes))
    private val client = OkHttpClient.Builder().proxy(java.net.Proxy.NO_PROXY).build()

    @After
    fun tearDown() = qr.stop()

    private fun post(url: String, body: String, type: String) =
        client.newCall(Request.Builder().url(url).post(body.toRequestBody(type.toMediaType())).build()).execute().use { it.code }

    @Test
    fun plainFormIsRejectedSealedFormIsAccepted() = runBlocking {
        val url = qr.start()
        assumeTrue(url != null)
        val base = url!!.substringBefore("/?t=")
        val token = url.substringAfter("?t=").substringBefore('#')
        assertEquals(ShareKey(keyBytes).fragment, url.substringAfter("#k="))
        val form = "t=$token&type=m3u&name=Meins&m3u=http%3A%2F%2Fsrv%2Fa.m3u"

        val received = async(start = CoroutineStart.UNDISPATCHED) { withTimeout(5_000) { qr.submissions.first() } }
        val plainCode = post("$base/submit?t=$token", form, "application/x-www-form-urlencoded")
        assertEquals("Klartext (früheres Formular, Angreifer mit mitgelesenem Token)", 400, plainCode)

        val nonce = ByteArray(12) { it.toByte() }
        val sealed = JSONObject()
            .put("n", Base64.encodeToString(nonce, Base64.NO_WRAP))
            .put("c", Base64.encodeToString(ChaCha20Poly1305.seal(keyBytes, nonce, form.toByteArray()), Base64.NO_WRAP))
            .toString()
        assertEquals(200, post("$base/submit?t=$token", sealed, "application/json"))
        val input: PlaylistInput = received.await()
        assertEquals("http://srv/a.m3u", input.m3uUrl)
    }
}
