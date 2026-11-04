package com.mg.player.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import com.mg.player.data.db.dao.CategoryDao
import com.mg.player.data.db.dao.CategoryPrefDao
import com.mg.player.data.db.dao.ChannelDao
import com.mg.player.data.db.dao.ChannelFlagsDao
import com.mg.player.data.db.dao.CombinedDao
import com.mg.player.data.db.dao.EpgDao
import com.mg.player.data.db.dao.FavoriteDao
import com.mg.player.data.db.dao.IntroProfileDao
import com.mg.player.data.db.dao.PlaylistDao
import com.mg.player.data.db.dao.ProgressDao
import com.mg.player.data.db.dao.SearchDao
import com.mg.player.data.db.dao.SeriesDao
import com.mg.player.data.db.dao.SportDao
import com.mg.player.data.db.dao.TmdbDao
import com.mg.player.data.db.dao.VodDao
import com.mg.player.data.db.entity.CategoryEntity
import com.mg.player.data.db.entity.CategoryPrefEntity
import com.mg.player.data.db.entity.ChannelEntity
import com.mg.player.data.db.entity.ChannelFlagsEntity
import com.mg.player.data.db.entity.CombinedLinkEntity
import com.mg.player.data.db.entity.CombinedRuleEntity
import com.mg.player.data.db.entity.EpgActiveEntity
import com.mg.player.data.db.entity.EpgProgramEntity
import com.mg.player.data.db.entity.EpisodeEntity
import com.mg.player.data.db.entity.FavoriteEntity
import com.mg.player.data.db.entity.IntroProfileEntity
import com.mg.player.data.db.entity.MovieEntity
import com.mg.player.data.db.entity.PlaylistEntity
import com.mg.player.data.db.entity.ProgressEntity
import com.mg.player.data.db.entity.SearchIndexEntity
import com.mg.player.data.db.entity.SeriesEntity
import com.mg.player.data.db.entity.SportEventChannelEntity
import com.mg.player.data.db.entity.SportEventEntity
import com.mg.player.data.db.entity.TmdbCacheEntity

@Database(
    entities = [
        PlaylistEntity::class,
        CategoryEntity::class,
        ChannelEntity::class,
        MovieEntity::class,
        SeriesEntity::class,
        EpisodeEntity::class,
        EpgProgramEntity::class,
        EpgActiveEntity::class,
        ProgressEntity::class,
        FavoriteEntity::class,
        IntroProfileEntity::class,
        CategoryPrefEntity::class,
        TmdbCacheEntity::class,
        SearchIndexEntity::class,
        ChannelFlagsEntity::class,
        SportEventEntity::class,
        SportEventChannelEntity::class,
        CombinedLinkEntity::class,
        CombinedRuleEntity::class,
    ],
    version = AppDatabase.VERSION,
    // Schemas liegen unter app/schemas (je Version eine Datei) – so lassen sich Migrationen gegen das echte Schema prüfen.
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun playlistDao(): PlaylistDao
    abstract fun categoryDao(): CategoryDao
    abstract fun categoryPrefDao(): CategoryPrefDao
    abstract fun channelDao(): ChannelDao
    abstract fun vodDao(): VodDao
    abstract fun seriesDao(): SeriesDao
    abstract fun epgDao(): EpgDao
    abstract fun progressDao(): ProgressDao
    abstract fun favoriteDao(): FavoriteDao
    abstract fun introProfileDao(): IntroProfileDao
    abstract fun tmdbDao(): TmdbDao
    abstract fun searchDao(): SearchDao
    abstract fun channelFlagsDao(): ChannelFlagsDao
    abstract fun sportDao(): SportDao
    abstract fun combinedDao(): CombinedDao

    companion object {
        /** Aktuelle Schema-Version. Für jede Erhöhung braucht es eine Migration in [Migrations.ALL]. */
        const val VERSION = 13

        fun create(context: Context): AppDatabase = Room.databaseBuilder(context, AppDatabase::class.java, "player.db")
            .addMigrations(*Migrations.ALL)
            // Nur die allererste Version (vor jeder Migration) darf noch verworfen werden. Fehlt künftig eine
            // Migration, schlägt der Test MigrationChainTest an – statt still Zugänge und Favoriten zu löschen.
            .fallbackToDestructiveMigrationFrom(true, 1)
            .fallbackToDestructiveMigrationOnDowngrade(true)
            .setJournalMode(RoomDatabase.JournalMode.WRITE_AHEAD_LOGGING)
            .build()
    }
}
