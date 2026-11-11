package com.mg.player.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.mg.player.ui.common.LocalSurfaceFocused
import com.mg.player.ui.common.RowColors
import com.mg.player.ui.theme.AppShapes
import com.mg.player.ui.theme.AnimatedIcon
import com.mg.player.ui.theme.AppColors
import com.mg.player.ui.theme.smoothShape
import com.mg.player.ui.theme.AppIcon
import com.mg.player.ui.theme.Dimens
import com.mg.player.ui.theme.contentColorFor

/*
 * Gruppierte Einstellungen: Zeilen, die zusammengehören, liegen in einem gemeinsamen Kasten mit feinen Trennlinien
 * (statt jede Zeile als eigene Glasfläche). Jede Zeile bleibt ein eigenes Element der LazyColumn – Fokus und
 * Wiederverwendung funktionieren wie bisher.
 */

/** true innerhalb eines Gruppen-Kastens: Zeilen zeichnen dann keinen eigenen Hintergrund. */
internal val LocalInGroup = compositionLocalOf { false }

internal class GroupRow(val key: String, val contentType: String, val modifier: Modifier, val content: @Composable () -> Unit)

internal class GroupBuilder {
    internal val rows = ArrayList<GroupRow>()

    /** [modifier] z. B. ein Fokus-Ziel für die Rückkehr aus einer Unterseite (geht an die Zeile darin). */
    fun row(key: String, contentType: String = "row", modifier: Modifier = Modifier, content: @Composable () -> Unit) {
        rows += GroupRow(key, contentType, modifier, content)
    }
}

/** Eine Gruppe mit optionaler Überschrift und Fußnote. Leere Gruppen erscheinen gar nicht. */
internal fun LazyListScope.settingsGroup(key: String, title: String? = null, footer: String? = null, build: GroupBuilder.() -> Unit) {
    val rows = GroupBuilder().apply(build).rows
    if (rows.isEmpty()) return
    item(key = "$key-title", contentType = if (title != null) "section" else "gap") {
        if (title != null) SettingsSection(title) else Spacer(Modifier.height(GROUP_GAP))
    }
    rows.forEachIndexed { index, row ->
        item(key = row.key, contentType = row.contentType) {
            GroupCell(first = index == 0, last = index == rows.lastIndex) { Box(row.modifier) { row.content() } }
        }
    }
    if (footer != null) {
        item(key = "$key-footer", contentType = "footer") {
            Text(
                footer,
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp),
                style = MaterialTheme.typography.bodySmall,
                color = AppColors.TextMuted,
            )
        }
    }
}

/** Ein Abschnitt des Gruppen-Kastens: oben bzw. unten abgerundet, dazwischen eine feine Linie. */
@Composable
private fun GroupCell(first: Boolean, last: Boolean, content: @Composable () -> Unit) {
    val radius = Dimens.CardRadius
    val shape = smoothShape(top = if (first) radius else 0.dp, bottom = if (last) radius else 0.dp)
    Box(Modifier.fillMaxWidth().clip(shape).background(AppColors.Glass)) {
        if (!first) {
            Box(Modifier.padding(start = DIVIDER_INSET, end = 16.dp).fillMaxWidth().height(1.dp).background(AppColors.GlassBorder))
        }
        Box(Modifier.padding(horizontal = 4.dp, vertical = 3.dp)) {
            CompositionLocalProvider(LocalInGroup provides true) { content() }
        }
    }
}

/** Kopf einer Seite: Titel und ein Satz, worum es geht. */
@Composable
internal fun SettingsPageHeader(title: String, lead: String?, breadcrumb: String? = null) {
    Column(Modifier.padding(start = 6.dp, top = 6.dp, bottom = 2.dp)) {
        if (breadcrumb != null) {
            Text("$breadcrumb  ›", style = MaterialTheme.typography.labelLarge, color = AppColors.TextMuted)
            Spacer(Modifier.height(2.dp))
        }
        Text(title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, color = AppColors.TextPrimary)
        if (lead != null) {
            Text(lead, style = MaterialTheme.typography.bodyMedium, color = AppColors.TextSecondary, modifier = Modifier.padding(top = 2.dp))
        }
    }
}

