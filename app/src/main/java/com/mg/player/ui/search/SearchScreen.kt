package com.mg.player.ui.search

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Text
import com.mg.player.domain.model.Category
import com.mg.player.domain.model.ContentType
import com.mg.player.domain.model.PlayRequest
import com.mg.player.domain.model.Playlist
import com.mg.player.domain.model.SearchHit
import com.mg.player.domain.model.SearchPages
import com.mg.player.ui.common.CenteredMessage
import com.mg.player.ui.common.ChoiceChip
import com.mg.player.ui.common.TvTextField
import com.mg.player.ui.common.appViewModel
import com.mg.player.ui.common.edgePadding
import com.mg.player.ui.common.isTouch
import com.mg.player.ui.navigation.LocalNavigator
import com.mg.player.ui.navigation.Screen
import com.mg.player.ui.theme.AppColors
import com.mg.player.ui.theme.AppIcons
import com.mg.player.ui.theme.AppText
import com.mg.player.ui.theme.Spacing
import kotlinx.coroutines.flow.map

/** Globale Suche über Sender, Filme und Serien aller Playlists – mit Seiten je Playlist und Bereichs-Filter. */
@Composable
fun SearchScreen(modifier: Modifier = Modifier) {
    val vm = appViewModel { SearchViewModel(it) }
    val navigator = LocalNavigator.current
    val query by vm.query.collectAsStateWithLifecycle()
    val results by vm.results.collectAsStateWithLifecycle()
    val page by vm.page.collectAsStateWithLifecycle()
    val playlists by vm.playlists.collectAsStateWithLifecycle()
    val filter by vm.filter.collectAsStateWithLifecycle()
    val recent by vm.recent.collectAsStateWithLifecycle()
    val touch = isTouch
    val multi = playlists.size > 1
    val pageIds = remember(playlists) { listOf<Long?>(null) + playlists.map { it.id } }
    // Scrollposition je Seite bleibt beim Wechsel erhalten.
    val listStates = remember { HashMap<Long?, LazyListState>() }
    val onOpen: (SearchHit) -> Unit = { hit ->
        navigator.push(
            when (hit.type) {
                ContentType.LIVE -> Screen.Player(PlayRequest.Live(hit.id, Category.ALL))
                ContentType.MOVIE -> Screen.MovieDetail(hit.id)
                ContentType.SERIES -> Screen.SeriesDetail(hit.id)
            },
        )
    }

    val edge = edgePadding
    Column(
        modifier
            .fillMaxSize()
            .padding(start = edge)
            // Kanal +/− (bzw. Bild auf/ab) blättert von überall aus durch die Seiten.
            .onPreviewKeyEvent { event ->
                if (!multi || event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when (event.key) {
                    Key.ChannelUp, Key.PageUp -> {
                        vm.stepPage(-1)
                        true
                    }

                    Key.ChannelDown, Key.PageDown -> {
                        vm.stepPage(1)
                        true
                    }

                    else -> false
                }
            },
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Row(
            Modifier.padding(top = 6.dp, end = edge).fillMaxWidth(if (touch) 1f else 0.62f),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TvTextField(
                value = query,
                onValueChange = vm::onQueryChange,
                label = "",
                placeholder = SEARCH_PLACEHOLDER,
                imeAction = ImeAction.Search,
                leadingIcon = AppIcons.Search,
                large = true,
                modifier = Modifier.weight(1f),
            )
            VoiceSearchButton(onResult = vm::onQueryChange, modifier = Modifier.padding(start = 12.dp))
        }
        val pages = results
        // Playlists und Filter erst, wenn gesucht wird (ab zwei Zeichen) – vorher gäbe es nichts zu filtern.
        AnimatedVisibility(visible = pages != null, enter = fadeIn() + expandVertically(), exit = fadeOut() + shrinkVertically()) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                if (multi) {
                    SearchPageTabs(playlists, results, page, filter, onSelect = vm::selectPage, modifier = Modifier.padding(top = 12.dp))
                }
                SearchFilterChips(filter, vm::setFilter, Modifier.padding(top = 10.dp, end = edge))
            }
        }
        Spacer(Modifier.height(6.dp))
        // Zuletzt gesucht: ein Klick sucht erneut (nur solange noch nichts eingegeben ist).
        if (pages == null && recent.isNotEmpty()) {
            RecentSearchChips(recent, onPick = vm::onQueryChange, onClear = vm::clearRecent, Modifier.padding(top = 12.dp, end = edge))
        }
        when {
            pages == null -> CenteredMessage(
                "Wonach suchst du?",
                subtitle = if (multi) {
                    "Mindestens zwei Zeichen eingeben – Sender, Filme und Serien aller Playlists werden durchsucht. " +
                        "Danach zwischen „$ALL_PLAYLISTS“ und den einzelnen Playlists wechseln."
                } else {
                    "Mindestens zwei Zeichen eingeben – Sender, Filme und Serien werden zusammen durchsucht."
                },
                icon = AppIcons.Search,
            )

            touch && multi -> {
                // Handy: waagerecht zwischen den Seiten wischen.
                val pagerState = rememberPagerState(initialPage = pageIds.indexOf(page).coerceAtLeast(0)) { pageIds.size }
                LaunchedEffect(page, pageIds) {
                    val target = pageIds.indexOf(page).coerceAtLeast(0)
                    if (pagerState.currentPage != target) pagerState.animateScrollToPage(target)
                }
                LaunchedEffect(pagerState, pageIds) {
                    snapshotFlow { pagerState.settledPage }.collect { vm.selectPage(pageIds.getOrNull(it)) }
                }
                HorizontalPager(pagerState, Modifier.fillMaxSize(), key = { pageIds[it] ?: -1L }) { index ->
                    val id = pageIds[index]
                    SearchPage(pages, id, playlists, filter, query, listStates.getOrPut(id) { LazyListState() }, onOpen)
                }
            }

            else -> SearchPage(pages, page, playlists, filter, query, listStates.getOrPut(page) { LazyListState() }, onOpen)
        }
    }
}

