package com.mg.player.domain.model

import androidx.compose.runtime.Immutable
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Verbindungen eines Zugangs laut Anbieter: [active] von [max] gleichzeitigen Streams belegt (0 = unbegrenzt). */
@Immutable
data class ConnectionStatus(val active: Int, val max: Int, val expiresAtMs: Long? = null) {
    /** Jemand schaut gerade über diesen Zugang (auch dieses Gerät zählt mit). */
    val inUse: Boolean get() = active > 0

    /** Alle Verbindungen belegt – ein weiterer Stream würde abgelehnt oder einen anderen beenden. */
    val full: Boolean get() = max in 1..active

    /** Kurzform für das Abzeichen, z. B. "1/1"; ohne Begrenzung nur die Zahl der aktiven Verbindungen. */
    val label: String get() = if (max > 0) "$active/$max" else "$active/∞"

    /** Vorlesbare Beschreibung (Bedienungshilfen und Tests). */
    val description: String
        get() = when {
            max <= 0 -> "$active Verbindungen aktiv"
            active == 0 -> "Frei – 0 von $max Verbindungen belegt"
            full -> "In Nutzung – $active von $max Verbindungen belegt (voll)"
            else -> "In Nutzung – $active von $max Verbindungen belegt"
        }
}

/** Hinweis zum Abo eines Zugangs. [warning] = gelb (läuft bald ab), [expired] = rot. */
@Immutable
data class AccountNotice(val text: String, val warning: Boolean, val expired: Boolean)

private const val DAY_MS = 24 * 3_600_000L

/** „Abo bis 12.11.2026“, gelb ab 7 Tage vorher („Abo läuft in 3 Tagen ab“ / „heute ab“), rot wenn abgelaufen. */
fun ConnectionStatus.subscriptionNotice(nowMs: Long): AccountNotice? {
    val end = expiresAtMs ?: return null
    val days = Math.floorDiv(end - nowMs, DAY_MS)
    return when {
        end <= nowMs -> AccountNotice("Abo abgelaufen", warning = false, expired = true)
        days == 0L -> AccountNotice("Abo läuft heute ab", warning = true, expired = false)
        days < 7 -> AccountNotice("Abo läuft in $days ${if (days == 1L) "Tag" else "Tagen"} ab", warning = true, expired = false)
        else -> AccountNotice("Abo bis ${formatDate(end)}", warning = false, expired = false)
    }
}

/**
 * Genauere Meldung bei „Zugriff verweigert“ (HTTP 401/403): Der Grund steht meist schon in den Kontodaten.
 * null = keine bessere Erklärung bekannt (dann bleibt die allgemeine Meldung).
 */
fun ConnectionStatus.accessDeniedReason(nowMs: Long): String? {
    val end = expiresAtMs
    return when {
        end != null && end <= nowMs -> "Zugriff verweigert – das Abo ist am ${formatDate(end)} abgelaufen."

        full ->
            "Zugriff verweigert – alle $max Verbindungen dieses Zugangs sind belegt. " +
                "Auf einem anderen Gerät die Wiedergabe beenden und erneut versuchen."

        else -> null
    }
}

private fun formatDate(ms: Long): String = SimpleDateFormat("dd.MM.yyyy", Locale.GERMANY).format(Date(ms))

/** Zugang eines Eintrags aus seiner ID („12:L:105“ → 12); null bei Aufnahmen und Fremd-IDs. */
fun playlistIdOf(itemId: String): Long? = itemId.substringBefore(':', "").toLongOrNull()