/**
 * Kleines Symbol-Kästchen links in einer Zeile; bei Gefahr rot, im Fokus in der Kontrastfarbe.
 * [altAccent]: Partnerfarbe statt Hauptakzent – nur für die Bereichsliste der Einstellungen gedacht
 * (PhoneSettingsMenu), damit Kategorien sich auf einen Blick unterscheiden, nicht für einzelne Aktionen
 * innerhalb eines Bereichs (dort bliebe eine zufällige Zweitfarbe bedeutungslos).
 */
@Composable
internal fun RowIcon(icon: AppIcon, danger: Boolean = false, altAccent: Boolean = false) {
    val focused = LocalSurfaceFocused.current
    val accent = if (altAccent) AppColors.AccentAlt else AppColors.Accent
    val accentText = if (altAccent) contentColorFor(AppColors.AccentAlt) else AppColors.AccentText
    val tint = when {
        focused -> Color.White
        danger -> AppColors.Danger
        else -> accentText
    }
    val fill = when {
        focused -> if (danger) AppColors.Danger else accent
        danger -> AppColors.Danger.copy(alpha = 0.14f)
        else -> accent.copy(alpha = 0.16f)
    }
    Box(Modifier.size(34.dp).clip(AppShapes.Small).background(fill), contentAlignment = Alignment.Center) {
        AnimatedIcon(icon, tint = tint, active = focused, size = 19.dp)
    }
}

/** Kleine Marke (Aktiv, HTTP, Auto-Update …). */
@Composable
internal fun SettingsChip(text: String, tone: ChipTone = ChipTone.NEUTRAL) {
    val focused = LocalSurfaceFocused.current
    val (fill, color) = when (tone) {
        ChipTone.ACCENT -> AppColors.Accent to Color.White
        ChipTone.SUCCESS -> AppColors.Success.copy(alpha = 0.16f) to if (focused) Color(0xFF166534) else AppColors.Success
        ChipTone.WARNING -> AppColors.Rating.copy(alpha = 0.16f) to if (focused) Color(0xFF92400E) else AppColors.Rating
        ChipTone.DANGER -> AppColors.Danger.copy(alpha = 0.16f) to AppColors.Danger
        ChipTone.NEUTRAL -> (if (focused) AppColors.OnFocus.copy(alpha = 0.08f) else AppColors.TextPrimary.copy(alpha = 0.08f)) to RowColors.secondary
    }
    Text(
        text,
        modifier = Modifier.clip(RoundedCornerShape(50)).background(fill).padding(horizontal = 9.dp, vertical = 2.dp),
        style = MaterialTheme.typography.labelMedium,
        fontWeight = FontWeight.SemiBold,
        color = color,
        maxLines = 1,
    )
}

internal enum class ChipTone { NEUTRAL, ACCENT, SUCCESS, WARNING, DANGER }

/** Mehrere Marken nebeneinander; am schmalen Handy brechen sie in die nächste Zeile um. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ChipRow(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    FlowRow(modifier, horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) { content() }
}

/** Hinweis-Kasten (gelb) unter einer Gruppe, z. B. bei unverschlüsseltem HTTP. */
@Composable
internal fun SettingsNotice(text: String, modifier: Modifier = Modifier) {
    Row(
        modifier
            .fillMaxWidth()
            .padding(top = 8.dp)
            .clip(AppShapes.Row)
            .background(AppColors.Rating.copy(alpha = 0.10f))
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("⚠", color = AppColors.Rating, style = MaterialTheme.typography.titleSmall)
        Spacer(Modifier.width(10.dp))
        Text(text, style = MaterialTheme.typography.bodySmall, color = AppColors.TextSecondary)
    }
}

private val GROUP_GAP = 14.dp
private val DIVIDER_INSET = 66.dp
