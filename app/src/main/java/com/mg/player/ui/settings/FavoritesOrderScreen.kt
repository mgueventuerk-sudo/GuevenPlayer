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
import androidx.compose.foundation.lazy.itemsIndexed
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
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.mg.player.core.di.AppContainer
import com.mg.player.domain.model.ContentType
import com.mg.player.domain.model.FavoriteName
import com.mg.player.ui.common.CenteredMessage
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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Einzeln gemerkte Favoriten (Sender, Filme, Serien) in eine eigene Reihenfolge bringen. */
class FavoritesOrderViewModel(private val container: AppContainer) : ViewModel() {
    private val _type = MutableStateFlow(ContentType.LIVE)
    val type: StateFlow<ContentType> = _type.asStateFlow()
    private val localOrder = MutableStateFlow<List<String>?>(null)
    private val _moving = MutableStateFlow<String?>(null)
    val moving: StateFlow<String?> = _moving.asStateFlow()
    private val writeLock = Mutex()

    private val stored = combine(container.settingsRepository.settings, _type) { s, t -> s.activePlaylistId to t }
        .flatMapLatest { (id, t) -> if (id == null) flowOf(emptyList()) else container.favoriteOrder.observe(t, id) }

    val favorites: StateFlow<List<FavoriteName>> = combine(stored, localOrder) { list, order ->
        val byId = list.associateBy { it.id }
        if (order == null || order.size != list.size || order.any { it !in byId }) list else order.map { byId.getValue(it) }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun selectType(type: ContentType) {
        _moving.value = null
        localOrder.value = null
        _type.value = type
    }

    fun toggleMoving(id: String) {
        _moving.value = if (_moving.value == id) null else id
    }

    fun stopMoving() {
        _moving.value = null
    }

    fun move(id: String, delta: Int): Boolean {
        val ids = movedIds(favorites.value.map { it.id }, id, delta) ?: return false
        localOrder.value = ids
        val type = _type.value
        viewModelScope.launch {
            writeLock.withLock { container.favoriteOrder.saveOrder(type, ids) }
        }
        return true
    }
}

/** Neue Reihenfolge nach dem Verschieben von [id] um [delta]; null am Rand. */
internal fun movedIds(ids: List<String>, id: String, delta: Int): List<String>? {
    val from = ids.indexOf(id)
    val to = from + delta
    if (from < 0 || delta == 0 || to !in ids.indices) return null
    return ids.toMutableList().apply { add(to, removeAt(from)) }
}

@Composable
fun FavoritesOrderScreen() {
    val vm = appViewModel { FavoritesOrderViewModel(it) }
    val type by vm.type.collectAsStateWithLifecycle()
    val favorites by vm.favorites.collectAsStateWithLifecycle()
    val moving by vm.moving.collectAsStateWithLifecycle()
    BackHandler(enabled = moving != null) { vm.stopMoving() }
    val touch = isTouch
    val listState = rememberLazyListState()
    LaunchedEffect(favorites, moving) {
        val index = favorites.indexOfFirst { it.id == moving }.takeIf { it >= 0 } ?: return@LaunchedEffect
        val visible = listState.layoutInfo.visibleItemsInfo
        if (visible.isNotEmpty() && (index <= visible.first().index || index >= visible.last().index)) {
            listState.scrollToItem((index - 2).coerceAtLeast(0))
        }
    }
    Column(Modifier.fillMaxSize().padding(start = if (touch) 16.dp else 64.dp, top = if (touch) 12.dp else 40.dp, end = if (touch) 16.dp else 64.dp)) {
        ScreenTitle("Favoriten sortieren")
        Text(
            if (touch) {
                "Mit den Pfeilen verschieben. Neue Favoriten kommen oben dazu."
            } else {
                "„Verschieben“: OK, dann hoch/runter, OK zum Ablegen. Neue Favoriten kommen oben dazu."
            },
            style = MaterialTheme.typography.bodyMedium,
            color = AppColors.TextSecondary,
            modifier = Modifier.padding(top = 4.dp, bottom = 12.dp),
        )
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            listOf(ContentType.LIVE to "Sender", ContentType.MOVIE to "Filme", ContentType.SERIES to "Serien").forEach { (t, label) ->
                ChoiceChip(label, selected = t == type, onClick = { vm.selectType(t) })
            }
        }
        Spacer(Modifier.height(12.dp))
        if (favorites.isEmpty()) {
            CenteredMessage(title = "Noch keine Favoriten", subtitle = "Halte OK auf einem Titel gedrückt, um ihn hinzuzufügen.", icon = AppIcons.Heart)
            return@Column
        }
        LazyColumn(state = listState, modifier = Modifier.fillMaxSize().focusRestorer(FocusRequester.Default), contentPadding = PaddingValues(bottom = 40.dp)) {
            itemsIndexed(favorites, key = { _, item -> item.id }) { index, favorite ->
                val isMoving = moving == favorite.id
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(vertical = 3.dp)
                        .glass(AppShapes.Row, fill = if (isMoving) AppColors.AccentSoft else null)
                        .padding(horizontal = 16.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("${index + 1}", style = MaterialTheme.typography.labelLarge, color = AppColors.TextMuted, modifier = Modifier.width(40.dp))
                    Text(
                        favorite.name ?: favorite.id,
                        style = MaterialTheme.typography.titleSmall,
                        color = AppColors.TextPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    if (touch) {
                        PhoneIconButton(AppIcons.ChevronUp, "Nach oben", onClick = { vm.move(favorite.id, -1) })
                        Spacer(Modifier.width(8.dp))
                        PhoneIconButton(AppIcons.ChevronDown, "Nach unten", onClick = { vm.move(favorite.id, 1) })
                    } else {
                        ChoiceChip(
                            if (isMoving) "Ablegen" else "Verschieben",
                            selected = isMoving,
                            icon = AppIcons.Reorder,
                            onClick = { vm.toggleMoving(favorite.id) },
                            modifier = Modifier.onPreviewKeyEvent { event ->
                                if (!isMoving || event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                                when (event.key) {
                                    Key.DirectionUp -> vm.move(favorite.id, -1).let { true }
                                    Key.DirectionDown -> vm.move(favorite.id, 1).let { true }
                                    Key.DirectionLeft, Key.DirectionRight -> true
                                    else -> false
                                }
                            },
                        )
                    }
                }
            }
        }
    }
}
