package com.mg.player.data.sport.proof

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HubAuthTest {

    private val key = HubAuth.key("12345678")

    @Test
    fun sameCodeSameKeyOtherCodeOtherKey() {
        assertTrue(key.contentEquals(HubAuth.key("12345678")))
        assertFalse(key.contentEquals(HubAuth.key("12345679")))
    }

    @Test
    fun signatureCoversEveryPart() {
        val sig = HubAuth.sign(key, "/release", "abc", "1000", "acct")
        assertTrue(HubAuth.verify(key, sig, "/release", "abc", "1000", "acct"))
        assertFalse(HubAuth.verify(key, sig, "/release", "abc", "1000", "other"))
        assertFalse(HubAuth.verify(key, sig, "/proofs", "abc", "1000", "acct"))
        assertFalse(HubAuth.verify(HubAuth.key("00000000"), sig, "/release", "abc", "1000", "acct"))
        assertFalse(HubAuth.verify(key, null, "/release", "abc", "1000", "acct"))
    }

    @Test
    fun signatureDoesNotContainTheCode() {
        assertFalse(HubAuth.sign(key, "/proofs", "n", "1").contains("12345678"))
    }

    @Test
    fun newSecretCarriesFullRandomnessAndIsTypable() {
        val secrets = (1..200).map { HubAuth.newSecret() }
        // Keine Wiederholung bei 200 Ziehungen – und 16 Zeichen à 5 Bit sind 80 Bit Zufall.
        assertEquals(secrets.size, secrets.toSet().size)
        secrets.forEach { secret ->
            assertTrue(secret, HubAuth.isStrong(secret))
            assertEquals("XXXX-XXXX-XXXX-XXXX", secret.replace(Regex("[^-]"), "X"))
            // Keine Zeichen, die man auf dem Bildschirm verwechselt.
            assertFalse(secret, secret.any { it in "ILOU" })
        }
    }

    /**
     * Der Suchraum ist die eigentliche Schutzmaßnahme, nicht die Langsamkeit der Ableitung – darum als Invariante.
     * Gemessen auf einem CPU-Kern: PBKDF2-HMAC-SHA256 mit 20 000 Runden schafft 273 Kandidaten/s. Der alte
     * 8-stellige Code (10^8 ≈ 2^26,6) war damit auf einer Grafikkarte in 12 bis 60 Minuten erschöpft; 2^80 nicht.
     */
    @Test
    fun theSearchSpaceIsTooBigToBruteForce() {
        assertEquals(80, HubAuth.SECRET_BITS)
        val bare = HubAuth.newSecret().filter { it.isLetterOrDigit() }
        assertEquals(16, bare.length)
        // 16 Zeichen à 5 Bit: Jedes Zeichen muss aus einem Alphabet von genau 32 stammen, sonst stimmt die Rechnung nicht.
        val alphabet = (1..4_000).flatMap { HubAuth.newSecret().filter(Char::isLetterOrDigit).toList() }.toSet()
        assertEquals(32, alphabet.size)
    }

    @Test
    fun entryIsForgivingAboutGroupsCaseAndConfusedCharacters() {
        val secret = "X4K9-2M7P-0ABC-DEFG"
        assertEquals(secret, HubAuth.normalize("x4k9 2m7p0abc-defg"))
        // I und L sind 1, O ist 0 – dieselbe Eingabe ergibt denselben Schlüssel.
        assertEquals(HubAuth.normalize("1111-2m7p-0abc-defg"), HubAuth.normalize("IlL1-2M7P-OABC-DEFG"))
        assertTrue(HubAuth.key(secret).contentEquals(HubAuth.key("x4k9 2m7p 0abc defg")))
    }

    @Test
    fun strongSecretsSkipTheSlowDerivationButOldCodesKeepWorking() {
        // Ein starkes Geheimnis braucht kein PBKDF2: 80 Bit Zufall sind nicht durchprobierbar.
        assertTrue(HubAuth.isStrong("X4K9-2M7P-0ABC-DEFG"))
        // Alte 8-stellige Codes gelten nicht als stark und laufen über den langsamen Weg weiter.
        assertFalse(HubAuth.isStrong("12345678"))
        assertTrue(key.contentEquals(HubAuth.key("12345678")))
        // Beide Wege liefern einen Schlüssel voller Länge und unterscheiden sich.
        assertEquals(32, HubAuth.key("X4K9-2M7P-0ABC-DEFG").size)
        assertEquals(32, key.size)
        assertFalse(HubAuth.key("X4K9-2M7P-0ABC-DEFG").contentEquals(key))
    }

    @Test
    fun signaturesOfTwoSecretsNeverMatch() {
        val a = HubAuth.key(HubAuth.newSecret())
        val b = HubAuth.key(HubAuth.newSecret())
        val signature = HubAuth.sign(a, "/proofs", "n", "1")
        assertTrue(HubAuth.verify(a, signature, "/proofs", "n", "1"))
        assertFalse(HubAuth.verify(b, signature, "/proofs", "n", "1"))
    }

    @Test
    fun noncesAreRandomAndRequestsExpire() {
        assertNotEquals(HubAuth.nonce(), HubAuth.nonce())
        assertEquals(32, HubAuth.nonce().length)
        assertTrue(HubAuth.fresh(1_000_000L, 1_000_000L + HubAuth.MAX_SKEW_MS))
        assertFalse(HubAuth.fresh(1_000_000L, 1_000_001L + HubAuth.MAX_SKEW_MS))
        assertFalse(HubAuth.fresh(null, 0L))
    }
}
