package com.example.data.local

import androidx.room.*
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "watch_history")
data class WatchHistoryEntity(
    @PrimaryKey
    val subjectId: String,
    val slug: String,
    val title: String,
    val posterUrl: String?,
    val season: Int,
    val episode: Int,
    val positionMs: Long,
    val durationMs: Long,
    val lastWatchedTimestamp: Long = System.currentTimeMillis(),
    val type: String? = null,
    val tmdbId: Long? = null,
    val tmdbMediaType: String? = null
) {
    constructor(
        subjectId: String,
        slug: String,
        title: String,
        posterUrl: String?,
        season: Int,
        episode: Int,
        positionMs: Long,
        durationMs: Long,
        lastWatchedTimestamp: Long = System.currentTimeMillis(),
        type: String? = null
    ) : this(
        subjectId = subjectId,
        slug = slug,
        title = title,
        posterUrl = posterUrl,
        season = season,
        episode = episode,
        positionMs = positionMs,
        durationMs = durationMs,
        lastWatchedTimestamp = lastWatchedTimestamp,
        type = type,
        tmdbId = null,
        tmdbMediaType = null
    )
}

@Entity(
    tableName = "watchlist",
    indices = [
        Index(value = ["tmdbId"]),
        Index(value = ["subjectId"])
    ]
)
data class WatchlistEntity(
    @PrimaryKey
    val id: String,
    val subjectId: String? = null,
    val slug: String,
    val title: String,
    val posterUrl: String? = null,
    val rating: String? = null,
    val year: String? = null,
    val genre: String? = null,
    val type: String? = null,
    val addedTimestamp: Long = System.currentTimeMillis(),
    val tmdbId: Long? = null,
    val tmdbMediaType: String? = null,
    val tmdbOriginalTitle: String? = null,
    val tmdbCountry: String? = null,
    val tmdbOriginalLanguage: String? = null
) {
    constructor(
        subjectId: String,
        slug: String,
        title: String,
        posterUrl: String?,
        rating: String?,
        year: String?,
        genre: String?,
        type: String?,
        addedTimestamp: Long = System.currentTimeMillis()
    ) : this(
        id = subjectId,
        subjectId = subjectId,
        slug = slug,
        title = title,
        posterUrl = posterUrl,
        rating = rating,
        year = year,
        genre = genre,
        type = type,
        addedTimestamp = addedTimestamp,
        tmdbId = null,
        tmdbMediaType = null,
        tmdbOriginalTitle = null,
        tmdbCountry = null,
        tmdbOriginalLanguage = null
    )

    val isFromTmdb: Boolean
        get() = tmdbId != null && tmdbId > 0L
}

@Entity(tableName = "episode_progress", primaryKeys = ["subjectId", "season", "episode"])
data class EpisodeProgressEntity(
    val subjectId: String,
    val season: Int,
    val episode: Int,
    val positionMs: Long,
    val durationMs: Long,
    val updatedAt: Long = System.currentTimeMillis(),
    val finished: Boolean = false
)

@Dao
interface WatchDao {
    @Query("SELECT * FROM watch_history ORDER BY lastWatchedTimestamp DESC")
    fun getAllHistory(): Flow<List<WatchHistoryEntity>>

    @Query("SELECT * FROM watch_history WHERE subjectId = :subjectId LIMIT 1")
    suspend fun getHistory(subjectId: String): WatchHistoryEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertHistory(entity: WatchHistoryEntity)

    @Query("DELETE FROM watch_history WHERE subjectId = :subjectId")
    suspend fun deleteHistory(subjectId: String)

    @Query("DELETE FROM watch_history")
    suspend fun clearHistory()

    @Query("SELECT * FROM watchlist ORDER BY addedTimestamp DESC")
    fun getAllWatchlist(): Flow<List<WatchlistEntity>>

    @Query("SELECT EXISTS(SELECT 1 FROM watchlist WHERE id = :id OR subjectId = :id)")
    fun isWatchlisted(id: String): Flow<Boolean>

