package com.mg.player.data.sport.proof

import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Mac
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec
import kotlin.math.abs

/**
 * Anmeldung zwischen Prüfgerät und Stick/Handy (Plan „Spiel-Finder“ O). Das Geheimnis wird nie übertragen: Aus ihm wird
 * ein Schlüssel abgeleitet, jede Anfrage trägt Zufallswert, Uhrzeit und Signatur, jede Antwort eine Signatur über den
 * Inhalt. So bekommt ein fremdes Gerät, das sich im Heimnetz als Prüfgerät ausgibt, weder das Geheimnis noch kann es
 * falsche Ergebnisse unterschieben.
 *
 * **Warum kein 8-stelliger Zifferncode mehr (ab 3.57):** Eine einzige mitgeschnittene Anfrage (Pfad, Zufallswert,
 * Uhrzeit, Signatur) lässt sich ohne das Prüfgerät durchprobieren – dort bremst keine Sperre. 10⁸ Codes gegen
 * PBKDF2/20 000 sind auf einer Grafikkarte in der Größenordnung einer halben Stunde erschöpft, und weil der Salzwert
 * für alle Installationen derselbe war, hätte sich die Tabelle **einmal** vorberechnen lassen und danach für jedes
 * Gerät gegolten. Mehr Runden kaufen dagegen nur Faktor zwei bis vier – zu wenig. Deshalb trägt das Geheimnis jetzt
 * [SECRET_BITS] Bit Zufall ([newSecret]): Durchprobieren ist damit keine Frage der Rechenzeit mehr, sondern unmöglich.
 * Das Geheimnis ist in Vierergruppen aus einem Alphabet ohne Verwechslungen (kein I, L, O, U) geschrieben, damit es
 * sich auf einer Fernbedienung noch eintippen lässt; der QR-Code am Prüfgerät erspart das Tippen.
 *
 * Alte 8-stellige Codes funktionieren weiter ([isStrong] false → der langsame PBKDF2-Weg), damit ein bestehendes Paar
 * nach dem Update nicht stehen bleibt. Neu angelegt werden sie nicht mehr.
 */
object HubAuth {

    private const val ITERATIONS = 20_000
    private const val KEY_BITS = 256
    private const val NONCE_BYTES = 16
    private const val SALT = "GuevenPlayer-Pruefgeraet-v1"
    private const val ALGORITHM = "HmacSHA256"

    /** Höchste erlaubte Abweichung der Uhren beider Geräte (und Gültigkeit einer Anfrage). */
    const val MAX_SKEW_MS = 5 * 60_000L

    /** Zufall im Geheimnis: 80 Bit (16 Zeichen à 5 Bit) – nicht durchprobierbar und noch tippbar. */
    const val SECRET_BITS = 80

    /** Zeichen des Geheimnisses: Crockford-Base32 ohne I, L, O und U (Verwechslungen auf dem Bildschirm). */
    private const val ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"
    private const val BITS_PER_CHAR = 5
    private const val SECRET_CHARS = SECRET_BITS / BITS_PER_CHAR
    private const val GROUP = 4

    /** Verwechslungen beim Eintippen abfangen: I und L sind 1, O ist 0. */
    private val CONFUSED = mapOf('I' to '1', 'L' to '1', 'O' to '0')

    /** Neues Geheimnis fürs Prüfgerät, in Vierergruppen („X4K9-2M7P-…“). */
    fun newSecret(): String {
        val random = SecureRandom()
        val chars = CharArray(SECRET_CHARS) { ALPHABET[random.nextInt(ALPHABET.length)] }
        return String(chars).chunked(GROUP).joinToString("-")
    }

    /**
     * Eingabe in die gespeicherte Form bringen: Gruppen, Leerzeichen und Verwechslungen weg. Ein alter Zifferncode
     * bleibt unverändert (er besteht nur aus Ziffern und ist kürzer).
     */
    fun normalize(input: String): String {
        val canonical = canonical(input)
        return if (canonical.length == SECRET_CHARS) canonical.chunked(GROUP).joinToString("-") else canonical
    }

    /** Trägt dieses Geheimnis die volle Zufallsmenge (dann ist die langsame Ableitung unnötig)? */
    fun isStrong(secret: String): Boolean {
        val canonical = canonical(secret)
        return canonical.length == SECRET_CHARS && canonical.all { it in ALPHABET }
    }

    /**
     * Schlüssel zum Geheimnis. Starkes Geheimnis: eine HMAC-Ableitung, schnell – gegen 80 Bit Zufall hilft Langsamkeit
     * nichts, sie kostet nur Rechenzeit auf dem Stick. Alter Zifferncode: PBKDF2 wie bisher, damit bestehende Paare
     * weiterlaufen.
     */
    fun key(secret: String): ByteArray {
        // Immer über die kanonische Form: Wer „x4k9 2m7p …“ eintippt, muss denselben Schlüssel bekommen wie das
        // Prüfgerät, das „X4K9-2M7P-…“ anzeigt. Sonst scheitert die Anmeldung ohne erkennbaren Grund.
        val canonical = canonical(secret)
        if (isStrong(secret)) return hmac(canonical.toByteArray(Charsets.UTF_8), SALT)
        val spec = PBEKeySpec(canonical.toCharArray(), SALT.toByteArray(Charsets.UTF_8), ITERATIONS, KEY_BITS)
        return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded.also { spec.clearPassword() }
    }

    fun sign(key: ByteArray, vararg parts: String): String =
        hmac(key, parts.joinToString("\n")).joinToString("") { "%02x".format(it) }

    /** Signatur in konstanter Zeit vergleichen. */
    fun verify(key: ByteArray, signature: String?, vararg parts: String): Boolean =
        signature != null && MessageDigest.isEqual(signature.toByteArray(Charsets.UTF_8), sign(key, *parts).toByteArray(Charsets.UTF_8))

    fun nonce(): String = ByteArray(NONCE_BYTES).also(SecureRandom()::nextBytes).joinToString("") { "%02x".format(it) }

    fun fresh(timestampMs: Long?, nowMs: Long): Boolean = timestampMs != null && abs(nowMs - timestampMs) <= MAX_SKEW_MS

    /**
     * Kanonische Form eines Geheimnisses: Gruppenstriche und Leerzeichen weg, Großbuchstaben, Verwechslungen
     * aufgelöst. Alte Zifferncodes bleiben unverändert (Ziffern kennen keine Verwechslung).
     */
    private fun canonical(secret: String) =
        secret.uppercase().filter { it.isLetterOrDigit() }.map { CONFUSED[it] ?: it }.joinToString("")

    private fun hmac(key: ByteArray, message: String): ByteArray =
        Mac.getInstance(ALGORITHM).apply { init(SecretKeySpec(key, ALGORITHM)) }.doFinal(message.toByteArray(Charsets.UTF_8))
}
