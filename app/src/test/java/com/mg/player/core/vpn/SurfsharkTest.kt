package com.mg.player.core.vpn

import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** Surfshark-Anmeldung: Fehlermeldungen, nichts im Klartext, Server per Klick übernehmen (ersetzt den vorigen). */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class SurfsharkTest {

    private val server = MockWebServer()
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val accountPrefs = context.getSharedPreferences("surfshark-test", Context.MODE_PRIVATE)
    private val vpnPrefs = context.getSharedPreferences("surfshark-vpn-test", Context.MODE_PRIVATE)
    private val seal: (String) -> String = { "sealed:" + it.reversed() }
    private val open: (String) -> String = { it.removePrefix("sealed:").reversed() }

    private val serversJson = """
        [{"country":"Germany","countryCode":"DE","location":"Frankfurt am Main","connectionName":"de-fra.prod.surfshark.com",
          "pubKey":"xTIBA5rboUvnH4htodjb6e697QjLERt1NAB4mZqp8Dg=","load":40},
         {"country":"Germany","countryCode":"DE","location":"Berlin","connectionName":"de-ber.prod.surfshark.com",
          "pubKey":"xTIBA5rboUvnH4htodjb6e697QjLERt1NAB4mZqp8Dg=","load":12},
         {"country":"Netherlands","countryCode":"NL","location":"Amsterdam","connectionName":"nl-ams.prod.surfshark.com","pubKey":""}]
    """.trimIndent()

    private val surfsharkConf = """
        [Interface]
        Address = 10.14.0.2/16
        PrivateKey = Z3VldmVucGxheWVyLXN1cmZzaGFyay10ZXN0LWtleSE=
        DNS = 162.252.172.57, 149.154.159.92

        [Peer]
        PublicKey = xTIBA5rboUvnH4htodjb6e697QjLERt1NAB4mZqp8Dg=
        AllowedIPs = 0.0.0.0/0
        Endpoint = de-ber.prod.surfshark.com:51820
    """.trimIndent()

    @Before
    fun setUp() {
        server.start()
        accountPrefs.edit().clear().commit()
        vpnPrefs.edit().clear().commit()
    }

    @After
    fun tearDown() = server.shutdown()

    private fun api() = SurfsharkApi(OkHttpClient(), server.url("/").toString().trimEnd('/'))

    private fun TestScope.account(): Pair<SurfsharkAccount, VpnStore> {
        val store = VpnStore(vpnPrefs, seal, open)
        val backend = object : VpnBackend {
            override fun permissionIntent(): Intent? = null
            override suspend fun up(configText: String) = Unit
            override suspend fun down() = Unit
            override fun isUp() = true
            override fun latestHandshakeMs() = 1L
            override fun traffic(): VpnTraffic? = null
        }
        val controller = VpnController(backend, store, backgroundScope, MutableStateFlow(true), io = StandardTestDispatcher(testScheduler))
        val account = SurfsharkAccount(accountPrefs, api(), VpnImporter(store, controller), store, seal, open) {
            "yAnz5TF+lXXJte14tji3zlMNq+hd2rYUIgJBgB3fBmk=" to "HIgo9xNzJMWLKASShiTqIybxZ0U3wGLiUeJ1PKf8ykw="
        }
        return account to store
    }

    @Test
    fun loginRegistersKeyAndStoresNothingInPlainText() = runTest {
        server.enqueue(MockResponse().setBody("""{"token":"T-1","renewToken":"R-1"}"""))
        server.enqueue(MockResponse().setResponseCode(201).setBody("""{"expiresAt":"2036-01-01"}"""))
        val (account, _) = account()
        assertTrue(account.login("max@example.com", "geheim123").isSuccess)
        val login = JSONObject(server.takeRequest().body.readUtf8())
        assertEquals("max@example.com", login.getString("username"))
        val register = server.takeRequest()
        assertEquals("/v1/account/users/public-keys", register.path)
        assertEquals("Bearer T-1", register.getHeader("Authorization"))
        val body = JSONObject(register.body.readUtf8())
        assertEquals("HIgo9xNzJMWLKASShiTqIybxZ0U3wGLiUeJ1PKf8ykw=", body.getString("pubKey"))
        assertTrue(body.getBoolean("manual"))
        val stored = accountPrefs.all.values.joinToString()
        listOf("geheim123", "T-1", "R-1", "yAnz5TF", "max@example.com").forEach { assertFalse(it, stored.contains(it)) }
        assertEquals("m…@example.com", account.account.value)
    }

    @Test
    fun clearErrorMessages() = runTest {
        server.enqueue(MockResponse().setResponseCode(401))
        server.enqueue(MockResponse().setResponseCode(403).setBody("<!DOCTYPE html><title>Attention Required! | Cloudflare</title>"))
        val (account, _) = account()
        assertEquals("E-Mail oder Passwort falsch.", account.login("a@b.de", "x").exceptionOrNull()?.message)
        assertTrue(account.login("a@b.de", "x").exceptionOrNull()!!.message!!.contains("Cloudflare"))
        assertTrue(account.login("kein-at", "x").isFailure)
        assertNull(account.account.value)
    }

    @Test
    fun keyFromSurfsharkFileThenOneClickServers() = runTest {
        server.enqueue(MockResponse().setBody(serversJson))
        val (account, store) = account()
        account.refresh()
        assertNull("noch kein Schlüssel", account.account.value)
        // Eine übertragene Surfshark-Datei liefert den Schlüssel für alle Surfshark-Server.
        store.add("Deutschland · Berlin", "DE", surfsharkConf)
        account.allowAdopt()
        account.refresh()
        assertEquals("Schlüssel aus Surfshark-Datei", account.account.value)

        val servers = account.servers().getOrThrow()
        assertEquals("Serverliste ohne Anmeldung", null, server.takeRequest().getHeader("Authorization"))
        // Server ohne WireGuard-Schlüssel fallen weg; deutsche Ländernamen.
        assertEquals(listOf("Deutschland · Berlin", "Deutschland · Frankfurt am Main"), servers.map { it.label })

        val first = account.use(servers[0]).getOrThrow()
        val conf = store.configText(first.id)!!
        assertTrue(conf.contains("Endpoint = de-ber.prod.surfshark.com:51820"))
        assertTrue(conf.contains("PrivateKey = Z3VldmVucGxheWVyLXN1cmZzaGFyay10ZXN0LWtleSE="))
        val second = account.use(servers[1]).getOrThrow()
        assertEquals("der hier gewählte Standort wird ersetzt, die Datei bleibt", 2, store.profiles.value.size)
        assertTrue(store.profiles.value.contains(second))
        assertEquals(second.id, store.activeId.value)

        // Schlüssel entfernen: nicht sofort wieder aus der Datei übernehmen.
        account.logout()
        account.refresh()
        assertNull(account.account.value)
    }

    @Test
    fun keyOnlyFromSurfsharkConfigs() {
        assertEquals("Z3VldmVucGxheWVyLXN1cmZzaGFyay10ZXN0LWtleSE=", SurfsharkAccount.surfsharkKeyOf(surfsharkConf))
        assertNull(SurfsharkAccount.surfsharkKeyOf(surfsharkConf.replace("surfshark.com", "mullvad.net")))
    }

    @Test
    fun parsesUnknownFieldsDefensively() {
        assertTrue(SurfsharkApi.parseServers("kein json").isEmpty())
        assertEquals(1, SurfsharkApi.parseServers("""[{"connectionName":"xx-yy.prod.surfshark.com","pubKey":"k"}]""").size)
    }
}
