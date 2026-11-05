package com.mg.player.data.sport.proof

import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Mac
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec
import kotlin.math.abs

/**
 * Anmeldung zwischen Prüfgerät und Stick/Handy (Plan „Spiel-Finder“ O). Der 8-stellige Code wird nie übertragen:
 * Aus ihm wird ein Schlüssel abgeleitet (PBKDF2, absichtlich langsam gegen Durchprobieren mitgeschnittener Anfragen),
 * jede Anfrage trägt Zufallswert, Uhrzeit und Signatur, jede Antwort eine Signatur über den Inhalt. So bekommt ein
 * fremdes Gerät, das sich im Heimnetz als Prüfgerät ausgibt, weder den Code noch kann es falsche Ergebnisse unterschieben.
 */
object HubAuth {

    private const val ITERATIONS = 20_000
    private const val KEY_BITS = 256
    private const val NONCE_BYTES = 16
    private const val SALT = "GuevenPlayer-Pruefgeraet-v1"
    private const val ALGORITHM = "HmacSHA256"

    /** Höchste erlaubte Abweichung der Uhren beider Geräte (und Gültigkeit einer Anfrage). */
    const val MAX_SKEW_MS = 5 * 60_000L

    fun key(code: String): ByteArray {
        val spec = PBEKeySpec(code.toCharArray(), SALT.toByteArray(Charsets.UTF_8), ITERATIONS, KEY_BITS)
        return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded.also { spec.clearPassword() }
    }

    fun sign(key: ByteArray, vararg parts: String): String {
        val mac = Mac.getInstance(ALGORITHM).apply { init(SecretKeySpec(key, ALGORITHM)) }
        return mac.doFinal(parts.joinToString("\n").toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }

    /** Signatur in konstanter Zeit vergleichen. */
    fun verify(key: ByteArray, signature: String?, vararg parts: String): Boolean =
        signature != null && MessageDigest.isEqual(signature.toByteArray(Charsets.UTF_8), sign(key, *parts).toByteArray(Charsets.UTF_8))

    fun nonce(): String = ByteArray(NONCE_BYTES).also(SecureRandom()::nextBytes).joinToString("") { "%02x".format(it) }

    fun fresh(timestampMs: Long?, nowMs: Long): Boolean = timestampMs != null && abs(nowMs - timestampMs) <= MAX_SKEW_MS
}
