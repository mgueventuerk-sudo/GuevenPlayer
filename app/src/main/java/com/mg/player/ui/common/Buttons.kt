package com.mg.player.ui.common

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.mg.player.ui.theme.AnimatedIcon
import com.mg.player.ui.theme.AppColors
import com.mg.player.ui.theme.AppIcon

/**
 * Pill-Button im Glas-Stil (Abspielen, Fortsetzen, Favorit …). Mit dem Fokus füllt er sich hell
 * und das Icon spielt seine Animation; [primary] trägt die Akzentfarbe.
 */
@Composable
fun ActionButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: AppIcon? = null,
    primary: Boolean = false,
    compact: Boolean = false,
    iconTint: Color? = null,
    /** Zurückhaltend (Player-Reiter wie bei Apple TV): ohne Füllung, Schrift gedämpft; erst der Fokus hebt hervor. */
    quiet: Boolean = false,
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val shape = RoundedCornerShape(50)
    val container = when {
        primary -> AppColors.Accent
        quiet -> Color.Transparent
        else -> AppColors.GlassRaised
    }
    val content = when {
        primary -> AppColors.OnAccent
        quiet -> AppColors.TextSecondary
        else -> AppColors.TextPrimary
    }
    Surface(
        onClick = onClick,
        modifier = modifier.touchClick(interaction, onClick),
        interactionSource = interaction,
        shape = ClickableSurfaceDefaults.shape(shape = shape),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = container,
            contentColor = content,
            focusedContainerColor = AppColors.FocusFill,
            focusedContentColor = AppColors.OnFocus,
            pressedContainerColor = AppColors.FocusFill,
            pressedContentColor = AppColors.OnFocus,
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.06f),
        glow = ClickableSurfaceDefaults.glow(focusedGlow = focusGlow(14.dp)),
    ) {
        Row(
            // Mittig, auch wenn der Knopf breiter ist als sein Inhalt (am Handy über die ganze Breite).
            Modifier.align(Alignment.Center).padding(horizontal = if (compact) 16.dp else 24.dp, vertical = if (compact) 9.dp else 12.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (icon != null) {
                AnimatedIcon(icon, tint = if (focused) AppColors.OnFocus else iconTint ?: content, active = focused, size = if (compact) 20.dp else 22.dp)
                Spacer(Modifier.width(10.dp))
            }
            Text(text, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, maxLines = 1)
        }
    }
}

/** Auswahl-Chip (Kategorien im TV-Guide, Filter): gewählt in der Akzentfarbe, mit Fokus hell gefüllt. */
@Composable
fun ChoiceChip(text: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier, icon: AppIcon? = null, onLongClick: (() -> Unit)? = null) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val shape = RoundedCornerShape(50)
    Surface(
        onClick = onClick,
        onLongClick = onLongClick,
        modifier = modifier.touchClick(interaction, onClick, onLongClick),
        interactionSource = interaction,
        shape = ClickableSurfaceDefaults.shape(shape = shape),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = if (selected) AppColors.Accent else AppColors.Glass,
            contentColor = if (selected) AppColors.OnAccent else AppColors.TextSecondary,
            focusedContainerColor = AppColors.FocusFill,
            focusedContentColor = AppColors.OnFocus,
            pressedContainerColor = AppColors.FocusFill,
            pressedContentColor = AppColors.OnFocus,
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.06f),
        glow = ClickableSurfaceDefaults.glow(focusedGlow = focusGlow(10.dp)),
    ) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
            if (icon != null) {
                AnimatedIcon(
                    icon,
                    tint = if (focused) {
                        AppColors.OnFocus
                    } else if (selected) {
                        AppColors.OnAccent
                    } else {
                        AppColors.TextSecondary
                    },
                    active = focused,
                    size = 18.dp,
                )
                Spacer(Modifier.width(7.dp))
            }
            Text(
                text,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                maxLines = 1,
            )
        }
    }
}

/**
 * Runder Glas-Knopf nur mit Icon; mit dem Fokus fährt die Beschriftung heraus (wie die Tabs oben).
 * Für Nebenaktionen auf Detailseiten – spart Platz und bleibt trotzdem verständlich.
 */
@Composable
fun IconActionButton(icon: AppIcon, label: String, onClick: () -> Unit, modifier: Modifier = Modifier, iconTint: Color? = null) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val shape = RoundedCornerShape(50)
    Surface(
        onClick = onClick,
        modifier = modifier.touchClick(interaction, onClick),
        interactionSource = interaction,
        shape = ClickableSurfaceDefaults.shape(shape = shape),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = AppColors.GlassRaised,
            contentColor = AppColors.TextPrimary,
            focusedContainerColor = AppColors.FocusFill,
            focusedContentColor = AppColors.OnFocus,
            pressedContainerColor = AppColors.FocusFill,
            pressedContentColor = AppColors.OnFocus,
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.06f),
        glow = ClickableSurfaceDefaults.glow(focusedGlow = focusGlow(14.dp)),
    ) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            AnimatedIcon(
                icon,
                tint = if (focused) AppColors.OnFocus else iconTint ?: AppColors.TextPrimary,
                active = focused,
                size = 22.dp,
                contentDescription = label,
            )
            AnimatedVisibility(visible = focused, enter = fadeIn() + expandHorizontally(), exit = fadeOut() + shrinkHorizontally()) {
                Row {
                    Spacer(Modifier.width(8.dp))
                    Text(
                        label,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        modifier = Modifier.padding(end = 6.dp),
                    )
                }
            }
        }
    }
}
