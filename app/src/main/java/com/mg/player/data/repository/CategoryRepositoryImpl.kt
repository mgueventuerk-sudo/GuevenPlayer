package com.mg.player.data.repository

import com.mg.player.data.db.AppDatabase
import com.mg.player.data.db.entity.CategoryPrefEntity
import com.mg.player.data.mapper.toDomain
import com.mg.player.domain.model.Category
import com.mg.player.domain.model.ContentType
import com.mg.player.domain.repository.CategoryRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

class CategoryRepositoryImpl(
    private val db: AppDatabase,
    /** Gruppe ein-/ausgeblendet oder gesperrt: Sender-Gruppierung neu aufbauen; [becameVisible] -> EPG nachladen. */
    private val onPrefsChanged: (playlistId: Long, becameVisible: Boolean) -> Unit = { _, _ -> },
) : CategoryRepository {

    override fun observeAll(playlistId: Long, type: ContentType): Flow<List<Category>> =
        db.categoryDao().observeAll(playlistId, type.name).map { list -> list.map { it.toDomain() } }

    override suspend fun save(playlistId: Long, category: Category) = withContext(Dispatchers.IO) {
        val before = db.categoryPrefDao().get(category.id)
        // Ohne gespeicherte Einstellung ist eine Gruppe sichtbar und nicht gesperrt.
        val wasHidden = before?.hidden ?: false
        val wasLocked = before?.locked ?: false
        val becameVisible = wasHidden && !category.hidden
        db.categoryPrefDao().upsert(
            CategoryPrefEntity(
                categoryId = category.id,
                playlistId = playlistId,
                hidden = category.hidden,
                locked = category.locked,
                pinned = category.pinned,
                position = before?.position,
            ),
        )
        // Nur Favorit-Markierung geändert: Die Sender-Gruppierung bleibt gleich, kein Neuaufbau.
        if (wasHidden != category.hidden || wasLocked != category.locked) onPrefsChanged(playlistId, becameVisible)
    }

    override suspend fun saveOrder(playlistId: Long, orderedIds: List<String>) = withContext(Dispatchers.IO) {
        val existing = db.categoryPrefDao().forPlaylist(playlistId).associateBy { it.categoryId }
        val prefs = orderedIds.mapIndexed { index, id ->
            existing[id]?.copy(position = index)
                ?: CategoryPrefEntity(categoryId = id, playlistId = playlistId, hidden = false, locked = false, pinned = false, position = index)
        }
        db.categoryPrefDao().upsertAll(prefs)
    }

    override suspend fun resetOrder(playlistId: Long, ids: List<String>) = withContext(Dispatchers.IO) {
        val wanted = ids.toHashSet()
        val prefs = db.categoryPrefDao().forPlaylist(playlistId).filter { it.categoryId in wanted && it.position != null }
        if (prefs.isNotEmpty()) db.categoryPrefDao().upsertAll(prefs.map { it.copy(position = null) })
    }

    override suspend fun isLocked(categoryId: String): Boolean = withContext(Dispatchers.IO) {
        db.categoryPrefDao().get(categoryId)?.locked == true
    }
}
