package com.mg.player.data.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Zugangsdaten in M3U-Adressen werden als Platzhalter gespeichert und erst beim Abspielen eingesetzt. */
class CredentialMaskTest {

    @Test
    fun pathAndQueryAreMaskedAndRevealed() {
        val path = "http://tv.example:8080/live/max/geheim/123.ts"
        val masked = CredentialMask.mask(path, "max", "geheim")
        assertEquals("http://tv.example:8080/live/{u}/{p}/123.ts", masked)
        assertEquals(path, CredentialMask.reveal(masked, "max", "geheim"))

        val query = "http://tv.example/get.php?username=max&password=geheim&stream=5|User-Agent=X"
        val maskedQuery = CredentialMask.mask(query, "max", "geheim")
        assertFalse("geheim" in maskedQuery)
        assertTrue(CredentialMask.isMasked(maskedQuery))
        assertEquals(query, CredentialMask.reveal(maskedQuery, "max", "geheim"))

        val end = "http://tv.example/play?username=max&password=geheim"
        assertEquals("http://tv.example/play?username={u}&password={p}", CredentialMask.mask(end, "max", "geheim"))
    }

    @Test
    fun foreignAddressesStayUntouched() {
        val other = "http://cdn.example/stream/maxi/geheimnis/1.ts"
        assertEquals(other, CredentialMask.mask(other, "max", "geheim"))
        assertFalse(CredentialMask.isMasked(other))
        assertEquals("http://a/b", CredentialMask.mask("http://a/b", "", ""))
    }
}
