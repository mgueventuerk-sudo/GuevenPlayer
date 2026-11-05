package com.mg.player.ui.common

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.Layout
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
import com.mg.player.ui.theme.AppMotion
import com.mg.player.ui.theme.Spacing
import com.mg.player.ui.vpn.LocalVpnState
import com.mg.player.ui.vpn.VpnBadge
import kotlinx.coroutines.delay

/** Mindestabstand zwischen Logo, Bereichen und rechter Ecke. */
private val SLOT_GAP = 16.dp

/** Deckkraft der nicht gewählten Bereiche, solange die Leiste eingezogen ist (Fokus im Inhalt). */
private const val COLLAPSED_ALPHA = 0.45f

data class TopBarItem(val id: String, val label: String, val icon: AppIcon)

/**
 * Navigationsleiste oben im tvOS-Stil: Logo links, in der Mitte eine Glas-Kapsel mit den Bereichen, rechts Uhr und
 * Zugang. Nur der gewählte und der fokussierte Bereich zeigen ihre Beschriftung – die Leiste bleibt schmal.
 * Ein Bereich wird erst nach kurzem Verweilen geöffnet ([onSelect]), damit schnelles Durchlaufen nicht jeden Bereich
 * lädt; OK ([onOpen]) öffnet sofort und springt in den Inhalt.
 *
 * Ab 3.41 im „Liquid Glass“-Stil: Liegt der Fokus im Inhalt ([collapsed]), tritt die Leiste zurück – Schriftzug aus,
 * die anderen Bereiche gedimmt, nur der gewählte bleibt hell; „Hoch“ holt sie mit einer Feder wieder nach vorn.
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
    collapsed: Boolean = false,
    trailing: @Composable () -> Unit = {},
) {
    TopBarLayout(
        modifier
            .fillMaxWidth()
            .height(Dimens.TopBarHeight)
            .padding(horizontal = Dimens.ScreenPadding),
        start = { TopBarBrand(collapsed) },
        center = { TopBarTabs(items, selectedId, onSelect, onOpen, selectedRequester, onDown, collapsed) },
        end = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                VpnBadge(LocalVpnState.current, short = true)
                Spacer(Modifier.width(10.dp))
                Clock()
                Spacer(Modifier.width(14.dp))
                trailing()
            }
        },
    )
}

/**
 * Drei Bereiche ohne feste Breiten (ab 3.51): Die rechte Ecke (VPN, Uhr, Zugang) bekommt immer, was sie braucht – auch
 * bei großer Schrift oder Anzeigegröße am Fernseher. Die Bereiche stehen in der Mitte, solange links und rechts Platz
 * ist, sonst rücken sie zwischen Logo und rechte Ecke. Das Logo bekommt den Rest (zuerst fällt der Schriftzug weg).
 * Vorher hatte die rechte Ecke feste 240 dp: Die Uhr brach um („21:3 / 0“), das Zugangs-Symbol verschwand.
 */
@Composable
private fun TopBarLayout(
    modifier: Modifier,
    start: @Composable () -> Unit,
    center: @Composable () -> Unit,
    end: @Composable () -> Unit,
) {
    Layout(contents = listOf(start, center, end), modifier = modifier) { (startSlot, centerSlot, endSlot), constraints ->
        val width = constraints.maxWidth
        val height = constraints.maxHeight
        val gap = SLOT_GAP.roundToPx()
        val loose = constraints.copy(minWidth = 0, minHeight = 0)
        val endPlaced = endSlot.map { it.measure(loose) }
        val endWidth = endPlaced.maxOfOrNull { it.width } ?: 0
        val centerMax = (width - endWidth - 2 * gap - BRAND_MIN.roundToPx()).coerceAtLeast(0)
        val centerPlaced = centerSlot.map { it.measure(loose.copy(maxWidth = centerMax)) }
        val centerWidth = centerPlaced.maxOfOrNull { it.width } ?: 0
        val startMax = (width - endWidth - centerWidth - 2 * gap).coerceAtLeast(0)
        val startPlaced = startSlot.map { it.measure(loose.copy(maxWidth = startMax)) }
        val startWidth = startPlaced.maxOfOrNull { it.width } ?: 0
        layout(width, height) {
            startPlaced.forEach { it.place(0, (height - it.height) / 2) }
            endPlaced.forEach { it.place(width - it.width, (height - it.height) / 2) }
            // Mittig, aber nie über Logo oder rechte Ecke.
            val left = startWidth + gap
            val right = width - endWidth - gap - centerWidth
            val x = ((width - centerWidth) / 2).coerceAtMost(right).coerceAtLeast(left)
            centerPlaced.forEach { it.place(x, (height - it.height) / 2) }
        }
    }
}

/** Platz, den das Logo mindestens behält (Zeichen ohne Schriftzug). */
private val BRAND_MIN = 40.dp

@Composable
private fun TopBarBrand(collapsed: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        BrandMark(size = 32.dp)
        AnimatedVisibility(
            visible = !collapsed,
            enter = fadeIn(AppMotion.surface()) + expandHorizontally(AppMotion.surface()),
            exit = fadeOut(AppMotion.surface()) + shrinkHorizontally(AppMotion.surface()),
        ) {
            Row {
                Spacer(Modifier.width(Spacing.Xs))
                BrandWordmark(fontSize = 18.sp)
            }
        }
    }
}

@Composable
private fun TopBarTabs(
    items: List<TopBarItem>,
    selectedId: String,
    onSelect: (TopBarItem) -> Unit,
    onOpen: (TopBarItem) -> Unit,
    selectedRequester: FocusRequester,
    onDown: () -> Boolean,
    collapsed: Boolean,
) {
    // Nur wer mit links/rechts durch die Leiste wandert, wechselt per Verweilen den Bereich. Jede andere Taste
    // (hoch/runter, OK, Zurück) beendet das Blättern; kommt der Fokus ohne Blättern auf einen Tab (z. B. weil der
    // Inhalt keine fokussierbaren Elemente mehr hat), bleibt der Bereich, wie er ist.
    var browsing by remember { mutableStateOf(false) }
    Row(
        Modifier
            .liquidGlass(RoundedCornerShape(50))
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
            // Eingezogen treten die anderen Bereiche zurück (gedimmt), der gewählte bleibt hell – er ist das Ziel von
            // „Hoch“ aus dem Inhalt. Alle Bereiche bleiben erreichbar (Bedienungshilfen, Tests).
            val dim by animateFloatAsState(if (collapsed && !selected) COLLAPSED_ALPHA else 1f, AppMotion.surface(), label = "tabDim")
            TopBarTab(
                item = item,
                selected = selected,
                onSelect = onSelect,
                // „Hoch“ aus dem Inhalt landet auf dem gewählten Bereich – nicht auf dem Tab, der zufällig darüber
                // liegt (z. B. aus den Film-Gruppen auf „Live TV“). Nur beim Blättern mit links/rechts bleibt er.
                onEnteredFromContent = { if (!browsing) runCatching { selectedRequester.requestFocus() } },
                onOpen = onOpen,
                modifier = Modifier
                    .graphicsLayer { alpha = dim }
                    .then(if (selected) Modifier.focusRequester(selectedRequester) else Modifier),
            )
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
    // Nie umbrechen („21:3 / 0“), auch bei großer Schrift.
    Text(TimeUtils.formatClock(now), style = MaterialTheme.typography.titleMedium, color = AppColors.TextPrimary, maxLines = 1, softWrap = false)
}
