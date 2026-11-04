package com.mg.player.ui.live

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.paging.compose.collectAsLazyPagingItems
import androidx.paging.compose.itemContentType
import androidx.paging.compose.itemKey
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.mg.player.domain.model.Category
import com.mg.player.domain.model.PlayRequest
import com.mg.player.ui.common.ActionButton
import com.mg.player.ui.common.CategoryColumn
import com.mg.player.ui.common.CenteredMessage
import com.mg.player.ui.common.GlassPanel
import com.mg.player.ui.common.LocalToast
import com.mg.player.ui.common.appViewModel
import com.mg.player.ui.common.glass
import com.mg.player.ui.navigation.LocalNavigator
import com.mg.player.ui.navigation.Screen
import com.mg.player.ui.player.EngineSurface
import com.mg.player.ui.theme.AppShapes
import com.mg.player.ui.theme.AnimatedIcon
import com.mg.player.ui.theme.AppColors
import com.mg.player.ui.theme.AppIcons
import com.mg.player.ui.theme.Dimens
import kotlinx.coroutines.delay

/** Live-TV in drei Spalten: Kategorien | Senderliste | EPG-Details & Live-Mini-Vorschau. */
@Composable
fun LiveScreen(modifier: Modifier = Modifier) {
    val vm = appViewModel { LiveViewModel(it) }
    val navigator = LocalNavigator.current
    val toast = LocalToast.current
    val categories by vm.categories.collectAsStateWithLifecycle()
    val selectedCategory by vm.selectedCategoryId.collectAsStateWithLifecycle()
    val previewResolution by vm.previewResolution.collectAsStateWithLifecycle()
    val channels = vm.channels.collectAsLazyPagingItems()
    val nowMs by produceState(System.currentTimeMillis()) {
        while (true) {
            delay(30_000)
            value = System.currentTimeMillis()
        }
    }

    // Zurück-Taste in der Senderliste: erst zu den Kategorien, dann (Startseite) zur Navigationsleiste.
    val categoryRequester = remember { FocusRequester() }
    var channelsFocused by remember { mutableStateOf(false) }
    BackHandler(enabled = channelsFocused) { runCatching { categoryRequester.requestFocus() } }

    // Nach „Zurück“ aus dem Vollbild: auf den zuletzt gesehenen Sender (auch nach dem Umschalten dort).
    val restoreTarget by vm.restoreTarget.collectAsStateWithLifecycle()
    val restoreRequester = remember { FocusRequester() }
    val listState = rememberLazyListState()
    val focusTarget = liveFocusTarget(restoreTarget)
    val restoreChannelId = (focusTarget as? LiveFocusTarget.Restore)?.channelId
    LaunchedEffect(Unit) { vm.onListShown() }

    LifecycleResumeEffect(Unit) {
        vm.setPreviewActive(true)
        onPauseOrDispose { vm.setPreviewActive(false) }
    }

    Row(
        modifier
            .fillMaxSize()
            .padding(start = Dimens.ScreenPadding, end = Dimens.ScreenPadding, bottom = 24.dp),
        horizontalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        CategoryColumn(
            categories = categories,
            selectedId = selectedCategory,
            onFocused = vm::onCategoryFocused,
            onClick = vm::onCategoryClicked,
            modifier = Modifier.focusRequester(categoryRequester),
            width = 236.dp,
        )

        Box(
            Modifier
                .weight(1.1f)
                .fillMaxHeight()
                .onFocusChanged { channelsFocused = it.hasFocus },
        ) {
            if (channels.itemCount == 0) {
                val loading = channels.loadState.refresh is androidx.paging.LoadState.Loading
                val favorites = selectedCategory == Category.FAVORITES
                CenteredMessage(
                    title = when {
                        loading -> "Lädt Sender…"
                        favorites -> "Noch keine Favoriten"
                        selectedCategory == Category.FREQUENT -> "Noch keine Sehgewohnheiten"
                        else -> "Keine Sender in dieser Kategorie"
                    },
                    subtitle = when {
                        loading -> null

                        favorites -> "Halte OK auf einem Sender gedrückt, um ihn hinzuzufügen. Gruppen lassen sich separat oben anheften."

                        selectedCategory == Category.FREQUENT -> "Sender, die du zu dieser Tageszeit länger schaust, erscheinen hier – oben die häufigsten."

                        // Leere Gruppe: sagen, was hilft, statt nur „leer“.
                        else -> "Andere Gruppe wählen oder unter Einstellungen › Zugänge & EPG aktualisieren."
                    },
                    loading = loading,
                    icon = if (favorites) AppIcons.Heart else AppIcons.LiveTv,
                )
            } else {
                RestoreScroll(restoreChannelId, channels, listState)
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .fillMaxSize()
                        .focusRestorer(FocusRequester.Default),
                    contentPadding = PaddingValues(vertical = 12.dp, horizontal = 6.dp),
                ) {
                    items(
                        count = channels.itemCount,
                        key = channels.itemKey { it.id },
                        contentType = channels.itemContentType { "channel" },
                    ) { index ->
                        val channel = channels[index]
                        if (channel != null) {
                            val isRestoreTarget = channel.id == restoreChannelId
                            if (isRestoreTarget) {
                                LaunchedEffect(restoreChannelId, channel.id) {
                                    // Wie beim Kaltstart: erst die tatsächliche Fokusmeldung quittiert Restore.
                                    repeat(40) {
                                        runCatching { restoreRequester.requestFocus() }
                                        delay(50)
                                    }
                                }
                            }
                            ChannelRow(
                                channel = channel,
                                number = channel.listNumber(index),
                                nowMs = nowMs,
                                measuredResolution = previewResolution?.takeIf { it.channelId == channel.id }?.label,
                                onClick = {
                                    vm.onOpenChannel(channel, withPreview = true)
                                    // Läuft der Sender schon in der Vorschau, spielt er im Vollbild einfach weiter.
                                    vm.handOffPreview(channel)
                                    navigator.push(Screen.Player(PlayRequest.Live(channel.id, selectedCategory)))
                                },
                                onLongClick = {
                                    vm.toggleFavorite(channel) { added ->
                                        toast.show(if (added) "♥ ${channel.name} ist jetzt ein Favorit" else "${channel.name} aus Favoriten entfernt")
                                    }
                                },
                                modifier = Modifier
                                    .padding(vertical = 3.dp)
                                    .then(if (isRestoreTarget) Modifier.focusRequester(restoreRequester) else Modifier)
                                    .onFocusChanged {
                                        if (it.isFocused) {
                                            vm.onChannelFocused(channel)
                                            if (isRestoreTarget) vm.onRestored()
                                        }
                                    },
                            )
                        }
                    }
                }
            }
        }

        LiveDetailsPane(
            vm = vm,
            nowMs = nowMs,
            onMosaic = { channelId -> navigator.push(Screen.Mosaic(selectedCategory, channelId)) },
            modifier = Modifier.weight(1f).fillMaxHeight().padding(top = 12.dp),
        )
    }
}