@Composable
private fun SearchPage(
    pages: SearchPages,
    pageId: Long?,
    playlists: List<Playlist>,
    filter: ContentType?,
    query: String,
    listState: LazyListState,
    onOpen: (SearchHit) -> Unit,
) {
    val hits = if (pageId == null) pages.all else pages.playlists.firstOrNull { it.playlistId == pageId }?.hits.orEmpty()
    val sections = remember(hits, filter) { (if (filter == null) hits else hits.filter { it.type == filter }).toSections() }
    if (sections.isEmpty()) {
        val name = playlists.firstOrNull { it.id == pageId }?.name
        CenteredMessage(
            if (name == null) "Keine Treffer für „$query“" else "Keine Treffer in „$name“",
            subtitle = if (playlists.size > 1) "Anders schreiben, kürzer suchen oder eine andere Playlist wählen." else "Anders schreiben oder kürzer suchen.",
            icon = AppIcons.Search,
        )
    } else {
        SearchResultSections(sections = sections, onOpen = onOpen, listState = listState)
    }
}

internal const val SEARCH_PLACEHOLDER = "Sender, Filme oder Serien suchen"

@Composable
private fun RecentSearchChips(recent: List<String>, onPick: (String) -> Unit, onClear: () -> Unit, modifier: Modifier = Modifier) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text("Zuletzt gesucht", style = AppText.caption, color = AppColors.TextSecondary)
        LazyRow(
            modifier = Modifier.padding(top = Spacing.Xs),
            horizontalArrangement = Arrangement.spacedBy(Spacing.Xs),
            contentPadding = PaddingValues(horizontal = Spacing.Xxs, vertical = Spacing.Xxs),
        ) {
            items(recent, key = { it }) { term -> ChoiceChip(term, selected = false, onClick = { onPick(term) }, icon = AppIcons.Clock) }
            item(key = "__clear__") { ChoiceChip("Verlauf löschen", selected = false, onClick = onClear, icon = AppIcons.Trash) }
        }
    }
}
