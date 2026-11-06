package com.mg.player.ui.phone

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.mg.player.core.util.CategoryName
import com.mg.player.domain.model.Category
import com.mg.player.ui.common.ChoiceChip
import com.mg.player.ui.common.LocalAppContainer
import com.mg.player.ui.common.LocalParentalGate
import com.mg.player.ui.common.LocalToast
import com.mg.player.ui.common.TopBarItem
import com.mg.player.ui.common.glass
import com.mg.player.ui.theme.AnimatedIcon
import com.mg.player.ui.theme.AppColors
import com.mg.player.ui.theme.AppIcon
import com.mg.player.ui.theme.BrandMark
import com.mg.player.ui.theme.BrandWordmark
import kotlinx.coroutines.launch

/** Kopfzeile am Handy: Logo links, rechts Schnellzugriffe und der Zugang. */
@Composable
fun PhoneTopBar(modifier: Modifier = Modifier, actions: @Composable () -> Unit) {
    // Die Knöpfe rechts haben Vorrang: Passt der Schriftzug daneben nicht mehr (schmale Handys, zusätzlicher
    // Sport-Knopf), bleibt nur das Logo stehen – statt den Avatar zusammenzuquetschen.
    Layout(
        content = {
            BrandMark(size = 28.dp)
            BrandWordmark(fontSize = 18.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) { actions() }
        },
        modifier = modifier
            .fillMaxWidth()
            .height(56.dp)
            .padding(horizontal = 16.dp),
    ) { measurables, constraints ->
        val loose = constraints.copy(minWidth = 0, minHeight = 0)
        val gap = 8.dp.roundToPx()
        val mark = measurables[0].measure(loose)
        val buttons = measurables[2].measure(loose)
        val room = constraints.maxWidth - mark.width - gap - buttons.width - gap
        val word = measurables[1].measure(loose.copy(maxWidth = Constraints.Infinity)).takeIf { it.width <= room }
        val height = constraints.maxHeight
        layout(constraints.maxWidth, height) {
            mark.placeRelative(0, (height - mark.height) / 2)
            word?.placeRelative(mark.width + gap, (height - word.height) / 2)
            buttons.placeRelative(constraints.maxWidth - buttons.width, (height - buttons.height) / 2)
        }
    }
}

/** Runder Glas-Knopf mit Icon (Kopfzeile, Zurück auf Detailseiten). */
@Composable
fun PhoneIconButton(icon: AppIcon, label: String, onClick: () -> Unit, modifier: Modifier = Modifier, active: Boolean = false) {
    val interaction = remember { MutableInteractionSource() }
    Box(
        modifier
            .size(42.dp)
            .glass(CircleShape, raised = true, fill = if (active) AppColors.AccentSoft else null)
            .clickable(interactionSource = interaction, indication = null, role = Role.Button, onClick = onClick)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        AnimatedIcon(icon, tint = if (active) AppColors.AccentText else AppColors.TextPrimary, active = active, size = 22.dp)
    }
}

/**
 * Untere Navigationsleiste: Glas-Kapsel mit fünf Bereichen. Der gewählte Bereich leuchtet in der
 * Akzentfarbe, sein Icon spielt beim Wechsel seine Animation.
 */
@Composable
fun PhoneBottomBar(items: List<TopBarItem>, selectedId: String, onSelect: (TopBarItem) -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp)
            .glass(RoundedCornerShape(28.dp), raised = true)
            .padding(horizontal = 6.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        items.forEach { item ->
            val selected = item.id == selectedId
            val background by animateColorAsState(if (selected) AppColors.AccentSoft else Color.Transparent, label = "tab")
            val interaction = remember { MutableInteractionSource() }
            Column(
                Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(22.dp))
                    .background(background)
                    .clickable(interactionSource = interaction, indication = null, role = Role.Tab) { onSelect(item) }
                    .semantics { this.selected = selected }
                    .padding(vertical = 7.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                AnimatedIcon(
                    item.icon,
                    tint = if (selected) AppColors.AccentText else AppColors.TextSecondary,
                    active = selected,
                    size = 24.dp,
                    contentDescription = item.label,
                )
                Spacer(Modifier.height(3.dp))
                Text(
                    item.label,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                    color = if (selected) AppColors.AccentText else AppColors.TextSecondary,
                    maxLines = 1,
                )
            }
        }
    }
}

/**
 * Kategorien als waagerecht wischbare Chips (statt der Spalte am Fernseher). Antippen wählt,
 * gedrückt halten heftet die Gruppe oben an. Gesperrte Gruppen fragen die PIN ab.
 */
@Composable
fun CategoryChipRow(categories: List<Category>, selectedId: String, onSelect: (Category) -> Unit, modifier: Modifier = Modifier) {
    val gate = LocalParentalGate.current
    val container = LocalAppContainer.current
    val toast = LocalToast.current
    val scope = rememberCoroutineScope()
    LazyRow(
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(categories, key = { it.id }, contentType = { "chip" }) { category ->
            ChoiceChip(
                text = (if (category.pinned) "★ " else "") + (if (category.locked) "🔒 " else "") + CategoryName.display(category.name),
                selected = category.id == selectedId,
                onClick = { gate.runIfAllowed(category) { onSelect(category) } },
                onLongClick = if (category.isVirtual) {
                    null
                } else {
                    {
                        scope.launch {
                            val playlistId = container.settingsRepository.current().activePlaylistId ?: return@launch
                            val favorite = !category.pinned
                            container.categoryRepository.save(playlistId, category.copy(pinned = favorite))
                            toast.show(
                                if (favorite) "★ Gruppe „${category.name}“ oben angeheftet" else "Gruppe „${category.name}“ nicht mehr angeheftet",
                            )
                        }
                    }
                },
            )
        }
    }
}