/**
 * Rechte Spalte: Vorschau und Programm des markierten Senders. Eigene Funktion, damit beim Durchblättern nur sie
 * neu gezeichnet wird – nicht der ganze Bildschirm samt Senderliste.
 */
@Composable
private fun LiveDetailsPane(vm: LiveViewModel, nowMs: Long, onMosaic: (String?) -> Unit, modifier: Modifier) {
    val focusedChannel by vm.focusedChannel.collectAsStateWithLifecycle()
    val epg by vm.epg.collectAsStateWithLifecycle()
    val isFavorite by vm.isFavorite.collectAsStateWithLifecycle()
    val previewEngine by vm.previewEngine.collectAsStateWithLifecycle()
    Column(modifier) {
        // Live-Vorschau im Glasrahmen.
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f)
                .glass(AppShapes.Card)
                .padding(3.dp)
                .clip(com.mg.player.ui.theme.SmoothShape(Dimens.CardRadius - 3.dp))
                .background(Color.Black.copy(alpha = if (AppColors.isDark) 0.35f else 0.08f)),
            contentAlignment = Alignment.Center,
        ) {
            val engine = previewEngine
            if (engine != null) {
                // Ambient-Licht: Das Vorschaubild färbt den Hintergrund (aus bei „Effekte reduzieren“).
                EngineSurface(engine, Modifier.fillMaxSize(), ambient = true)
            } else {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    AnimatedIcon(AppIcons.LiveTv, tint = AppColors.TextMuted, active = focusedChannel != null, size = 34.dp)
                    Spacer(Modifier.height(8.dp))
                    Text(
                        if (focusedChannel == null) "Sender auswählen" else "Vorschau startet…",
                        style = MaterialTheme.typography.bodyMedium,
                        color = AppColors.TextMuted,
                    )
                }
            }
        }
        Spacer(Modifier.height(14.dp))
        GlassPanel(Modifier.fillMaxWidth().weight(1f)) {
            Column(Modifier.fillMaxSize().padding(horizontal = 18.dp, vertical = 14.dp)) {
                EpgDetails(focusedChannel, epg, nowMs, Modifier.weight(1f))
                if (focusedChannel != null) {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        ActionButton(
                            text = if (isFavorite) "Aus Favoriten entfernen" else "Zu Favoriten",
                            icon = if (isFavorite) AppIcons.HeartFilled else AppIcons.Heart,
                            onClick = vm::toggleFavorite,
                            compact = true,
                        )
                        // Live-Mosaik ab diesem Sender: die nächsten Sender der Gruppe als Standbilder.
                        ActionButton(
                            text = "Mosaik",
                            icon = AppIcons.Grid,
                            onClick = { onMosaic(focusedChannel?.id) },
                            compact = true,
                        )
                    }
                }
            }
        }
    }
}
