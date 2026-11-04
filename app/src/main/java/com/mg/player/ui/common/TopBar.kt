package com.mg.player.ui.common

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.mg.player.core.util.TimeUtils
import com.mg.player.ui.theme.AnimatedIcon
import com.mg.player.ui.theme.AppColors
import com.mg.player.ui.theme.AppIcon
import com.mg.player.ui.theme.BrandMark
import com.mg.player.ui.theme.BrandWordmark
import com.mg.player.ui.theme.Dimens
import com.mg.player.ui.vpn.LocalVpnState
import com.mg.player.ui.vpn.VpnBadge
import kotlinx.coroutines.delay

/** Links Logo, rechts VPN-Schild, Uhr und Zugang – zusammen schmal genug für 960 dp breite Fernseher. */
private val START_WIDTH = 200.dp
private val END_WIDTH = 240.dp

data class TopBarItem(val id: String, val label: String, val icon: AppIcon)

/**
 * Navigationsleiste oben im tvOS-Stil: Logo links, in der Mitte eine Glas-Kapsel mit den Bereichen, rechts Uhr und
 * Zugang. Nur der gewählte und der fokussierte Bereich zeigen ihre Beschriftung – die Leiste bleibt schmal.
 * Ein Bereich wird erst nach kurzem Verweilen geöffnet ([onSelect]), damit schnelles Durchlaufen nicht jeden Bereich
 * lädt; OK ([onOpen]) öffnet sofort und springt in den Inhalt.
 */
@Composable
fun TopBar(
    items: List<TopBarItem>,
    selectedId: String,
    onSelect: (TopBarItem) -> Unit,
    onOpen: (TopBarItem) -> Unit,
    modifier: Modifier = Modifier,
    selectedRequester: FocusRequester = remember { FocusRequester() },
    /** „Runter“ aus den Tabs: true = selbst behandelt (z. B. Fokus auf die Gruppen), sonst Standard-Navigation. */
    onDown: () -> Boolean = { false },
    trailing: @Composable () -> Unit = {},
) {
    Row(
        modifier
            .fillMaxWidth()
            .height(Dimens.TopBarHeight)
            .padding(horizontal = Dimens.ScreenPadding),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(Modifier.width(START_WIDTH), verticalAlignment = Alignment.CenterVertically) {
            BrandMark(size = 32.dp)
            Spacer(Modifier.width(10.dp))
            BrandWordmark(fontSize = 18.sp)
        }
        Spacer(Modifier.weight(1f))
        // Nur wer mit links/rechts durch die Leiste wandert, wechselt per Verweilen den Bereich. Jede andere Taste
        // (hoch/runter, OK, Zurück) beendet das Blättern; kommt der Fokus ohne Blättern auf einen Tab (z. B. weil der
        // Inhalt keine fokussierbaren Elemente mehr hat), bleibt der Bereich, wie er ist.
        var browsing by remember { mutableStateOf(false) }
        Row(
            Modifier
                .glass(RoundedCornerShape(50))
                .padding(5.dp)
                .selectableGroup()
                .onPreviewKeyEvent { event ->
                    if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                    browsing = event.key == Key.DirectionLeft || event.key == Key.DirectionRight
                    event.key == Key.DirectionDown && onDown()
                }
                .focusRestorer(selectedRequester),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            items.forEach { item ->
                val selected = item.id == selectedId
                TopBarTab(
                    item = item,
                    selected = selected,
                    onSelect = onSelect,
                    // „Hoch“ aus dem Inhalt landet auf dem gewählten Bereich – nicht auf dem Tab, der zufällig darüber
                    // liegt (z. B. aus den Film-Gruppen auf „Live TV“). Nur beim Blättern mit links/rechts bleibt er.
                    onEnteredFromContent = { if (!browsing) runCatching { selectedRequester.requestFocus() } },
                    onOpen = onOpen,
                    modifier = if (selected) Modifier.focusRequester(selectedRequester) else Modifier,
                )
            }
        }
        Spacer(Modifier.weight(1f))
        Row(Modifier.width(END_WIDTH), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
            VpnBadge(LocalVpnState.current, short = true)
            Spacer(Modifier.width(10.dp))
            Clock()
            Spacer(Modifier.width(14.dp))
            trailing()
        }
    }
}

@Composable
private fun TopBarTab(
    item: TopBarItem,
    selected: Boolean,
    onSelect: (TopBarItem) -> Unit,
    onOpen: (TopBarItem) -> Unit,
    modifier: Modifier = Modifier,
    onEnteredFromContent: () -> Unit = {},
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    // Verweilt der Fokus kurz auf einem Bereich, wird er geöffnet (wie bei tvOS).
    LaunchedEffect(focused) {
        if (focused && !selected) {
            onEnteredFromContent()
            delay(280)
            onSelect(item)
        }
    }
    val shape = RoundedCornerShape(50)
    Surface(
        onClick = { onOpen(item) },
        modifier = modifier,
        interactionSource = interaction,
        shape = ClickableSurfaceDefaults.shape(shape = shape),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = if (selected) AppColors.AccentSoft else Color.Transparent,
            contentColor = if (selected) AppColors.TextPrimary else AppColors.TextSecondary,
            focusedContainerColor = AppColors.FocusFill,
            focusedContentColor = AppColors.OnFocus,
            pressedContainerColor = AppColors.FocusFill,
            pressedContentColor = AppColors.OnFocus,
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.04f),
        glow = ClickableSurfaceDefaults.glow(focusedGlow = focusGlow(10.dp)),
    ) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            AnimatedIcon(
                item.icon,
                tint = when {
                    focused -> AppColors.OnFocus
                    selected -> AppColors.AccentText
                    else -> AppColors.TextSecondary
                },
                active = focused,
                size = 22.dp,
                contentDescription = item.label,
            )
            AnimatedVisibility(
                visible = focused || selected,
                enter = fadeIn() + expandHorizontally(),
                exit = fadeOut() + shrinkHorizontally(),
            ) {
                Row {
                    Spacer(Modifier.width(8.dp))
                    Text(item.label, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, maxLines = 1)
                }
            }
        }
    }
}

/** Uhrzeit oben rechts – aktualisiert sich zur vollen Minute. */
@Composable
private fun Clock() {
    val now by produceState(System.currentTimeMillis()) {
        while (true) {
            value = System.currentTimeMillis()
            delay(60_000 - value % 60_000)
        }
    }
    Text(TimeUtils.formatClock(now), style = MaterialTheme.typography.titleMedium, color = AppColors.TextPrimary)
}
