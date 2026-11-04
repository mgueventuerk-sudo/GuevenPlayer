package com.mg.player.ui.player

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.mg.player.ui.common.glass
import com.mg.player.ui.common.isTouch
import com.mg.player.ui.common.trapFocus
import com.mg.player.ui.theme.AppShapes
import com.mg.player.ui.theme.AppColors
import com.mg.player.ui.theme.AppIcons
import com.mg.player.ui.theme.Dimens
import com.mg.player.ui.theme.StaticIcon

/**
 * Gemeinsamer Rahmen der Seitenpanels im Player.
 *
 * Am Fernseher schließt die Zurück-Taste, am Handy ein Tipp daneben – zusätzlich gibt es dort ein „×“ oben rechts,
 * denn ohne sichtbaren Ausweg wirkt ein Panel, das über der halben Oberfläche liegt, wie festgefahren.
 * Tipper **innerhalb** des Panels werden geschluckt, damit sie es nicht aus Versehen schließen.
 */
@Composable
internal fun PanelFrame(
    title: String,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    width: Int = PANEL_WIDTH,
    content: @Composable ColumnScope.() -> Unit,
) {
    val touch = isTouch
    val interaction = remember { MutableInteractionSource() }
    Column(
        modifier
            .trapFocus()
            .padding(16.dp)
            // Am Fernseher feste Breite wie bisher; am Handy höchstens so breit wie der Bildschirm.
            .then(if (touch) Modifier.widthIn(max = width.dp) else Modifier.width(width.dp))
            .fillMaxHeight()
            .glass(AppShapes.Panel, fill = AppColors.Panel)
            // Kein Fokus, keine Hervorhebung: fängt nur die Tipper ab, die sonst beim Player-Hintergrund landen.
            .clickable(interaction, indication = null, enabled = touch) { }
            .padding(24.dp),
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text(title, style = MaterialTheme.typography.titleLarge)
            if (touch) {
                Column(
                    Modifier
                        .size(36.dp)
                        .glass(CircleShape)
                        .clickable(onClickLabel = "Schließen", onClick = onClose),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    StaticIcon(AppIcons.Close, tint = AppColors.TextPrimary, size = 18.dp, contentDescription = "Schließen")
                }
            }
        }
        content()
    }
}

/** Breite der Seitenpanels am Fernseher; am Handy begrenzt widthIn zusätzlich auf die verfügbare Breite. */
internal const val PANEL_WIDTH = 380
