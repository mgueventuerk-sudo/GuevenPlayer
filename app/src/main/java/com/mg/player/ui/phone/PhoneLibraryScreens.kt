package com.mg.player.ui.phone

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.paging.compose.collectAsLazyPagingItems
import com.mg.player.domain.model.Category
import com.mg.player.domain.model.Movie
import com.mg.player.domain.model.Series
import com.mg.player.ui.common.LocalToast
import com.mg.player.ui.common.PagingPosterGrid
import com.mg.player.ui.common.PosterItem
import com.mg.player.ui.common.appViewModel
import com.mg.player.ui.library.LibraryViewModel
import com.mg.player.ui.library.MoviesViewModel
import com.mg.player.ui.library.SeriesViewModel
import com.mg.player.ui.navigation.LocalNavigator
import com.mg.player.ui.navigation.Screen
import com.mg.player.ui.theme.AppIcon
import com.mg.player.ui.theme.AppIcons

@Composable
fun PhoneMoviesScreen(modifier: Modifier = Modifier) {
    val vm = appViewModel { MoviesViewModel(it) }
    val navigator = LocalNavigator.current
    PhoneLibrary(
        vm = vm,
        modifier = modifier,
        key = { it.id },
        toPoster = { movie: Movie -> PosterItem(movie.id, movie.name, movie.posterUrl, movie.rating, movie.year, movie.badges, movie.progress) },
        onClick = { movie -> navigator.push(Screen.MovieDetail(movie.id)) },
        emptyText = "Keine Filme in dieser Kategorie",
        emptyIcon = AppIcons.Movie,
    )
}

@Composable
fun PhoneSeriesScreen(modifier: Modifier = Modifier) {
    val vm = appViewModel { SeriesViewModel(it) }
    val navigator = LocalNavigator.current
    PhoneLibrary(
        vm = vm,
        modifier = modifier,
        key = { it.id },
        toPoster = { series: Series -> PosterItem(series.id, series.name, series.posterUrl, series.rating, series.year, series.badges) },
        onClick = { series -> navigator.push(Screen.SeriesDetail(series.id)) },
        emptyText = "Keine Serien in dieser Kategorie",
        emptyIcon = AppIcons.Series,
    )
}

/** Filme bzw. Serien am Handy: Kategorien als Chips, darunter drei bis vier Poster je Reihe. */
@Composable
private fun <T : Any> PhoneLibrary(
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
    val toast = LocalToast.current
    val padding = remember { PaddingValues(start = 16.dp, end = 16.dp, top = 10.dp, bottom = 20.dp) }
    Column(modifier) {
        CategoryChipRow(categories, selected, onSelect = vm::onCategoryClicked)
        PagingPosterGrid(
            items = items,
            key = key,
            toPoster = toPoster,
            onClick = onClick,
            emptyText = when (selected) {
                Category.FAVORITES -> "Noch keine Favoriten – halte einen Titel gedrückt, um ihn hinzuzufügen. Gruppen lassen sich separat oben anheften."
                Category.CONTINUE -> "Noch nichts angefangen – was du anschaust, erscheint hier zum Weiterschauen."
                else -> emptyText
            },
            emptyIcon = when (selected) {
                Category.FAVORITES -> AppIcons.Heart
                Category.CONTINUE -> AppIcons.Play
                else -> emptyIcon
            },
            onLongClick = { item ->
                val title = toPoster(item).title
                vm.toggleFavorite(key(item)) { added ->
                    toast.show(if (added) "♥ „$title“ ist jetzt ein Favorit" else "„$title“ aus Favoriten entfernt")
                }
            },
            minCellWidth = 104.dp,
            contentPadding = padding,
            spacing = 12.dp,
            modifier = Modifier.fillMaxSize(),
        )
    }
}
