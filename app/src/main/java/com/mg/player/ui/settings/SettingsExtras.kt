package com.mg.player.ui.settings

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.mg.player.BuildConfig
import com.mg.player.domain.model.GroupSort
import com.mg.player.ui.common.TvTextField
import com.mg.player.ui.common.isTouch
import com.mg.player.ui.navigation.Screen
import com.mg.player.ui.theme.AppColors
import com.mg.player.ui.theme.AppIcons

/** Zustand der zweistufigen PIN-Vergabe (eingeben, bestätigen). */
internal data class PinFlow(val confirmStep: Boolean = false, val firstPin: String? = null, val error: String? = null)

/** Bereich „Inhalte“: Kategorien, Favoriten und Film-Infos (TMDB). */
internal fun LazyListScope.contentItems(c: SettingsContext) {
    settingsGroup("categories", "Kategorien & Favoriten") {
        row("manage-categories") {
            ActionRow(
                "Kategorien verwalten",
                { c.gate.runWithPin { c.navigator.push(Screen.ManageCategories) } },
                "Sortieren, ausblenden, sperren oder anheften",
                icon = AppIcons.Grid,
            )
        }
        row("group-sort") {
            ChoiceRow(
                "Reihenfolge in Gruppen",
                c.s.groupSort.label,
                { c.vm.update { it.copy(groupSort = GroupSort.entries.nextAfter(it.groupSort)) } },
                when (c.s.groupSort) {
                    GroupSort.AUTO -> "„Alle Zugänge“ A–Z, einzelne Zugänge wie beim Anbieter"
                    GroupSort.PROVIDER -> "Sender, Filme und Serien wie vom Anbieter geliefert"
                    GroupSort.ALPHABETICAL -> "Sender, Filme und Serien überall von A bis Z"
                },
                AppIcons.Reorder,
            )
        }
        row("sort-favorites") {
            ActionRow(
                "Favoriten sortieren",
                { c.navigator.push(Screen.FavoritesOrder) },
                "Eigene Reihenfolge für Sender, Filme und Serien",
                icon = AppIcons.Heart,
            )
        }
    }
    settingsGroup(
        "tmdb",
        "Film- & Serieninfos (TMDB)",
        footer = "Schlüssel kostenlos auf themoviedb.org → Einstellungen → API. Er wird nur verschlüsselt gespeichert.",
    ) {
        row("tmdb-enabled") {
            ToggleRow(
                "TMDB-Infos verwenden",
                c.s.tmdbEnabled,
                { c.vm.update { it.copy(tmdbEnabled = !it.tmdbEnabled) } },
                "Deutsche Beschreibungen, Bewertungen, FSK, Besetzung, Trailer",
                AppIcons.Star,
            )
        }
        row("tmdb-key", contentType = "text-field") { SecretKeyField(c.s.tmdbApiKey, "TMDB-API-Schlüssel", c.vm::setTmdbKey) }
        row("tmdb-clear") { ActionRow("TMDB-Infos neu laden", c.vm::clearTmdbCache, icon = AppIcons.Refresh, chevron = false) }
    }
}

/** Bereich „Sport“: gefolgte Ligen, Sender-Korrektur und eigene API-Schlüssel (verschlüsselt gespeichert). */
internal fun LazyListScope.sportItems(c: SettingsContext) {
    settingsGroup("sport", "Ligen") {
        row("sport-leagues") {
            ActionRow(
                "Ligen wählen",
                { c.navigator.push(Screen.SportLeagues) },
                c.sportProblem ?: sportSummaryText(c.sportSummary),
                trailing = "${c.vm.followedLeagues(c.s)} gewählt",
                icon = AppIcons.Sport,
            )
        }
        // Diagnose der Bzzoiro-Sender: welche Namen die Senderliste nicht kennt (OK → von Hand zuordnen).
        c.sportStations?.let { report ->
            row("sport-stations") {
                ActionRow("Sender von Bzzoiro", { c.navigator.push(Screen.SportAssign()) }, report.text(), icon = AppIcons.LiveTv)
            }
        }
        row("sport-assign") {
            ActionRow(
                "Sender korrigieren",
                { c.navigator.push(Screen.SportAssign()) },
                "Nur nötig, wenn für eine Liga kein Sender gefunden wird",
                icon = AppIcons.LiveTv,
            )
        }
    }
    settingsGroup(
        "sport-keys",
        "API-Schlüssel",
        footer = "Bzzoiro (empfohlen, kostenlos: sports.bzzoiro.com) liefert Spiele mit TV-Sendern. Sportmonks (optional) ergänzt weitere Fußball-Ligen.",
    ) {
        row("bzzoiro-token", contentType = "text-field") { SecretKeyField(c.s.bzzoiroToken, "Bzzoiro-Schlüssel", c.vm::setBzzoiroToken) }
        row("sport-token", contentType = "text-field") {
            SecretKeyField(c.s.sportmonksToken, "Sportmonks-Schlüssel") { key -> c.vm.update { it.copy(sportmonksToken = key.trim()) } }
        }
    }
}

/** Eingabe für einen geheimen Schlüssel: gespeichert nur verschlüsselt, angezeigt verdeckt. */
@Composable
private fun SecretKeyField(token: String, label: String, onSave: (String) -> Unit) {
    var draft by remember(token) { mutableStateOf(token) }
    TvTextField(
        value = draft,
        onValueChange = { draft = it },
        label = label,
        placeholder = when {
            token.isBlank() -> if (isTouch) "Tippen und Schlüssel eingeben" else "OK drücken und Schlüssel eingeben"
            else -> if (isTouch) "Schlüssel gespeichert – tippen zum Ändern" else "Schlüssel gespeichert – OK zum Ändern"
        },
        password = token.isNotBlank() && draft == token,
        onSubmit = { onSave(draft) },
        leadingIcon = AppIcons.Lock,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp),
    )
}

internal fun LazyListScope.aboutItems() {
    item(key = "s-about", contentType = "section") { SettingsSection("Über GuevenPlayer") }
    item(key = "about", contentType = "footer") {
        Text(
            "GuevenPlayer · Version ${BuildConfig.VERSION_NAME}\n\n" +
                "Diese App nutzt die TMDB-API, ist aber nicht von TMDB unterstützt oder zertifiziert. " +
                "Film- und Seriendaten sowie Bilder stammen von The Movie Database (TMDB).\n\n" +
                "Schrift: Inter, Copyright 2016 The Inter Project Authors, SIL Open Font License 1.1.",
            modifier = Modifier.padding(start = 14.dp, top = 4.dp),
            style = MaterialTheme.typography.bodySmall,
            color = AppColors.TextMuted,
        )
    }
}

/** Übersicht: wie viele Spiele geladen und wie viele davon auf den eigenen Sendern gefunden wurden. */
internal fun sportSummaryText(summary: Pair<Int, Int>?): String {
    val (total, matched) = summary ?: return "Nur Spiele dieser Ligen erscheinen unter „Sport“ (immer online abgerufen)"
    return when {
        total == 0 -> "Keine Spiele dieser Ligen in den nächsten 7 Tagen"
        matched == 0 -> "$total Spiele geladen, für keins wurde ein Sender in deinen Zugängen gefunden (siehe Sport → „Ohne Sender“)"
        else -> "$total Spiele geladen, $matched davon auf deinen Sendern"
    }
}
