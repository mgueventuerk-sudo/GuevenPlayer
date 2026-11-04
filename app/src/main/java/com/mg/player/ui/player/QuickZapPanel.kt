package com.mg.player.ui.player

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.unit.dp
import androidx.paging.compose.collectAsLazyPagingItems
import androidx.paging.compose.itemContentType
import androidx.paging.compose.itemKey
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.mg.player.domain.model.Channel
import com.mg.player.ui.common.LocalAppContainer
import com.mg.player.ui.common.glass
import com.mg.player.ui.common.isTouch
import com.mg.player.ui.common.trapFocus
import com.mg.player.ui.live.ChannelRow
import com.mg.player.ui.theme.AppShapes
import com.mg.player.ui.theme.AppColors
import com.mg.player.ui.theme.Dimens
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Schlanke Senderliste mit EPG-Vorschau über dem laufenden Bild (Live: Links-Taste).
 * Das Video läuft im Hintergrund weiter; OK schaltet um, Zurück schließt die Liste.
 */
@Composable
fun QuickZapPanel(
    playlistId: Long,
    categoryId: String,
    currentNumber: Int?,
    onSelect: (Channel) -> Unit,
    modifier: Modifier = Modifier,
    title: String = "Senderliste",
    /** Liste bleibt leer (z. B. leere Kategorie): Panel schließen, sonst gäbe es nichts zum Fokussieren. */
    onEmpty: () -> Unit = {},
) {
    val container = LocalAppContainer.current
    val startIndex = ((currentNumber ?: 1) - 1).coerceAtLeast(0)
    // Die Liste beginnt gleich rund um den laufenden Sender – kein seitenweises Nachladen bis dorthin.
    val channels = remember(playlistId, categoryId) {
        container.liveRepository.channels(playlistId, categoryId, startAt = (startIndex - ROWS_ABOVE).coerceAtLeast(0))
    }.collectAsLazyPagingItems()
    val listState = rememberLazyListState()
    val requester = remember { FocusRequester() }
    var focusPosition by remember { mutableIntStateOf(-1) }
    var focusDone by remember { mutableStateOf(false) }
    val nowMs by produceState(System.currentTimeMillis()) {
        while (true) {
            delay(30_000)
            value = System.currentTimeMillis()
        }
    }
    // Fokus auf den laufenden Sender: Seiten nachladen, bis er in der Liste ist, dann hinscrollen.
    LaunchedEffect(channels) {
        if (withTimeoutOrNull(3_000) { snapshotFlow { channels.itemCount }.first { it > 0 } } == null) {
            onEmpty()
            return@LaunchedEffect
        }
        val loaded = channels.itemSnapshotList
        val target = loaded.indexOfFirst { it?.listPosition == startIndex }.takeIf { it >= 0 } ?: 0
        // Erst beim nächsten Layout springen: scrollToItem mitten in einem Messdurchgang bringt Compose zum Absturz.
        listState.requestScrollToItem((target - 3).coerceAtLeast(0))
        // Nach Position statt Index: Lädt die Liste oben Sender nach, verschieben sich die Indizes.
        focusPosition = loaded.getOrNull(target)?.listPosition ?: -1
        repeat(40) {
            if (focusDone) return@LaunchedEffect
            runCatching { requester.requestFocus() }
            delay(50)
        }
    }

    Column(
        modifier
            // Fokus bleibt in der Liste: "rechts" springt nicht auf Knöpfe hinter dem Panel.
            .trapFocus()
            .padding(16.dp)
            .widthIn(max = 470.dp)
            .fillMaxHeight()
            .glass(AppShapes.Panel, fill = AppColors.Panel)
            .padding(start = 18.dp, top = 22.dp, bottom = 12.dp, end = 12.dp),
    ) {
        Text(title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(start = 8.dp))
        Text(
            if (isTouch) "Antippen schaltet um · Zurück schließt die Liste" else "OK schaltet um · Zurück schließt die Liste",
            style = MaterialTheme.typography.labelSmall,
            color = AppColors.TextMuted,
            modifier = Modifier.padding(start = 8.dp, bottom = 10.dp),
        )
        LazyColumn(
            state = listState,
            modifier = Modifier
                .weight(1f)
                .onFocusChanged { if (it.hasFocus) focusDone = true }
                .focusRestorer(FocusRequester.Default),
            contentPadding = PaddingValues(vertical = 4.dp),
        ) {
            items(
                count = channels.itemCount,
                key = channels.itemKey { it.id },
                contentType = channels.itemContentType { "quick-zap" },
            ) { index ->
                val channel = channels[index] ?: return@items
                ChannelRow(
                    channel = channel,
                    number = channel.listNumber(index),
                    nowMs = nowMs,
                    onClick = { onSelect(channel) },
                    modifier = Modifier
                        .padding(vertical = 2.dp)
                        .then(if (channel.listPosition == focusPosition) Modifier.focusRequester(requester) else Modifier),
                )
            }
        }
    }
}

/** So viele Sender stehen über dem laufenden, wenn die Liste aufgeht. */
private const val ROWS_ABOVE = 10