    @Query("SELECT EXISTS(SELECT 1 FROM watchlist WHERE (tmdbId = :tmdbId AND tmdbMediaType = :mediaType) OR id = :id OR subjectId = :id)")
    fun isTmdbWatchlisted(tmdbId: Long, mediaType: String, id: String = "tmdb_${mediaType}_$tmdbId"): Flow<Boolean>

    @Query("SELECT * FROM watchlist WHERE id = :id OR subjectId = :id LIMIT 1")
    suspend fun getWatchlistItem(id: String): WatchlistEntity?

    @Query("SELECT * FROM watchlist WHERE tmdbId = :tmdbId AND tmdbMediaType = :mediaType LIMIT 1")
    suspend fun getTmdbWatchlistItem(tmdbId: Long, mediaType: String): WatchlistEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun addToWatchlist(entity: WatchlistEntity)

    @Query("DELETE FROM watchlist WHERE id = :id OR subjectId = :id")
    suspend fun removeFromWatchlist(id: String)

    @Query("DELETE FROM watchlist WHERE tmdbId = :tmdbId AND tmdbMediaType = :mediaType")
    suspend fun removeTmdbFromWatchlist(tmdbId: Long, mediaType: String)

    // Episode Progress
    @Query("SELECT * FROM episode_progress WHERE subjectId = :subjectId AND season = :season AND episode = :episode LIMIT 1")
    suspend fun getEpisodeProgress(subjectId: String, season: Int, episode: Int): EpisodeProgressEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertEpisodeProgress(entity: EpisodeProgressEntity)

    @Query("DELETE FROM episode_progress WHERE subjectId = :subjectId AND season = :season AND episode = :episode")
    suspend fun deleteEpisodeProgress(subjectId: String, season: Int, episode: Int)

    @Query("DELETE FROM episode_progress WHERE subjectId = :subjectId")
    suspend fun deleteEpisodeProgressForSubject(subjectId: String)

    @Query("DELETE FROM episode_progress")
    suspend fun clearEpisodeProgress()

    // Content Mappings (TMDB to MovieBox Provider)
    @Query("SELECT * FROM content_mappings WHERE tmdbId = :tmdbId AND mediaType = :mediaType LIMIT 1")
    suspend fun getMapping(tmdbId: Long, mediaType: String): ContentMappingEntity?

    @Query("SELECT * FROM content_mappings WHERE tmdbId = :tmdbId AND mediaType = :mediaType LIMIT 1")
    fun getMappingFlow(tmdbId: Long, mediaType: String): Flow<ContentMappingEntity?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertMapping(entity: ContentMappingEntity)

    @Query("SELECT * FROM content_mappings WHERE movieboxSubjectId = :subjectId")
    suspend fun getMappingsByMovieboxId(subjectId: String): List<ContentMappingEntity>

    @Query("UPDATE content_mappings SET matchStatus = :status, lastCheckedAt = :updatedAt WHERE tmdbId = :tmdbId AND mediaType = :mediaType")
    suspend fun updateMappingStatus(tmdbId: Long, mediaType: String, status: String, updatedAt: Long)

    @Query("UPDATE content_mappings SET hasAvailableStreams = :hasStreams, availabilityCheckedAt = :checkedAt, availabilityExpiresAt = :expiresAt WHERE tmdbId = :tmdbId AND mediaType = :mediaType")
    suspend fun updateAvailability(tmdbId: Long, mediaType: String, hasStreams: Boolean, checkedAt: Long, expiresAt: Long)

    @Query("SELECT * FROM content_mappings WHERE expiresAt < :now")
    suspend fun getExpiredMappings(now: Long): List<ContentMappingEntity>

    @Query("DELETE FROM content_mappings WHERE expiresAt < :now")
    suspend fun deleteExpiredMappings(now: Long)
}

