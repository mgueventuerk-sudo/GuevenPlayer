package com.mg.player.core.vpn

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** Standorte: nur verschlüsselt abgelegt, eindeutige Namen, Löschen wählt einen anderen bzw. schaltet aus. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class VpnStoreTest {

    private val prefs = ApplicationProvider.getApplicationContext<Context>().getSharedPreferences("vpn-store-test", Context.MODE_PRIVATE)

    private fun store() = VpnStore(prefs, encrypt = { "sealed:" + it.reversed() }, decrypt = { it.removePrefix("sealed:").reversed() })

    @Before
    fun clear() {
        prefs.edit().clear().commit()
    }

    @Test
    fun storesOnlyEncryptedAndReadsBack() {
        val store = store()
        val profile = store.add("Deutschland", "DE", "PrivateKey = GEHEIM")
        assertFalse(prefs.all.values.joinToString().contains("GEHEIM"))
        assertEquals("PrivateKey = GEHEIM", store.configText(profile.id))
        // Neu geladen (z. B. nach App-Neustart) ist alles noch da.
        val again = store()
        assertEquals(listOf(profile), again.profiles.value)
        assertEquals(profile.id, again.active()?.id)
    }

    @Test
    fun uniqueNamesAndRemoval() {
        val store = store()
        val first = store.add("Deutschland", "DE", "a")
        val second = store.add("Deutschland", "DE", "b")
        assertEquals("Deutschland (2)", second.name)
        assertEquals(second.id, store.activeId.value)
        store.setEnabled(true)
        store.remove(second.id)
        assertEquals(first.id, store.activeId.value)
        assertTrue(store.enabled.value)
        store.remove(first.id)
        assertNull(store.active())
        assertFalse("ohne Standort ist das VPN aus", store.enabled.value)
    }

    @Test
    fun remembersLastDrop() {
        store().recordDrop(1234L, "Server antwortet nicht")
        assertEquals(VpnDrop(1234L, "Server antwortet nicht"), store().lastDrop.value)
    }
}
