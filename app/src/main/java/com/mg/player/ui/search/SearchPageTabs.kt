package com.mg.player.ui.search

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.mg.player.domain.model.ContentType
import com.mg.player.domain.model.Playlist
import com.mg.player.domain.model.SearchPages
import com.mg.player.ui.common.ChoiceChip
import com.mg.player.ui.common.edgePadding
import com.mg.player.ui.theme.AppIcons

internal const val ALL_PLAYLISTS = "Alle Playlists"
internal const val FILTER_TAG = "search-filter"

/** Trefferzahl einer Seite (null = noch kein Suchbegriff). */
internal fun SearchPages.count(pageId: Long?, filter: ContentType?): Int {
    val hits = if (pageId == null) all else playlists.firstOrNull { it.playlistId == pageId }?.hits.orEmpty()
    return if (filter == null) hits.size else hits.count { it.type == filter }
}

/**
 * Seiten der Suche: „Alle Playlists · N“, „MG1 · N“, „MG2 · N“. Die gewählte Seite rollt immer ins Bild;
 * Seiten ohne Treffer sind abgeblendet, bleiben aber wählbar (Fokus springt sonst unerwartet).
 */
@Composable
internal fun SearchPageTabs(
    playlists: List<Playlist>,
    pages: SearchPages?,
    selected: Long?,
    filter: ContentType?,
    onSelect: (Long?) -> Unit,
    modifier: Modifier = Modifier,
) {
    val ids = listOf<Long?>(null) + playlists.map { it.id }
    val state = rememberLazyListState()
    val selectedIndex = ids.indexOf(selected).coerceAtLeast(0)
    LaunchedEffect(selectedIndex) { state.animateScrollToItem(selectedIndex) }
    LazyRow(
        state = state,
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(end = edgePadding),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        itemsIndexed(ids, key = { _, id -> id ?: -1L }) { _, id ->
            val name = if (id == null) ALL_PLAYLISTS else playlists.first { it.id == id }.name
            val count = pages?.count(id, filter)
            ChoiceChip(
                text = if (count == null) name else "$name · $count",
                selected = id == selected,
                onClick = { onSelect(id) },
                icon = if (id == null) AppIcons.Layers else AppIcons.User,
                modifier = if (count == 0 && id != selected) Modifier.alpha(0.45f) else Modifier,
            )
        }
    }
}

/** Bereichs-Filter: Alles · Live · Filme · Serien – gilt für jede Seite. */
@Composable
internal fun SearchFilterChips(filter: ContentType?, onSelect: (ContentType?) -> Unit, modifier: Modifier = Modifier) {
    // Waagerecht scrollbar: Am Handy im Hochformat passen die vier Bereiche nicht nebeneinander.
    Row(modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).testTag(FILTER_TAG), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        ChoiceChip("Alles", filter == null, onClick = { onSelect(null) }, icon = AppIcons.Search)
        ChoiceChip("Live", filter == ContentType.LIVE, onClick = { onSelect(ContentType.LIVE) }, icon = AppIcons.LiveTv)
        ChoiceChip("Filme", filter == ContentType.MOVIE, onClick = { onSelect(ContentType.MOVIE) }, icon = AppIcons.Movie)
        ChoiceChip("Serien", filter == ContentType.SERIES, onClick = { onSelect(ContentType.SERIES) }, icon = AppIcons.Series)
    }
}
