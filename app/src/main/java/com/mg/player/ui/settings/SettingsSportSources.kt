package com.mg.player.ui.settings

import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mg.player.core.util.TimeUtils
import com.mg.player.data.sport.proof.ProofEvaluator
import com.mg.player.domain.model.MatchSource
import com.mg.player.domain.sport.SportBoard
import com.mg.player.ui.common.LocalAppContainer
import com.mg.player.ui.theme.AppIcons

/**
 * Sport → „Quellen“ und „Länder“ (ab 3.53, Plan „Spiel-Finder“ K und Länderauswahl): Jede Quelle der Sender-Zuordnung
 * lässt sich abschalten; daneben steht, wie viele angezeigte Spiele gerade von ihr stammen. Länder bestimmen, welche
 * Senderlisten (Bzzoiro) geladen werden.
 */
internal fun LazyListScope.sportSourceItems(c: SettingsContext) {
    settingsGroup(
        "sport-sources",
        "Quellen der Sender",
        footer = "Event-Sender des Anbieters („PRIME DE 016 [Team – Team]“) werden nicht mehr angezeigt: Ihr Name ist kein Beweis.",
    ) {
        MatchSource.entries.filter { it.switchable }.forEach { source ->
            row("sport-source-${source.name}") {
                val on = source.name !in c.s.sportSourcesOff
                ToggleRow(
                    source.label.replaceFirstChar { it.uppercase() },
                    on,
                    { c.vm.update { s -> s.copy(sportSourcesOff = if (on) s.sportSourcesOff + source.name else s.sportSourcesOff - source.name) } },
                    sourceCountText(source),
                    AppIcons.Sport,
                )
            }
        }
        proofDeviceRows(c)
        row("sport-source-counts") { SourceSummaryRow() }
        row("sport-proofs") { ProofHistoryRow() }
    }
    settingsGroup("sport-countries", "Länder", footer = "Senderlisten dieser Länder werden geladen; mindestens ein Land bleibt gewählt.") {
        SPORT_COUNTRIES.forEach { (code, label) ->
            row("sport-country-$code") {
                val on = code in c.s.sportCountries
                ToggleRow(label, on, {
                    c.vm.update { s ->
                        val next = if (on) s.sportCountries - code else s.sportCountries + code
                        s.copy(sportCountries = next.ifEmpty { s.sportCountries })
                    }
                }, icon = AppIcons.Globe)
            }
        }
    }
}

/** Prüfgerät und Code fürs Heimnetz (Plan „Spiel-Finder“ N/O); der Wecker folgt der Einstellung von selbst (PlayerApp). */
private fun GroupBuilder.proofDeviceRows(c: SettingsContext) {
    row("sport-proof-device") {
        ToggleRow(
            "Dieses Gerät prüft im Hintergrund",
            c.s.sportProofDevice,
            { c.vm.update { it.copy(sportProofDevice = !it.sportProofDevice) } },
            "2 Minuten vor Anpfiff, höchstens 15 Minuten, nur über freie Playlists. Nur auf einem Gerät einschalten (z. B. Fire TV Cube).",
            AppIcons.Clock,
        )
    }
    if (c.s.sportProofDevice) {
        row("sport-hub-code") {
            ActionRow(
                "Code für andere Geräte",
                {},
                c.s.sportHubCode.ifBlank { "wird erzeugt …" } + " – auf Stick/Handy unter „Code des Prüfgeräts“ eintragen",
                icon = AppIcons.Lock,
                chevron = false,
            )
        }
    } else {
        row("sport-hub-join", contentType = "text-field") {
            SecretKeyField(c.s.sportHubJoinCode, "Code des Prüfgeräts (z. B. Fire TV Cube)") { code ->
                c.vm.update { it.copy(sportHubJoinCode = code.filter(Char::isDigit)) }
            }
        }
    }
}

@Composable
private fun sourceCountText(source: MatchSource): String {
    val board by LocalAppContainer.current.sport.repository.board.collectAsStateWithLifecycle()
    val count = board?.let { sourceCounts(it)[source] } ?: 0
    val rate = LocalAppContainer.current.sport.stats.overallPercent(source)?.let { " Vorhersage $it % (an bewiesenen Spielen gemessen)." }.orEmpty()
    return "${source.detail} Gerade $count ${if (count == 1) "Spiel" else "Spiele"}.$rate"
}

@Composable
private fun SourceSummaryRow() {
    val board by LocalAppContainer.current.sport.repository.board.collectAsStateWithLifecycle()
    val text = board?.let { b -> sourceCounts(b).entries.sortedByDescending { it.value }.joinToString(" · ") { "${it.key.label}: ${it.value}" } }
        ?.ifBlank { null } ?: "Noch keine Spiele mit Sender"
    ActionRow("Angezeigte Spiele je Quelle", {}, text, icon = AppIcons.Document, chevron = false)
}

/** Plan „Spiel-Finder“ M: die letzten Prüfungen (Bild, Zuschauen, „Falscher Sender“) mit Ergebnis. */
@Composable
private fun ProofHistoryRow() {
    val proofs = LocalAppContainer.current.sport.proofs
    // Ohne remember: Die Liste wächst während der Einstellungen weiter (gemerkt bliebe der Stand beim Öffnen stehen).
    val text = proofs.recent().take(MAX_HISTORY_SHOWN).joinToString("\n") { r ->
        "${TimeUtils.formatClock(r.atMs)} · ${r.channel}: ${verdictLabel(r.verdict)} (${r.by})"
    }.ifBlank { "Noch keine Prüfung – sie läuft bei Spielen, die gerade laufen, über freie Playlists." }
    ActionRow("Letzte Prüfungen", {}, text, icon = AppIcons.Shield, chevron = false)
}

private fun verdictLabel(verdict: ProofEvaluator.Verdict): String = when (verdict) {
    ProofEvaluator.Verdict.PROVEN -> "bewiesen"
    ProofEvaluator.Verdict.PLACEHOLDER -> "Platzhalter/Standbild"
    ProofEvaluator.Verdict.OTHER -> "anderes Programm"
    ProofEvaluator.Verdict.UNKNOWN -> "nicht lesbar"
}

private const val MAX_HISTORY_SHOWN = 8

/** Wie viele angezeigte Spiele (live und kommend) aus welcher Quelle stammen. */
internal fun sourceCounts(board: SportBoard): Map<MatchSource, Int> =
    (board.live + board.upcoming.values.flatten()).groupingBy { it.source }.eachCount()

/** Länder zur Auswahl (ISO-Kürzel wie bei Bzzoiro). */
internal val SPORT_COUNTRIES = listOf(
    "DE" to "Deutschland", "AT" to "Österreich", "CH" to "Schweiz", "TR" to "Türkei", "GB" to "Großbritannien",
    "ES" to "Spanien", "IT" to "Italien", "FR" to "Frankreich", "NL" to "Niederlande", "BE" to "Belgien",
    "PT" to "Portugal", "PL" to "Polen", "US" to "USA",
)