val MIGRATION_1_2 = object : androidx.room.migration.Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `episode_progress` (
                `subjectId` TEXT NOT NULL,
                `season` INTEGER NOT NULL,
                `episode` INTEGER NOT NULL,
                `positionMs` INTEGER NOT NULL,
                `durationMs` INTEGER NOT NULL,
                `updatedAt` INTEGER NOT NULL,
                `finished` INTEGER NOT NULL,
                PRIMARY KEY(`subjectId`, `season`, `episode`)
            )
            """.trimIndent()
        )
    }
}

val MIGRATION_2_3 = object : androidx.room.migration.Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `content_mappings` (
                `tmdbId` INTEGER NOT NULL,
                `mediaType` TEXT NOT NULL,
                `tmdbTitle` TEXT NOT NULL,
                `tmdbOriginalTitle` TEXT NOT NULL,
                `releaseYear` TEXT,
                `movieboxSubjectId` TEXT,
                `movieboxDetailPath` TEXT,
                `matchStatus` TEXT NOT NULL,
                `matchConfidence` REAL NOT NULL,
                `matchedAt` INTEGER NOT NULL,
                `lastCheckedAt` INTEGER NOT NULL,
                `expiresAt` INTEGER NOT NULL,
                `hasAvailableStreams` INTEGER NOT NULL DEFAULT 0,
                `availabilityCheckedAt` INTEGER NOT NULL DEFAULT 0,
                `availabilityExpiresAt` INTEGER NOT NULL DEFAULT 0,
                PRIMARY KEY(`tmdbId`, `mediaType`)
            )
            """.trimIndent()
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_content_mappings_movieboxSubjectId` ON `content_mappings` (`movieboxSubjectId`)"
        )
    }
}

val MIGRATION_3_4 = object : androidx.room.migration.Migration(3, 4) {
    override fun migrate(db: SupportSQLiteDatabase) {
        // 1. Recreate watchlist table to support id primary key and TMDB columns
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `watchlist_new` (
                `id` TEXT NOT NULL,
                `subjectId` TEXT,
                `slug` TEXT NOT NULL,
                `title` TEXT NOT NULL,
                `posterUrl` TEXT,
                `rating` TEXT,
                `year` TEXT,
                `genre` TEXT,
                `type` TEXT,
                `addedTimestamp` INTEGER NOT NULL,
                `tmdbId` INTEGER,
                `tmdbMediaType` TEXT,
                `tmdbOriginalTitle` TEXT,
                `tmdbCountry` TEXT,
                `tmdbOriginalLanguage` TEXT,
                PRIMARY KEY(`id`)
            )
            """.trimIndent()
        )
        // 2. Safely migrate existing MovieBox watchlist records
        db.execSQL(
            """
            INSERT INTO `watchlist_new` (
                `id`, `subjectId`, `slug`, `title`, `posterUrl`, `rating`, `year`, `genre`, `type`, `addedTimestamp`,
                `tmdbId`, `tmdbMediaType`, `tmdbOriginalTitle`, `tmdbCountry`, `tmdbOriginalLanguage`
            )
            SELECT 
                `subjectId`, `subjectId`, `slug`, `title`, `posterUrl`, `rating`, `year`, `genre`, `type`, `addedTimestamp`,
                NULL, NULL, NULL, NULL, NULL
            FROM `watchlist`
            """.trimIndent()
        )
        // 3. Drop old table and rename new table
        db.execSQL("DROP TABLE `watchlist`")
        db.execSQL("ALTER TABLE `watchlist_new` RENAME TO `watchlist`")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_watchlist_tmdbId` ON `watchlist` (`tmdbId`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_watchlist_subjectId` ON `watchlist` (`subjectId`)")

        // 4. Add tmdbId and tmdbMediaType columns to watch_history
        db.execSQL("ALTER TABLE `watch_history` ADD COLUMN `tmdbId` INTEGER DEFAULT NULL")
        db.execSQL("ALTER TABLE `watch_history` ADD COLUMN `tmdbMediaType` TEXT DEFAULT NULL")
    }
}

@Database(
    entities = [
        WatchHistoryEntity::class,
        WatchlistEntity::class,
        EpisodeProgressEntity::class,
        ContentMappingEntity::class
    ],
    version = 4,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun watchDao(): WatchDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getInstance(context: android.content.Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "cinepulse.db"
                ).addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4).build()
                INSTANCE = instance
                instance
            }
        }
    }
}
