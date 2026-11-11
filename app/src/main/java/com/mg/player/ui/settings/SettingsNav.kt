package com.mg.player.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.mg.player.ui.common.ListSurface
import com.mg.player.ui.common.LocalSurfaceFocused
import com.mg.player.ui.common.RowColors
import com.mg.player.ui.phone.PhoneIconButton
import com.mg.player.ui.theme.AnimatedIcon
import com.mg.player.ui.theme.AppColors
import com.mg.player.ui.theme.AppIcons

/** Eintrag der Bereichsliste (TV): Symbol, Name und Zustand; ausgewählt in der Akzentfarbe, mit Fokus hell. */
@Composable
internal fun SettingsNavItem(
    page: SettingsPage,
    status: String,
    selected: Boolean,
    onFocused: () -> Unit,
    onClick: () -> Unit,
    onMoveRight: () -> Unit,
) {
    ListSurface(
        onClick = onClick,
        selected = selected,
        modifier = Modifier
            .fillMaxWidth()
            .onFocusChanged { if (it.isFocused) onFocused() }
            .onPreviewKeyEvent { event ->
                if (event.type == KeyEventType.KeyDown && event.key == Key.DirectionRight) {
                    onMoveRight()
                    true
                } else {
                    false
                }
            },
    ) {
        Row(Modifier.padding(horizontal = 10.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            NavIcon(page, selected)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    page.label,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = if (selected) FontWeight.Bold else FontWeight.SemiBold,
                    color = RowColors.primary,
                    maxLines = 1,
                )
                Text(status, style = MaterialTheme.typography.labelMedium, color = RowColors.muted, maxLines = 1)
            }
        }
    }
}

/** Symbol eines Bereichs im Kästchen: ausgewählt bzw. im Fokus gefüllt in der Akzentfarbe. */
@Composable
private fun NavIcon(page: SettingsPage, selected: Boolean) {
    val focused = LocalSurfaceFocused.current
    val filled = selected || focused
    Box(
        Modifier
            .size(34.dp)
            .clip(com.mg.player.ui.theme.AppShapes.Small)
            .background(if (filled) AppColors.Accent else AppColors.TextPrimary.copy(alpha = 0.07f)),
        contentAlignment = Alignment.Center,
    ) {
        AnimatedIcon(page.icon, tint = if (filled) Color.White else AppColors.TextSecondary, active = focused, size = 19.dp)
    }
}

/** Handy: die Bereiche als gruppierte Liste mit Zustand; ein Tipp öffnet den Bereich. */
@Composable
internal fun PhoneSettingsMenu(c: SettingsContext, vpnLine: String, onOpen: (SettingsPage) -> Unit) {
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 12.dp, end = 12.dp, bottom = 48.dp)) {
        settingsGroup("menu") {
            SettingsPage.entries.forEach { page ->
                row("menu-${page.name}") {
                    // Abwechselnd Haupt-/Partnerfarbe statt für alle Bereiche derselbe Kachelfarbe - sonst sind
                    // "Sicherheit" und "Darstellung" auf den ersten Blick nicht zu unterscheiden.
                    ActionRow(page.label, { onOpen(page) }, page.status(c, vpnLine), icon = page.icon, altAccent = page.ordinal % 2 == 1)
                }
            }
        }
        aboutItems()
    }
}

/** Kopfzeile einer Seite am Handy: Zurück-Knopf und wohin er führt. */
@Composable
internal fun PhonePageBar(title: String, onBack: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(start = 4.dp, end = 16.dp, top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        PhoneIconButton(AppIcons.Back, "Zurück", onClick = onBack)
        Spacer(Modifier.width(8.dp))
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, color = AppColors.TextSecondary, maxLines = 1)
    }
}
