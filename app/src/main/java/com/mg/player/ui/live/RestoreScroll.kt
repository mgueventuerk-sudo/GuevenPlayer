package com.mg.player.ui.live

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.paging.compose.LazyPagingItems
import com.mg.player.domain.model.Channel

/**
 * Scrollt die Senderliste zum Sender [target], sobald er geladen ist. Nötig, weil die gemerkte Scroll-Position
 * zur alten Liste gehört – nach dem Umschalten im Vollbild beginnt die Liste rund um den zuletzt gesehenen Sender.
 * [onScrolled] meldet, dass der Sender sichtbar ist (am Handy das Ende der Wiederherstellung; am TV folgt der Fokus).
 */
@Composable
fun RestoreScroll(target: String?, channels: LazyPagingItems<Channel>, listState: LazyListState, onScrolled: () -> Unit = {}) {
    LaunchedEffect(target, channels.itemCount) {
        if (target == null) return@LaunchedEffect
        val index = channels.itemSnapshotList.indexOfFirst { it?.id == target }
        if (index < 0) return@LaunchedEffect
        if (listState.layoutInfo.visibleItemsInfo.none { it.index == index }) {
            listState.scrollToItem((index - VISIBLE_ABOVE).coerceAtLeast(0))
        }
        onScrolled()
    }
}

private const val VISIBLE_ABOVE = 2
