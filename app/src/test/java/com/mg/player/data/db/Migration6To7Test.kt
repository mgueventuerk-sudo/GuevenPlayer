package com.mg.player.data.db

import android.content.Context
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.mg.player.data.db.entity.CategoryEntity
import com.mg.player.data.repository.CategoryRepositoryImpl
import com.mg.player.domain.model.ContentType
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** 3.2 (Datenbank 6) auf 3.3 (7): Spalte für die eigene Reihenfolge der Kategorien; vorhandene Einstellungen bleiben. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class Migration6To7Test {

    @Test
    fun upgradeKeepsPrefsAndAllowsOwnOrder() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "migration-6-7.db"
        context.deleteDatabase(name)
        Room.databaseBuilder(context, AppDatabase::class.java, name).build().apply {
            categoryDao().insertAll(listOf("A", "B", "C").mapIndexed { i, n -> CategoryEntity("1:L:$n", 1, "LIVE", n, n, i) })
            openHelper.writableDatabase.apply {
                downgradeCategoryPrefs(this)
                execSQL("INSERT INTO category_prefs (categoryId, playlistId, hidden, locked, pinned) VALUES ('1:L:B', 1, 0, 1, 0)")
                version = 6
            }
            close()
        }
        val db = Room.databaseBuilder(context, AppDatabase::class.java, name).addMigrations(*Migrations.ALL).build()
        try {
            assertEquals(AppDatabase.VERSION, db.openHelper.readableDatabase.version)
            val before = db.categoryPrefDao().get("1:L:B")!!
            assertTrue(before.locked)
            assertNull(before.position)
            val repo = CategoryRepositoryImpl(db)
            assertEquals(listOf("A", "B", "C"), repo.observeAll(1, ContentType.LIVE).first().map { it.name })
            repo.saveOrder(1, listOf("1:L:C", "1:L:A", "1:L:B"))
            assertEquals(listOf("C", "A", "B"), repo.observeAll(1, ContentType.LIVE).first().map { it.name })
            assertTrue("Sperre bleibt beim Sortieren", db.categoryPrefDao().get("1:L:B")!!.locked)
            repo.resetOrder(1, listOf("1:L:A", "1:L:B", "1:L:C"))
            assertEquals(listOf("A", "B", "C"), repo.observeAll(1, ContentType.LIVE).first().map { it.name })
        } finally {
            db.close()
            context.deleteDatabase(name)
        }
    }

    companion object {
        /** category_prefs wie in Version 6 (ohne Spalte position). */
        fun downgradeCategoryPrefs(db: SupportSQLiteDatabase) {
            db.execSQL("DROP TABLE category_prefs")
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `category_prefs` (`categoryId` TEXT NOT NULL, `playlistId` INTEGER NOT NULL, " +
                    "`hidden` INTEGER NOT NULL, `locked` INTEGER NOT NULL, `pinned` INTEGER NOT NULL, PRIMARY KEY(`categoryId`))",
            )
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_category_prefs_playlistId` ON `category_prefs` (`playlistId`)")
        }
    }
}
