package com.mg.player.ui.common

import androidx.activity.compose.ReportDrawnWhen
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.mg.player.domain.model.Category
import com.mg.player.ui.theme.AppColors
import com.mg.player.ui.theme.AppIcons
import com.mg.player.ui.theme.StaticIcon
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Fernseher: Ziel für „Runter“ aus der oberen Leiste. Live-TV, Filme und Serien landen so zuerst in den Gruppen statt
 * – je nach Position des Tabs – mitten in den Sendern bzw. Postern.
 */
val LocalGroupFocus = staticCompositionLocalOf<FocusRequester?> { null }

/** Direkter Fokus auf „Alle“ beim App-Start bzw. beim Öffnen eines Bereichs. */
val LocalAllCategoryFocus = staticCompositionLocalOf<FocusRequester?> { null }

/**
 * Kategorie-Spalte. Die Auswahl folgt dem Fokus (mit Debounce im ViewModel),
 * OK springt direkt in den Inhalt.
 */
@Composable
fun CategoryColumn(
    categories: List<Category>,
    selectedId: String,
    onFocused: (Category) -> Unit,
    onClick: (Category) -> Unit,
    modifier: Modifier = Modifier,
    width: Dp = 260.dp,
) {
    val listState = rememberLazyListState()
    val gate = LocalParentalGate.current
    val container = LocalAppContainer.current
    val toast = LocalToast.current
    val contextMenu = LocalContextMenu.current
    val scope = rememberCoroutineScope()
    val allCategoryFocus = LocalAllCategoryFocus.current
    // Die gewählte Gruppe (beim Start „Alle …“) muss sichtbar sein, sobald die Gruppen geladen sind – sonst ist ihr
    // FocusRequester nicht angehängt und der Fokus fällt auf irgendeine gerade sichtbare Gruppe. Am Fire TV stellte
    // Android nach einem Neustart die alte Scrollposition wieder her, während die Gruppen noch luden (die Auswahl im
    // ViewModel stand aber wieder auf „Alle“): Fokus landete „irgendwo“, erst nach einem Tab-Wechsel stimmte er.
    val hasItems = categories.isNotEmpty()
    val selectedIndex = categories.indexOfFirst { it.id == selectedId }
    // TTFD: Erst mit geladenen Kategorien ist der Bereich bedienbar (Macrobenchmark „timeToFullDisplay“).
    ReportDrawnWhen { hasItems }
    LaunchedEffect(hasItems, allCategoryFocus) {
        if (!hasItems) return@LaunchedEffect
        snapshotFlow { listState.layoutInfo.totalItemsCount }.first { it > 0 }
        val target = selectedIndex.coerceAtLeast(0)
        if (listState.layoutInfo.visibleItemsInfo.none { it.index == target }) listState.scrollToItem(target)
    }
    // OK gedrückt halten: Gruppe oben anheften. Das macht ihre Inhalte nicht zu Favoriten.
    val toggleFavoriteGroup: (Category) -> Unit = { category ->
        scope.launch {
            val playlistId = container.settingsRepository.current().activePlaylistId ?: return@launch
            val favorite = !category.pinned
            container.categoryRepository.save(playlistId, category.copy(pinned = favorite))
            toast.show(
                if (favorite) "★ Gruppe „${category.name}“ oben angeheftet" else "Gruppe „${category.name}“ nicht mehr angeheftet",
            )
        }
    }
    // Beim Hineinspringen landet der Fokus auf der gewählten Kategorie (nicht stur auf der ersten), damit
    // die Auswahl nicht ungewollt auf "Weiterschauen" o. Ä. springt.
    val selectedRequester = remember { FocusRequester() }
    val groupFocus = LocalGroupFocus.current
    LazyColumn(
        state = listState,
        modifier = modifier
            .width(width)
            .fillMaxHeight()
            .glass()
            .then(if (groupFocus != null) Modifier.focusRequester(groupFocus) else Modifier)
            .focusRestorer(if (categories.any { it.id == selectedId }) selectedRequester else FocusRequester.Default),
        contentPadding = PaddingValues(vertical = 10.dp, horizontal = 8.dp),
    ) {
        items(categories, key = { it.id }, contentType = { "category" }) { category ->
            val selected = category.id == selectedId
            ListSurface(
                // Gesperrte Kategorien öffnen sich erst nach PIN-Eingabe.
                onClick = { gate.runIfAllowed(category) { onClick(category) } },
                // Einheitliches Kontextmenü statt Sofort-Aktion: Öffnen und Anheften an einer Stelle.
                onLongClick = if (category.isVirtual) {
                    null
                } else {
                    {
                        contextMenu.show(
                            title = category.name,
                            actions = listOf(
                                ContextAction("Öffnen", AppIcons.ChevronRight) { gate.runIfAllowed(category) { onClick(category) } },
                                ContextAction(
                                    if (category.pinned) "Nicht mehr oben anheften" else "Oben anheften",
                                    AppIcons.Star,
                                ) { toggleFavoriteGroup(category) },
                            ),
                        )
                    }
                },
                selected = selected,
                modifier = Modifier
                    .padding(vertical = 2.dp)
                    .fillMaxWidth()
                    .then(if (category.id == Category.ALL && allCategoryFocus != null) Modifier.focusRequester(allCategoryFocus) else Modifier)
                    .then(if (selected) Modifier.focusRequester(selectedRequester) else Modifier)
                    .onFocusChanged { if (it.isFocused) onFocused(category) },
            ) {
                Row(Modifier.padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    // Farbpunkt nach Inhaltsart (Sport, Nachrichten, Filme …); virtuelle Gruppen („Alle“, „Favoriten“) ohne.
                    val kind = remember(category.name) { if (category.isVirtual) null else CategoryAccent.of(category.name) }
                    if (kind != null) {
                        Box(Modifier.size(7.dp).background(kind.color, CircleShape))
                        Spacer(Modifier.width(10.dp))
                    }
                    MarqueeText(
                        text = category.name,
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                        color = if (selected) RowColors.primary else RowColors.secondary,
                        // Zwei Zeilen: Anbieter-Gruppen wie „|DE| ✪ NEUE FILME 2025“ sind ohne Fokus lesbar.
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (category.pinned) {
                        StaticIcon(AppIcons.Star, tint = AppColors.Rating, size = 16.dp, contentDescription = "Angeheftete Gruppe")
                    }
                    if (category.locked) {
                        StaticIcon(AppIcons.Lock, tint = RowColors.muted, size = 16.dp, modifier = Modifier.padding(start = 4.dp))
                    }
                }
            }
        }
        item(key = "hint", contentType = "hint") {
            Text(
                "Tipp: OK gedrückt halten markiert einen Titel als Favorit oder heftet eine Gruppe oben an.",
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 16.dp),
                style = MaterialTheme.typography.labelSmall,
                color = AppColors.TextMuted,
            )
        }
    }
}
