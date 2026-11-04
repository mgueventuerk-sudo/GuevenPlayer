package com.mg.player.ui.phone

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.paging.LoadState
import androidx.paging.compose.collectAsLazyPagingItems
import androidx.paging.compose.itemContentType
import androidx.paging.compose.itemKey
import com.mg.player.domain.model.Category
import com.mg.player.domain.model.PlayRequest
import com.mg.player.ui.common.CenteredMessage
import com.mg.player.ui.common.LocalToast
import com.mg.player.ui.common.appViewModel
import com.mg.player.ui.live.ChannelRow
import com.mg.player.ui.live.LiveViewModel
import com.mg.player.ui.live.RestoreScroll
import com.mg.player.ui.navigation.LocalNavigator
import com.mg.player.ui.navigation.Screen
import com.mg.player.ui.theme.AppIcons
import kotlinx.coroutines.delay

/**
 * Live-TV am Handy: Kategorien als Chips, darunter die Senderliste mit laufender Sendung und Fortschritt.
 * Antippen startet den Sender, gedrückt halten macht ihn zum Favoriten. (Keine Mini-Vorschau – spart Datenvolumen.)
 */
@Composable
fun PhoneLiveScreen(modifier: Modifier = Modifier) {
    val vm = appViewModel { LiveViewModel(it) }
    val navigator = LocalNavigator.current
    val toast = LocalToast.current
    val categories by vm.categories.collectAsStateWithLifecycle()
    val selected by vm.selectedCategoryId.collectAsStateWithLifecycle()
    val channels = vm.channels.collectAsLazyPagingItems()
    val restoreTarget by vm.restoreTarget.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { vm.onListShown() }
    val nowMs by produceState(System.currentTimeMillis()) {
        while (true) {
            delay(30_000)
            value = System.currentTimeMillis()
        }
    }

    Column(modifier) {
        CategoryChipRow(categories, selected, onSelect = vm::onCategoryClicked)
        if (channels.itemCount == 0) {
            val loading = channels.loadState.refresh is LoadState.Loading
            val favorites = selected == Category.FAVORITES
            CenteredMessage(
                title = when {
                    loading -> "Lädt Sender…"
                    favorites -> "Noch keine Favoriten"
                    selected == Category.FREQUENT -> "Noch keine Sehgewohnheiten"
                    else -> "Keine Sender in dieser Kategorie"
                },
                subtitle = if (favorites && !loading) "Halte einen Sender gedrückt, um ihn hinzuzufügen. Gruppen lassen sich separat oben anheften." else null,
                loading = loading,
                icon = if (favorites) AppIcons.Heart else AppIcons.LiveTv,
                modifier = Modifier.padding(horizontal = 24.dp),
            )
            return@Column
        }
        val listState = rememberLazyListState()
        // Nach „Zurück“ aus dem Vollbild: zum zuletzt gesehenen Sender (auch nach dem Wischen zum nächsten).
        RestoreScroll(restoreTarget, channels, listState, onScrolled = vm::onRestored)
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 8.dp, end = 12.dp, top = 4.dp, bottom = 12.dp),
        ) {
            items(
                count = channels.itemCount,
                key = channels.itemKey { it.id },
                contentType = channels.itemContentType { "channel" },
            ) { index ->
                val channel = channels[index] ?: return@items
                ChannelRow(
                    channel = channel,
                    number = channel.listNumber(index),
                    nowMs = nowMs,
                    onClick = {
                        vm.onOpenChannel(channel, withPreview = false)
                        navigator.push(Screen.Player(PlayRequest.Live(channel.id, selected)))
                    },
                    onLongClick = {
                        vm.toggleFavorite(channel) { added ->
                            toast.show(if (added) "♥ ${channel.name} ist jetzt ein Favorit" else "${channel.name} aus Favoriten entfernt")
                        }
                    },
                    modifier = Modifier.padding(vertical = 2.dp),
                )
            }
        }
    }
}
