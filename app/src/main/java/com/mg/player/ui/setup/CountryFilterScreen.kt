package com.mg.player.ui.setup

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.mg.player.data.filter.CountryCount
import com.mg.player.ui.common.ActionButton
import com.mg.player.ui.common.CenteredMessage
import com.mg.player.ui.common.FocusSurface
import com.mg.player.ui.common.appViewModel
import com.mg.player.ui.common.isTouch
import com.mg.player.ui.theme.AnimatedIcon
import com.mg.player.ui.theme.AppColors
import com.mg.player.ui.theme.AppIcons
import com.mg.player.ui.theme.BrandLogo
import kotlinx.coroutines.delay

/**
 * "Welche Länder möchtest du sehen?" – nach dem Einrichten eines Zugangs ([firstRun]) oder aus den Einstellungen.
 * Gruppen nicht gewählter Länder werden ausgeblendet, damit die Listen übersichtlich bleiben.
 */
@Composable
fun CountryFilterScreen(firstRun: Boolean, onDone: () -> Unit, playlistId: Long? = null) {
    val vm = appViewModel(key = "country-$firstRun-$playlistId") { CountryFilterViewModel(it, firstRun, playlistId) }
    val ui by vm.ui.collectAsStateWithLifecycle()
    val done by vm.done.collectAsStateWithLifecycle()
    LaunchedEffect(done) { if (done) onDone() }
    // Beim Einrichten heißt "Zurück": jetzt nicht filtern, alles anzeigen.
    BackHandler(enabled = firstRun && !ui.applying) { vm.showAll() }

    val summary = ui.summary
    if (ui.loading || summary == null || ui.applying) {
        CenteredMessage(if (ui.applying) "Wird übernommen…" else "Suche Länder in deiner Playlist…", loading = true)
        return
    }
    val firstRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        // Das Raster braucht einen Moment, bis die erste Karte da ist.
        repeat(10) {
            if (runCatching { firstRequester.requestFocus() }.isSuccess) return@LaunchedEffect
            delay(60)
        }
    }

    val touch = isTouch
    Column(Modifier.fillMaxSize().padding(horizontal = if (touch) 16.dp else 64.dp, vertical = if (touch) 16.dp else 36.dp)) {
        if (firstRun) BrandLogo(markSize = 40.dp, fontSize = 24.sp, modifier = Modifier.padding(bottom = 18.dp))
        Text("Welche Länder möchtest du sehen?", style = MaterialTheme.typography.headlineMedium)
        Text(
            "In deiner Playlist stecken Gruppen aus ${countryText(summary.countries.size)}. Nur deine Auswahl wird " +
                "angezeigt, alles andere blenden wir aus. Ändern kannst du das jederzeit unter Einstellungen → Länder & Sprachen.",
            style = MaterialTheme.typography.bodyLarge,
            color = AppColors.TextSecondary,
            modifier = Modifier.padding(top = 6.dp, bottom = if (touch) 8.dp else 20.dp).fillMaxWidth(if (touch) 1f else 0.85f),
        )
        LazyVerticalGrid(
            // Am Handy hochkant eine Spalte (Name und Zahlen passen ganz drauf), quer bzw. am Tablet mehrere.
            columns = if (touch) GridCells.Adaptive(300.dp) else GridCells.Fixed(4),
            modifier = Modifier.weight(1f).focusRestorer(FocusRequester.Default),
            contentPadding = PaddingValues(if (touch) 4.dp else 16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items(summary.countries, key = { it.code }, contentType = { "country" }) { country ->
                val index = summary.countries.indexOf(country)
                CountryCard(
                    country = country,
                    selected = country.code in ui.selected,
                    onClick = { vm.toggle(country.code) },
                    modifier = if (index == 0) Modifier.focusRequester(firstRequester) else Modifier,
                )
            }
            // Nur zeigen, wenn es solche Gruppen gibt – „0 Gruppen“ zum Ankreuzen verwirrt nur.
            if (summary.unknownGroups > 0) {
                item(key = "unknown", contentType = "unknown") {
                    ChoiceCard(
                        badge = "?",
                        title = "Ohne Länderangabe",
                        subtitle = "${groupCount(summary.unknownGroups)}, z. B. „Sport“ oder „Kinder“",
                        selected = ui.keepUnknown,
                        onClick = vm::toggleUnknown,
                    )
                }
            }
        }
        ui.hint?.let { Text(it, color = AppColors.Danger, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 10.dp)) }
        val apply = "Übernehmen (${ui.selected.size} ausgewählt)"
        if (touch) {
            // Am Handy: Hauptknopf über die ganze Breite, die anderen darunter nebeneinander.
            ActionButton(apply, onClick = vm::apply, icon = AppIcons.Check, primary = true, modifier = Modifier.padding(top = 12.dp).fillMaxWidth())
            Row(Modifier.padding(top = 8.dp).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                ActionButton("Alles anzeigen", onClick = vm::showAll, icon = AppIcons.Globe, modifier = Modifier.weight(1f))
                if (!firstRun) ActionButton("Abbrechen", onClick = onDone, icon = AppIcons.Close, modifier = Modifier.weight(1f))
            }
        } else {
            Row(Modifier.padding(top = 16.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                ActionButton(apply, onClick = vm::apply, icon = AppIcons.Check, primary = true)
                ActionButton("Alles anzeigen", onClick = vm::showAll, icon = AppIcons.Globe)
                if (!firstRun) ActionButton("Abbrechen", onClick = onDone, icon = AppIcons.Close)
            }
        }
    }
}

