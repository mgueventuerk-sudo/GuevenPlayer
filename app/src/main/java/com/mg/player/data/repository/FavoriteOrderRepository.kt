package com.mg.player.data.repository

import androidx.room.withTransaction
import com.mg.player.data.db.AppDatabase
import com.mg.player.domain.model.ContentType
import com.mg.player.domain.model.FavoriteName
import kotlinx.coroutines.flow.Flow

/** Eigene Reihenfolge der Favoriten: lesen und als absteigende Zeitstempel speichern (neueste oben). */
class FavoriteOrderRepository(private val db: AppDatabase, private val clock: () -> Long = System::currentTimeMillis) {

    fun observe(type: ContentType, playlistId: Long): Flow<List<FavoriteName>> = db.favoriteDao().observeNamed(type.name, playlistId)

    /** [ids] in dieser Reihenfolge speichern – eine Transaktion, damit die Liste nie halb umsortiert ist. */
    suspend fun saveOrder(type: ContentType, ids: List<String>) {
        val top = clock()
        db.withTransaction { ids.forEachIndexed { index, item -> db.favoriteDao().setAddedAt(type.name, item, top - index) } }
    }
}
