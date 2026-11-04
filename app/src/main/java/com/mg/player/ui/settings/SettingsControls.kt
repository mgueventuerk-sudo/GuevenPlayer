package com.mg.player.ui.settings

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Glow
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.mg.player.domain.model.ConnectionStatus
import com.mg.player.domain.model.Playlist
import com.mg.player.ui.accounts.AccountAvatar
import com.mg.player.ui.accounts.AccountStatusLine
import com.mg.player.ui.accounts.ConnectionBadge
import com.mg.player.ui.accounts.PLAIN_HTTP_WARNING
import com.mg.player.ui.accounts.detailLabel
import com.mg.player.ui.accounts.isPlainHttp
import com.mg.player.ui.common.FocusSurface
import com.mg.player.ui.common.IconActionButton
import com.mg.player.ui.common.ListSurface
import com.mg.player.ui.common.LocalSurfaceFocused
import com.mg.player.ui.common.RowColors
import com.mg.player.ui.common.touchClick
import com.mg.player.ui.theme.AnimatedIcon
import com.mg.player.ui.theme.AppColors
import com.mg.player.ui.theme.AppIcon
import com.mg.player.ui.theme.AppIcons
import com.mg.player.ui.theme.contentColorFor

/** Farbfelder zur Wahl der Akzentfarbe. */
@Composable
fun <T> SwatchRow(
    label: String,
    options: List<T>,
    selected: T,
    colorsOf: (T) -> List<Color>,
    nameOf: (T) -> String,
    onSelect: (T) -> Unit,
    description: String? = null,
    icon: AppIcon? = null,
) {
    ControlRow(label, description, icon) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            options.forEach { option ->
                Swatch(colorsOf(option), nameOf(option), option == selected) { onSelect(option) }
            }
        }
    }
}

@Composable
internal fun Swatch(colors: List<Color>, name: String, selected: Boolean, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    Surface(
        onClick = onClick,
        modifier = Modifier.touchClick(interaction, onClick),
        interactionSource = interaction,
        shape = ClickableSurfaceDefaults.shape(shape = CircleShape),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = Color.Transparent,
            focusedContainerColor = Color.Transparent,
            pressedContainerColor = Color.Transparent,
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.18f),
        border = ClickableSurfaceDefaults.border(
            border = Border(BorderStroke(2.dp, if (selected) AppColors.TextPrimary else Color.Transparent), shape = CircleShape),
            focusedBorder = Border(BorderStroke(3.dp, AppColors.TextPrimary), shape = CircleShape),
        ),
        glow = ClickableSurfaceDefaults.glow(focusedGlow = Glow(elevationColor = colors.first().copy(alpha = 0.6f), elevation = 12.dp)),
    ) {
        Box(
            Modifier
                .padding(4.dp)
                .size(30.dp)
                .clip(CircleShape)
                .background(Brush.linearGradient(colors)),
            contentAlignment = Alignment.Center,
        ) {
            if (selected) {
                AnimatedIcon(
                    AppIcons.Check,
                    tint = contentColorFor(colors.first()),
                    active = true,
                    size = 18.dp,
                    contentDescription = "$name gewählt",
                )
            }
        }
    }
}

@Composable
internal fun StepButton(text: String, onClick: () -> Unit) {
    FocusSurface(onClick = onClick, shape = CircleShape, glow = false, sheen = false, focusedContainerColor = AppColors.FocusFill) {
        Box(Modifier.size(40.dp), contentAlignment = Alignment.Center) {
            Text(text, style = MaterialTheme.typography.titleLarge)
        }
    }
}

/** Schalter im iOS-Stil; der Knopf gleitet beim Umschalten. */
@Composable
internal fun SwitchIndicator(checked: Boolean) {
    val focused = LocalSurfaceFocused.current
    val track by animateColorAsState(
        when {
            focused && checked -> AppColors.OnFocus
            focused -> AppColors.OnFocus.copy(alpha = 0.25f)
            checked -> AppColors.Accent
            else -> AppColors.ProgressTrack
        },
        label = "track",
    )
    val knobOffset by animateDpAsState(if (checked) 20.dp else 0.dp, label = "knob")
    Box(
        Modifier
            .width(48.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(track)
            .padding(3.dp),
    ) {
        Box(
            Modifier
                .offset { IntOffset(knobOffset.roundToPx(), 0) }
                .size(22.dp)
                .clip(CircleShape)
                .background(if (focused && checked) AppColors.FocusFill else Color.White),
        )
    }
}

/** Nächster Wert einer Liste (zyklisch). */
fun <T> List<T>.nextAfter(current: T): T {
    val index = indexOf(current)
    return this[(index + 1).mod(size)]
}