@Composable
private fun CountryCard(country: CountryCount, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val parts = listOfNotNull(
        country.live.takeIf { it > 0 }?.let { "Live $it" },
        country.movies.takeIf { it > 0 }?.let { "Filme $it" },
        country.series.takeIf { it > 0 }?.let { "Serien $it" },
    )
    ChoiceCard(
        badge = flagFor(country.code) ?: country.code.take(3),
        title = country.label,
        subtitle = "${groupCount(country.total)} · " + parts.joinToString(" · "),
        selected = selected,
        onClick = onClick,
        modifier = modifier,
    )
}

@Composable
private fun ChoiceCard(badge: String, title: String, subtitle: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    FocusSurface(
        onClick = onClick,
        modifier = modifier.fillMaxWidth().height(78.dp),
        focusedScale = 1.04f,
        containerColor = if (selected) AppColors.AccentSoft else AppColors.Glass,
        focusedContainerColor = if (selected) AppColors.Accent.copy(alpha = 0.42f) else AppColors.GlassRaised,
    ) {
        Row(Modifier.fillMaxSize().padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(width = 48.dp, height = 36.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(
                        if (selected) {
                            Brush.linearGradient(
                                AppColors.BrandGradient,
                            )
                        } else {
                            Brush.linearGradient(listOf(AppColors.GlassRaised, AppColors.GlassRaised))
                        },
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    badge,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = if (selected) AppColors.OnAccent else AppColors.TextPrimary,
                    maxLines = 1,
                )
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(subtitle, style = MaterialTheme.typography.labelSmall, color = AppColors.TextSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (selected) AnimatedIcon(AppIcons.CheckCircle, tint = AppColors.AccentText, active = true, size = 24.dp, contentDescription = "ausgewählt")
        }
    }
}

/** Flaggen-Emoji für echte Länder; Sprachgruppen wie "Arabisch" bekommen stattdessen ihr Kürzel. */
private fun flagFor(code: String): String? {
    val iso = when (code) {
        "UK" -> "GB"
        "FA" -> "IR"
        "AR", "KU", "EN", "LATAM", "BALKAN", "NORDIC", "AFRICA", "ASIA" -> return null
        else -> code
    }
    if (iso.length != 2) return null
    return iso.map { String(Character.toChars(0x1F1E6 + (it - 'A'))) }.joinToString("")
}

/** „1 Gruppe“, „2 Gruppen“. */
internal fun groupCount(n: Int): String = if (n == 1) "1 Gruppe" else "$n Gruppen"

private fun countryText(count: Int): String = if (count == 1) "einem Land bzw. einer Sprache" else "$count Ländern und Sprachen"
