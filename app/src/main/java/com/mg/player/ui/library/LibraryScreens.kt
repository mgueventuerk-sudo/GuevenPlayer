package com.mg.player.ui.library

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.paging.compose.collectAsLazyPagingItems
import com.mg.player.domain.model.Category
import com.mg.player.domain.model.Movie
import com.mg.player.domain.model.Series
import com.mg.player.ui.common.CategoryColumn
import com.mg.player.ui.common.ContextAction
import com.mg.player.ui.common.LocalContextMenu
import com.mg.player.ui.common.LocalToast
import com.mg.player.ui.common.PagingPosterGrid
import com.mg.player.ui.common.PosterItem
import com.mg.player.ui.common.appViewModel
import com.mg.player.ui.navigation.LocalNavigator
import com.mg.player.ui.navigation.Screen
import com.mg.player.ui.theme.AppIcon
import com.mg.player.ui.theme.AppIcons
import com.mg.player.ui.theme.Dimens
import kotlinx.coroutines.launch

/** Filme (VOD): Kategorien links, Netflix-artiges Poster-Grid rechts. */
@Composable
fun MoviesScreen(modifier: Modifier = Modifier) {
    val vm = appViewModel { MoviesViewModel(it) }
    val navigator = LocalNavigator.current
    LibraryLayout(
        vm = vm,
        modifier = modifier,
        key = { it.id },
        toPoster = { movie: Movie -> PosterItem(movie.id, movie.name, movie.posterUrl, movie.rating, movie.year, movie.badges, movie.progress) },
        onClick = { movie -> navigator.push(Screen.MovieDetail(movie.id)) },
        emptyText = "Keine Filme in dieser Kategorie",
        emptyIcon = AppIcons.Movie,
    )
}

/** Serien: Poster-Grid nach Kategorien. */
@Composable
fun SeriesScreen(modifier: Modifier = Modifier) {
    val vm = appViewModel { SeriesViewModel(it) }
    val navigator = LocalNavigator.current
    LibraryLayout(
        vm = vm,
        modifier = modifier,
        key = { it.id },
        toPoster = { series: Series -> PosterItem(series.id, series.name, series.posterUrl, series.rating, series.year, series.badges) },
        onClick = { series -> navigator.push(Screen.SeriesDetail(series.id)) },
        emptyText = "Keine Serien in dieser Kategorie",
        emptyIcon = AppIcons.Series,
    )
}

@Composable
private fun <T : Any> LibraryLayout(
    vm: LibraryViewModel<T>,
    key: (T) -> String,
    toPoster: (T) -> PosterItem,
    onClick: (T) -> Unit,
    emptyText: String,
    emptyIcon: AppIcon,
    modifier: Modifier = Modifier,
) {
    val categories by vm.categories.collectAsStateWithLifecycle()
    val selected by vm.selectedCategoryId.collectAsStateWithLifecycle()
    val items = vm.items.collectAsLazyPagingItems()
    val restoreKey = remember { vm.restoreKey.also { vm.restoreKey = null } }
    val toast = LocalToast.current
    val contextMenu = LocalContextMenu.current
    val scope = rememberCoroutineScope()
    // Zurück im Poster-Raster: erst zu den Kategorien (wie bei Live-TV), dann zur Navigationsleiste.
    val categoryRequester = remember { FocusRequester() }
    var firstPosterFocusRequest by remember { mutableIntStateOf(0) }
    var gridFocused by remember { mutableStateOf(false) }
    BackHandler(enabled = gridFocused) { runCatching { categoryRequester.requestFocus() } }
    Row(modifier.fillMaxSize().padding(start = Dimens.ScreenPadding)) {
        CategoryColumn(
            categories = categories,
            selectedId = selected,
            onFocused = vm::onCategoryFocused,
            onClick = vm::onCategoryClicked,
            onMoveRight = { category ->
                vm.onCategoryClicked(category)
                firstPosterFocusRequest++
            },
            modifier = Modifier.focusRequester(categoryRequester).padding(bottom = 24.dp),
            width = 220.dp,
        )
        Spacer(Modifier.width(6.dp))
        PagingPosterGrid(
            items = items,
            key = key,
            toPoster = toPoster,
            onClick = { item ->
                vm.restoreKey = key(item)
                onClick(item)
            },
            emptyText = when (selected) {
                Category.FAVORITES -> FAVORITES_EMPTY
                Category.CONTINUE -> CONTINUE_EMPTY
                else -> emptyText
            },
            emptyIcon = when (selected) {
                Category.FAVORITES -> AppIcons.Heart
                Category.CONTINUE -> AppIcons.Play
                else -> emptyIcon
            },
            restoreKey = restoreKey,
            focusFirstRequest = firstPosterFocusRequest,
            // Leere Favoriten/Weiterschauen: direkter Weg zu allen Titeln statt Sackgasse.
            emptyActionLabel = "Alle anzeigen".takeIf { selected == Category.FAVORITES || selected == Category.CONTINUE },
            onEmptyAction = vm::showAll,
            // Einheitliches Kontextmenü: Öffnen und Favorit an einer Stelle.
            onLongClick = { item ->
                val title = toPoster(item).title
                scope.launch {
                    val favorite = vm.isFavoriteNow(key(item))
                    contextMenu.show(
                        title = title,
                        actions = listOf(
                            ContextAction("Öffnen", AppIcons.Play) {
                                vm.restoreKey = key(item)
                                onClick(item)
                            },
                            ContextAction(
                                if (favorite) "Aus Favoriten entfernen" else "Zu Favoriten",
                                if (favorite) AppIcons.HeartFilled else AppIcons.Heart,
                            ) {
                                vm.toggleFavorite(key(item)) { added ->
                                    toast.show(if (added) "♥ „$title“ ist jetzt ein Favorit" else "„$title“ aus Favoriten entfernt")
                                }
                            },
                        ),
                    )
                }
            },
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
                .onFocusChanged { gridFocused = it.hasFocus },
        )
    }
}

private const val FAVORITES_EMPTY =
    "Noch keine Favoriten – halte OK auf einem Titel gedrückt, um ihn hinzuzufügen. Gruppen lassen sich separat oben anheften."

private const val CONTINUE_EMPTY = "Noch nichts angefangen – was du anschaust, erscheint hier zum Weiterschauen."
