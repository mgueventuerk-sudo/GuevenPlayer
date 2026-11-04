package com.mg.player.ui.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.mg.player.domain.model.Category
import com.mg.player.domain.model.ContentType
import com.mg.player.ui.common.ChoiceChip
import com.mg.player.ui.common.ScreenTitle
import com.mg.player.ui.common.appViewModel
import com.mg.player.ui.common.glass
import com.mg.player.ui.common.isTouch
import com.mg.player.ui.phone.PhoneIconButton
import com.mg.player.ui.theme.AppShapes
import com.mg.player.ui.theme.AppColors
import com.mg.player.ui.theme.AppIcons
import com.mg.player.ui.theme.Dimens

/** Kategorien ausblenden, sperren, anheften (erscheinen ganz oben) und in eine eigene Reihenfolge bringen. */
@Composable
fun ManageCategoriesScreen() {
    val vm = appViewModel { ManageCategoriesViewModel(it) }
    val type by vm.type.collectAsStateWithLifecycle()
    val categories by vm.categories.collectAsStateWithLifecycle()
    val hasPin by vm.hasPin.collectAsStateWithLifecycle()
    val moving by vm.moving.collectAsStateWithLifecycle()
    // Zurück beendet zuerst das Verschieben, erst dann den Bildschirm.
    BackHandler(enabled = moving != null) { vm.stopMoving() }

    val touch = isTouch
    Column(Modifier.fillMaxSize().padding(start = if (touch) 16.dp else 64.dp, top = if (touch) 12.dp else 40.dp, end = if (touch) 16.dp else 64.dp)) {
        ScreenTitle("Kategorien verwalten")
        Text(
            buildString {
                append(if (touch) "Mit den Pfeilen verschieben. " else "„Verschieben“: OK, dann hoch/runter, OK zum Ablegen. ")
                append("Favoriten stehen oben, ausgeblendete verschwinden aus allen Listen")
                append(if (hasPin) ", gesperrte brauchen die PIN." else ". Zum Sperren zuerst in den Einstellungen eine PIN festlegen.")
            },
            style = MaterialTheme.typography.bodyMedium,
            color = AppColors.TextSecondary,
            modifier = Modifier.padding(top = 4.dp, bottom = 12.dp),
        )
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            listOf(ContentType.LIVE to "Live TV", ContentType.MOVIE to "Filme", ContentType.SERIES to "Serien").forEach { (t, label) ->
                ChoiceChip(
                    label,
                    selected = t == type,
                    onClick = { vm.selectType(t) },
                    icon = when (t) {
                        ContentType.LIVE -> AppIcons.LiveTv
                        ContentType.MOVIE -> AppIcons.Movie
                        ContentType.SERIES -> AppIcons.Series
                    },
                )
            }
            ChoiceChip("Standard-Reihenfolge", selected = false, onClick = vm::resetOrder, icon = AppIcons.Restart)
        }
        Spacer(Modifier.height(12.dp))
        val listState = rememberLazyListState()
        KeepMovingVisible(listState, categories, moving)
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize().focusRestorer(FocusRequester.Default),
            contentPadding = PaddingValues(bottom = 40.dp),
        ) {
            items(categories, key = { it.id }, contentType = { "manage-category" }) { category ->
                ManageRow(
                    category = category,
                    hasPin = hasPin,
                    stacked = touch,
                    moving = moving == category.id,
                    onUpdate = vm::update,
                    onToggleMove = { vm.toggleMoving(category.id) },
                    onMove = { delta -> vm.move(category.id, delta) },
                )
            }
        }
    }
}

/** Die verschobene Kategorie bleibt im Bild (sonst verschwände sie samt Fokus aus der Liste). */
@Composable
private fun KeepMovingVisible(listState: LazyListState, categories: List<Category>, moving: String?) {
    LaunchedEffect(categories, moving) {
        val index = categories.indexOfFirst { it.id == moving }.takeIf { it >= 0 } ?: return@LaunchedEffect
        val visible = listState.layoutInfo.visibleItemsInfo
        val first = visible.firstOrNull()?.index ?: return@LaunchedEffect
        val last = visible.last().index
        when {
            index <= first -> listState.scrollToItem((index - 1).coerceAtLeast(0))
            index >= last -> listState.scrollToItem((index - visible.size + 3).coerceAtLeast(0))
        }
    }
}

/** Eine Kategorie mit ihren Schaltern; am Handy stehen die Schalter unter dem Namen, Pfeile rechts daneben. */
@Composable
private fun ManageRow(
    category: Category,
    hasPin: Boolean,
    stacked: Boolean,
    moving: Boolean,
    onUpdate: (Category) -> Unit,
    onToggleMove: () -> Unit,
    onMove: (Int) -> Boolean,
) {
    val name: @Composable (Modifier) -> Unit = { modifier ->
        Text(
            category.name,
            modifier = modifier,
            style = MaterialTheme.typography.titleSmall,
            color = if (category.hidden) AppColors.TextMuted else AppColors.TextPrimary,
            maxLines = if (stacked) 2 else 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
    val chips: @Composable () -> Unit = {
        if (!stacked) {
            ChoiceChip(
                if (moving) "Ablegen" else "Verschieben",
                selected = moving,
                icon = AppIcons.Reorder,
                onClick = onToggleMove,
                modifier = Modifier.onPreviewKeyEvent { event ->
                    if (!moving || event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                    when (event.key) {
                        Key.DirectionUp -> onMove(-1).let { true }

                        Key.DirectionDown -> onMove(1).let { true }

                        // Beim Verschieben nicht seitlich aus der Zeile springen.
                        Key.DirectionLeft, Key.DirectionRight -> true

                        else -> false
                    }
                },
            )
            Spacer(Modifier.width(8.dp))
        }
        ChoiceChip("Angeheftet", category.pinned, icon = AppIcons.Star, onClick = { onUpdate(category.copy(pinned = !category.pinned)) })
        Spacer(Modifier.width(8.dp))
        if (hasPin || category.locked) {
            ChoiceChip("Sperren", category.locked, icon = AppIcons.Lock, onClick = { onUpdate(category.copy(locked = !category.locked)) })
            Spacer(Modifier.width(8.dp))
        }
        ChoiceChip("Ausblenden", category.hidden, icon = AppIcons.EyeOff, onClick = { onUpdate(category.copy(hidden = !category.hidden)) })
    }
    val frame = Modifier
        .fillMaxWidth()
        .padding(vertical = 3.dp)
        .glass(AppShapes.Row, fill = if (moving) AppColors.AccentSoft else null)
    if (stacked) {
        Column(frame.padding(horizontal = 14.dp, vertical = 10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                name(Modifier.weight(1f))
                PhoneIconButton(AppIcons.ChevronUp, "Nach oben", onClick = { onMove(-1) })
                Spacer(Modifier.width(8.dp))
                PhoneIconButton(AppIcons.ChevronDown, "Nach unten", onClick = { onMove(1) })
            }
            Row(Modifier.padding(top = 8.dp).horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically) { chips() }
        }
    } else {
        Row(frame.padding(horizontal = 16.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            name(Modifier.weight(1f))
            chips()
        }
    }
}
