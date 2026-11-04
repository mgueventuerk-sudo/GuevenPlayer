package com.mg.player.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mg.player.core.di.AppContainer
import com.mg.player.domain.model.Category
import com.mg.player.domain.model.ContentType
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

/** Kategorien ausblenden, sperren, anheften und in eine eigene Reihenfolge bringen. */
class ManageCategoriesViewModel(private val container: AppContainer) : ViewModel() {

    private val _type = MutableStateFlow(ContentType.LIVE)
    val type: StateFlow<ContentType> = _type.asStateFlow()
    val hasPin: StateFlow<Boolean> = container.parentalControl.hasPin

    private val stored = combine(container.settingsRepository.settings, _type) { s, t -> s.activePlaylistId to t }
        .flatMapLatest { (id, t) -> if (id == null) flowOf(emptyList()) else container.categoryRepository.observeAll(id, t) }

    /** Reihenfolge beim Verschieben sofort zeigen – schnelle Tastendrücke warten nicht auf die Datenbank. */
    private val localOrder = MutableStateFlow<List<String>?>(null)

    val categories: StateFlow<List<Category>> = combine(stored, localOrder, ::applyOrder)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Kategorie, die gerade verschoben wird (TV: OK auf „Verschieben“, dann hoch/runter). */
    private val _moving = MutableStateFlow<String?>(null)
    val moving: StateFlow<String?> = _moving.asStateFlow()

    /** Speichern nacheinander, damit bei schnellen Tastendrücken die letzte Reihenfolge gewinnt. */
    private val writeLock = Mutex()

    fun selectType(type: ContentType) {
        _moving.value = null
        localOrder.value = null
        _type.value = type
    }

    fun update(category: Category) {
        viewModelScope.launch {
            val playlistId = container.settingsRepository.current().activePlaylistId ?: return@launch
            container.categoryRepository.save(playlistId, category)
        }
    }

    fun toggleMoving(id: String) {
        _moving.value = if (_moving.value == id) null else id
    }

    fun stopMoving() {
        _moving.value = null
    }

    /** Eine Stelle hoch (-1) oder runter (+1). false = geht nicht (Rand oder Grenze zu den angehefteten Gruppen). */
    fun move(id: String, delta: Int): Boolean {
        val ids = movedOrder(categories.value, id, delta) ?: return false
        localOrder.value = ids
        viewModelScope.launch {
            writeLock.withLock {
                val playlistId = container.settingsRepository.current().activePlaylistId ?: return@withLock
                container.categoryRepository.saveOrder(playlistId, ids)
            }
        }
        return true
    }

    /** Zurück zur Reihenfolge des Anbieters. */
    fun resetOrder() {
        _moving.value = null
        val ids = categories.value.map { it.id }
        localOrder.value = null
        viewModelScope.launch {
            writeLock.withLock {
                val playlistId = container.settingsRepository.current().activePlaylistId ?: return@withLock
                container.categoryRepository.resetOrder(playlistId, ids)
            }
        }
    }
}

/** Gespeicherte Liste in der lokalen Reihenfolge; angeheftete Gruppen bleiben oben (stabil sortiert). */
internal fun applyOrder(list: List<Category>, order: List<String>?): List<Category> {
    if (order == null) return list
    val byId = list.associateBy { it.id }
    // Andere Art oder Gruppen dazugekommen/weggefallen: dann gilt die gespeicherte Liste.
    if (order.size != list.size || order.any { it !in byId }) return list
    return order.map { byId.getValue(it) }.sortedByDescending { it.pinned }
}

/** Neue Reihenfolge (Ids) nach dem Verschieben von [id] um [delta]; null, wenn es nicht geht. */
internal fun movedOrder(list: List<Category>, id: String, delta: Int): List<String>? {
    val from = list.indexOfFirst { it.id == id }
    val to = from + delta
    if (from < 0 || delta == 0 || to !in list.indices || list[to].pinned != list[from].pinned) return null
    return list.map { it.id }.toMutableList().apply { add(to, removeAt(from)) }
}
