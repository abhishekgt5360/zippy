package com.example.data.local

import android.content.Context
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * Validates Room Database migration from Version 2 to Version 3.
 *
 * Requirements:
 * 1. Preserves all watch_history entries.
 * 2. Preserves all watchlist entries.
 * 3. Preserves all episode_progress entries.
 * 4. Creates content_mappings table with compound primary key (tmdbId, mediaType).
 * 5. Creates index on movieboxSubjectId.
 * 6. Non-destructive migration without data loss.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RoomMigrationTest {

    private lateinit var context: Context
    private lateinit var dbFile: File

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext<Context>()
        dbFile = context.getDatabasePath("test_migration.db")
        if (dbFile.exists()) {
            dbFile.delete()
        }
    }

    @After
    fun tearDown() {
        if (dbFile.exists()) {
            dbFile.delete()
        }
    }

    @Test
    fun testMigration_2_to_3_preservesAllExistingDataAndCreatesContentMappings() {
        val helperConfig = androidx.sqlite.db.SupportSQLiteOpenHelper.Configuration.builder(context)
            .name("test_migration.db")
            .callback(object : androidx.sqlite.db.SupportSQLiteOpenHelper.Callback(2) {
                override fun onCreate(db: SupportSQLiteDatabase) {
                    // Create Version 1 tables
                    db.execSQL(
                        """
                        CREATE TABLE IF NOT EXISTS `watch_history` (
                            `subjectId` TEXT NOT NULL PRIMARY KEY,
                            `slug` TEXT NOT NULL,
                            `title` TEXT NOT NULL,
                            `posterUrl` TEXT,
                            `season` INTEGER NOT NULL,
                            `episode` INTEGER NOT NULL,
                            `positionMs` INTEGER NOT NULL,
                            `durationMs` INTEGER NOT NULL,
                            `lastWatchedTimestamp` INTEGER NOT NULL,
                            `type` TEXT
                        )
                        """.trimIndent()
                    )
                    db.execSQL(
                        """
                        CREATE TABLE IF NOT EXISTS `watchlist` (
                            `subjectId` TEXT NOT NULL PRIMARY KEY,
                            `slug` TEXT NOT NULL,
                            `title` TEXT NOT NULL,
                            `posterUrl` TEXT,
                            `rating` TEXT,
                            `year` TEXT,
                            `genre` TEXT,
                            `type` TEXT,
                            `addedTimestamp` INTEGER NOT NULL
                        )
                        """.trimIndent()
                    )
                    // Create Version 2 table (episode_progress)
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

                override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) {
                    // Not needed during setup
                }
            })
            .build()

        val openHelper = FrameworkSQLiteOpenHelperFactory().create(helperConfig)
        val v2Db = openHelper.writableDatabase

        // 1. Seed existing data into Version 2 database
        v2Db.execSQL(
            """
            INSERT INTO `watch_history` (`subjectId`, `slug`, `title`, `posterUrl`, `season`, `episode`, `positionMs`, `durationMs`, `lastWatchedTimestamp`, `type`)
            VALUES ('mb_hist_1', 'inception-2010', 'Inception', 'https://example.com/poster.jpg', 0, 0, 450000, 8800000, 1700000000000, 'movie')
            """.trimIndent()
        )
        v2Db.execSQL(
            """
            INSERT INTO `watchlist` (`subjectId`, `slug`, `title`, `posterUrl`, `rating`, `year`, `genre`, `type`, `addedTimestamp`)
            VALUES ('mb_wl_1', 'breaking-bad-2008', 'Breaking Bad', 'https://example.com/bb.jpg', '9.5', '2008', 'Drama', 'tv', 1700000001000)
            """.trimIndent()
        )
        v2Db.execSQL(
            """
            INSERT INTO `episode_progress` (`subjectId`, `season`, `episode`, `positionMs`, `durationMs`, `updatedAt`, `finished`)
            VALUES ('mb_wl_1', 1, 2, 1200000, 2800000, 1700000002000, 0)
            """.trimIndent()
        )

        // Verify version 2 data exists prior to migration
        val cursorHistoryPre = v2Db.query("SELECT COUNT(*) FROM watch_history")
        cursorHistoryPre.moveToFirst()
        assertEquals(1, cursorHistoryPre.getInt(0))
        cursorHistoryPre.close()

        // 2. Execute MIGRATION_2_3
        MIGRATION_2_3.migrate(v2Db)

        // 3. Verify watch_history preservation
        val cursorHistoryPost = v2Db.query("SELECT * FROM watch_history WHERE subjectId = 'mb_hist_1'")
        assertTrue("watch_history entry must exist after migration", cursorHistoryPost.moveToFirst())
        assertEquals("Inception", cursorHistoryPost.getString(cursorHistoryPost.getColumnIndexOrThrow("title")))
        assertEquals(450000L, cursorHistoryPost.getLong(cursorHistoryPost.getColumnIndexOrThrow("positionMs")))
        cursorHistoryPost.close()

        // 4. Verify watchlist preservation
        val cursorWatchlistPost = v2Db.query("SELECT * FROM watchlist WHERE subjectId = 'mb_wl_1'")
        assertTrue("watchlist entry must exist after migration", cursorWatchlistPost.moveToFirst())
        assertEquals("Breaking Bad", cursorWatchlistPost.getString(cursorWatchlistPost.getColumnIndexOrThrow("title")))
        assertEquals("9.5", cursorWatchlistPost.getString(cursorWatchlistPost.getColumnIndexOrThrow("rating")))
        cursorWatchlistPost.close()

        // 5. Verify episode_progress preservation
        val cursorEpPost = v2Db.query("SELECT * FROM episode_progress WHERE subjectId = 'mb_wl_1' AND season = 1 AND episode = 2")
        assertTrue("episode_progress entry must exist after migration", cursorEpPost.moveToFirst())
        assertEquals(1200000L, cursorEpPost.getLong(cursorEpPost.getColumnIndexOrThrow("positionMs")))
        assertEquals(0, cursorEpPost.getInt(cursorEpPost.getColumnIndexOrThrow("finished")))
        cursorEpPost.close()

        // 6. Verify content_mappings table was created properly and can accept records
        v2Db.execSQL(
            """
            INSERT INTO `content_mappings` (
                `tmdbId`, `mediaType`, `tmdbTitle`, `tmdbOriginalTitle`, `releaseYear`,
                `movieboxSubjectId`, `movieboxDetailPath`, `matchStatus`, `matchConfidence`,
                `matchedAt`, `lastCheckedAt`, `expiresAt`,
                `hasAvailableStreams`, `availabilityCheckedAt`, `availabilityExpiresAt`
            ) VALUES (
                27205, 'movie', 'Inception', 'Inception', '2010',
                '1001', 'inception-2010', 'VERIFIED', 0.99,
                1700000003000, 1700000003000, 1702592003000,
                1, 1700000003000, 1700086403000
            )
            """.trimIndent()
        )

        val cursorMapping = v2Db.query("SELECT * FROM content_mappings WHERE tmdbId = 27205 AND mediaType = 'movie'")
        assertTrue("content_mappings table must exist and hold new records", cursorMapping.moveToFirst())
        assertEquals("1001", cursorMapping.getString(cursorMapping.getColumnIndexOrThrow("movieboxSubjectId")))
        assertEquals("VERIFIED", cursorMapping.getString(cursorMapping.getColumnIndexOrThrow("matchStatus")))
        assertEquals(1, cursorMapping.getInt(cursorMapping.getColumnIndexOrThrow("hasAvailableStreams")))
        cursorMapping.close()

        // 7. Verify index on movieboxSubjectId exists
        val cursorIndex = v2Db.query("PRAGMA index_list('content_mappings')")
        var indexFound = false
        while (cursorIndex.moveToNext()) {
            val name = cursorIndex.getString(cursorIndex.getColumnIndexOrThrow("name"))
            if (name == "index_content_mappings_movieboxSubjectId") {
                indexFound = true
                break
            }
        }
        cursorIndex.close()
        assertTrue("Index 'index_content_mappings_movieboxSubjectId' must be created", indexFound)

        openHelper.close()
    }

    @Test
    fun testMigration_3_to_4_preservesAllExistingDataAndEnablesTmdbWatchlist() {
        val helperConfig = androidx.sqlite.db.SupportSQLiteOpenHelper.Configuration.builder(context)
            .name("test_migration_v4.db")
            .callback(object : androidx.sqlite.db.SupportSQLiteOpenHelper.Callback(3) {
                override fun onCreate(db: SupportSQLiteDatabase) {
                    db.execSQL(
                        """
                        CREATE TABLE IF NOT EXISTS `watch_history` (
                            `subjectId` TEXT NOT NULL PRIMARY KEY,
                            `slug` TEXT NOT NULL,
                            `title` TEXT NOT NULL,
                            `posterUrl` TEXT,
                            `season` INTEGER NOT NULL,
                            `episode` INTEGER NOT NULL,
                            `positionMs` INTEGER NOT NULL,
                            `durationMs` INTEGER NOT NULL,
                            `lastWatchedTimestamp` INTEGER NOT NULL,
                            `type` TEXT
                        )
                        """.trimIndent()
                    )
                    db.execSQL(
                        """
                        CREATE TABLE IF NOT EXISTS `watchlist` (
                            `subjectId` TEXT NOT NULL PRIMARY KEY,
                            `slug` TEXT NOT NULL,
                            `title` TEXT NOT NULL,
                            `posterUrl` TEXT,
                            `rating` TEXT,
                            `year` TEXT,
                            `genre` TEXT,
                            `type` TEXT,
                            `addedTimestamp` INTEGER NOT NULL
                        )
                        """.trimIndent()
                    )
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
                    db.execSQL(
                        """
                        CREATE TABLE IF NOT EXISTS `content_mappings` (
                            `tmdbId` INTEGER NOT NULL,
                            `mediaType` TEXT NOT NULL,
                            `tmdbTitle` TEXT NOT NULL,
                            `tmdbOriginalTitle` TEXT,
                            `releaseYear` TEXT,
                            `movieboxSubjectId` TEXT,
                            `movieboxDetailPath` TEXT,
                            `matchStatus` TEXT NOT NULL,
                            `matchConfidence` REAL NOT NULL,
                            `matchedAt` INTEGER NOT NULL,
                            `lastCheckedAt` INTEGER NOT NULL,
                            `expiresAt` INTEGER NOT NULL,
                            `hasAvailableStreams` INTEGER NOT NULL,
                            `availabilityCheckedAt` INTEGER,
                            `availabilityExpiresAt` INTEGER,
                            PRIMARY KEY(`tmdbId`, `mediaType`)
                        )
                        """.trimIndent()
                    )
                }

                override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) {}
            })
            .build()

        val openHelper = FrameworkSQLiteOpenHelperFactory().create(helperConfig)
        val v3Db = openHelper.writableDatabase

        // 1. Seed existing Version 3 data
        v3Db.execSQL(
            """
            INSERT INTO `watch_history` (`subjectId`, `slug`, `title`, `posterUrl`, `season`, `episode`, `positionMs`, `durationMs`, `lastWatchedTimestamp`, `type`)
            VALUES ('mb_hist_old', 'interstellar-2014', 'Interstellar', 'https://example.com/interstellar.jpg', 0, 0, 900000, 10000000, 1700000000000, 'movie')
            """.trimIndent()
        )
        v3Db.execSQL(
            """
            INSERT INTO `watchlist` (`subjectId`, `slug`, `title`, `posterUrl`, `rating`, `year`, `genre`, `type`, `addedTimestamp`)
            VALUES ('mb_wl_old', 'dark-2017', 'Dark', 'https://example.com/dark.jpg', '8.7', '2017', 'Sci-Fi', 'tv', 1700000001000)
            """.trimIndent()
        )
        v3Db.execSQL(
            """
            INSERT INTO `content_mappings` (
                `tmdbId`, `mediaType`, `tmdbTitle`, `matchStatus`, `matchConfidence`,
                `matchedAt`, `lastCheckedAt`, `expiresAt`, `hasAvailableStreams`
            ) VALUES (
                157336, 'movie', 'Interstellar', 'VERIFIED', 0.99,
                1700000000000, 1700000000000, 1702592000000, 1
            )
            """.trimIndent()
        )
        v3Db.execSQL(
            """
            INSERT INTO `episode_progress` (`subjectId`, `season`, `episode`, `positionMs`, `durationMs`, `updatedAt`, `finished`)
            VALUES ('mb_wl_old', 1, 3, 1500000, 3000000, 1700000002000, 0)
            """.trimIndent()
        )

        // 2. Execute MIGRATION_3_4
        MIGRATION_3_4.migrate(v3Db)

        // 3. Verify existing MovieBox watchlist record is preserved
        val cursorWl = v3Db.query("SELECT * FROM watchlist WHERE id = 'mb_wl_old'")
        assertTrue("Migrated watchlist item must exist with id = old subjectId", cursorWl.moveToFirst())
        assertEquals("mb_wl_old", cursorWl.getString(cursorWl.getColumnIndexOrThrow("subjectId")))
        assertEquals("Dark", cursorWl.getString(cursorWl.getColumnIndexOrThrow("title")))
        assertEquals("dark-2017", cursorWl.getString(cursorWl.getColumnIndexOrThrow("slug")))
        assertNull(cursorWl.getString(cursorWl.getColumnIndexOrThrow("tmdbId")))
        cursorWl.close()

        // 4. Verify existing watch_history record is preserved and new columns exist
        val cursorHist = v3Db.query("SELECT * FROM watch_history WHERE subjectId = 'mb_hist_old'")
        assertTrue("Migrated watch_history item must exist", cursorHist.moveToFirst())
        assertEquals("Interstellar", cursorHist.getString(cursorHist.getColumnIndexOrThrow("title")))
        assertNull(cursorHist.getString(cursorHist.getColumnIndexOrThrow("tmdbId")))
        assertNull(cursorHist.getString(cursorHist.getColumnIndexOrThrow("tmdbMediaType")))
        cursorHist.close()

        // 5. Verify existing episode_progress record is preserved
        val cursorEp = v3Db.query("SELECT * FROM episode_progress WHERE subjectId = 'mb_wl_old' AND season = 1 AND episode = 3")
        assertTrue("Migrated episode_progress must exist", cursorEp.moveToFirst())
        assertEquals(1500000L, cursorEp.getLong(cursorEp.getColumnIndexOrThrow("positionMs")))
        cursorEp.close()

        // 6. Verify insertion of new TMDB items into watchlist (id = "tmdb_movie_550", subjectId = NULL)
        v3Db.execSQL(
            """
            INSERT INTO `watchlist` (
                `id`, `subjectId`, `tmdbId`, `tmdbMediaType`, `tmdbOriginalTitle`, `tmdbCountry`, `tmdbOriginalLanguage`,
                `slug`, `title`, `posterUrl`, `rating`, `year`, `genre`, `type`, `addedTimestamp`
            ) VALUES (
                'tmdb_movie_550', NULL, 550, 'movie', 'Fight Club', 'US', 'en',
                '', 'Fight Club', 'https://image.tmdb.org/t/p/w500/fc.jpg', '8.4', '1999', 'Drama', 'movie', 1700000005000
            )
            """.trimIndent()
        )

        val cursorTmdbWl = v3Db.query("SELECT * FROM watchlist WHERE tmdbId = 550 AND tmdbMediaType = 'movie'")
        assertTrue("TMDB watchlist record must be queryable by tmdbId", cursorTmdbWl.moveToFirst())
        assertEquals("tmdb_movie_550", cursorTmdbWl.getString(cursorTmdbWl.getColumnIndexOrThrow("id")))
        assertNull(cursorTmdbWl.getString(cursorTmdbWl.getColumnIndexOrThrow("subjectId")))
        assertEquals("Fight Club", cursorTmdbWl.getString(cursorTmdbWl.getColumnIndexOrThrow("title")))
        cursorTmdbWl.close()

        // 7. Verify indices on watchlist table
        val cursorIndex = v3Db.query("PRAGMA index_list('watchlist')")
        val indexNames = mutableSetOf<String>()
        while (cursorIndex.moveToNext()) {
            indexNames.add(cursorIndex.getString(cursorIndex.getColumnIndexOrThrow("name")))
        }
        cursorIndex.close()

        assertTrue("Index 'index_watchlist_tmdbId' must exist", indexNames.contains("index_watchlist_tmdbId"))
        assertTrue("Index 'index_watchlist_subjectId' must exist", indexNames.contains("index_watchlist_subjectId"))

        // 8. Verify content_mappings is still intact
        val cursorMapping = v3Db.query("SELECT COUNT(*) FROM content_mappings")
        cursorMapping.moveToFirst()
        assertEquals(1, cursorMapping.getInt(0))
        cursorMapping.close()

        openHelper.close()
    }

    @Test
    fun testMigration_1_to_4_fullUpgradePath() {
        val helperConfig = androidx.sqlite.db.SupportSQLiteOpenHelper.Configuration.builder(context)
            .name("test_migration_v1_to_v4.db")
            .callback(object : androidx.sqlite.db.SupportSQLiteOpenHelper.Callback(1) {
                override fun onCreate(db: SupportSQLiteDatabase) {
                    db.execSQL(
                        """
                        CREATE TABLE IF NOT EXISTS `watch_history` (
                            `subjectId` TEXT NOT NULL PRIMARY KEY,
                            `slug` TEXT NOT NULL,
                            `title` TEXT NOT NULL,
                            `posterUrl` TEXT,
                            `season` INTEGER NOT NULL,
                            `episode` INTEGER NOT NULL,
                            `positionMs` INTEGER NOT NULL,
                            `durationMs` INTEGER NOT NULL,
                            `lastWatchedTimestamp` INTEGER NOT NULL,
                            `type` TEXT
                        )
                        """.trimIndent()
                    )
                    db.execSQL(
                        """
                        CREATE TABLE IF NOT EXISTS `watchlist` (
                            `subjectId` TEXT NOT NULL PRIMARY KEY,
                            `slug` TEXT NOT NULL,
                            `title` TEXT NOT NULL,
                            `posterUrl` TEXT,
                            `rating` TEXT,
                            `year` TEXT,
                            `genre` TEXT,
                            `type` TEXT,
                            `addedTimestamp` INTEGER NOT NULL
                        )
                        """.trimIndent()
                    )
                }

                override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) {}
            })
            .build()

        val openHelper = FrameworkSQLiteOpenHelperFactory().create(helperConfig)
        val v1Db = openHelper.writableDatabase

        // Seed v1 historical data
        v1Db.execSQL(
            """
            INSERT INTO `watch_history` (`subjectId`, `slug`, `title`, `posterUrl`, `season`, `episode`, `positionMs`, `durationMs`, `lastWatchedTimestamp`, `type`)
            VALUES ('v1_hist_1', 'the-matrix-1999', 'The Matrix', 'https://example.com/matrix.jpg', 0, 0, 500000, 8160000, 1690000000000, 'movie')
            """.trimIndent()
        )
        v1Db.execSQL(
            """
            INSERT INTO `watchlist` (`subjectId`, `slug`, `title`, `posterUrl`, `rating`, `year`, `genre`, `type`, `addedTimestamp`)
            VALUES ('v1_wl_1', 'the-wire-2002', 'The Wire', 'https://example.com/wire.jpg', '9.3', '2002', 'Crime', 'tv', 1690000001000)
            """.trimIndent()
        )

        // Sequentially execute all migrations from 1 to 4
        MIGRATION_1_2.migrate(v1Db)
        MIGRATION_2_3.migrate(v1Db)
        MIGRATION_3_4.migrate(v1Db)

        // Verify v1 historical watch_history survived
        val cursorHist = v1Db.query("SELECT * FROM watch_history WHERE subjectId = 'v1_hist_1'")
        assertTrue("v1 watch_history item must survive full migration chain", cursorHist.moveToFirst())
        assertEquals("The Matrix", cursorHist.getString(cursorHist.getColumnIndexOrThrow("title")))
        assertEquals(500000L, cursorHist.getLong(cursorHist.getColumnIndexOrThrow("positionMs")))
        assertNull(cursorHist.getString(cursorHist.getColumnIndexOrThrow("tmdbId")))
        cursorHist.close()

        // Verify v1 historical watchlist survived
        val cursorWl = v1Db.query("SELECT * FROM watchlist WHERE id = 'v1_wl_1'")
        assertTrue("v1 watchlist item must survive with id = old subjectId", cursorWl.moveToFirst())
        assertEquals("The Wire", cursorWl.getString(cursorWl.getColumnIndexOrThrow("title")))
        assertEquals("v1_wl_1", cursorWl.getString(cursorWl.getColumnIndexOrThrow("subjectId")))
        assertNull(cursorWl.getString(cursorWl.getColumnIndexOrThrow("tmdbId")))
        cursorWl.close()

        // Verify newly added TMDB watchlist item can coexist
        v1Db.execSQL(
            """
            INSERT INTO `watchlist` (
                `id`, `subjectId`, `tmdbId`, `tmdbMediaType`, `tmdbOriginalTitle`, `tmdbCountry`, `tmdbOriginalLanguage`,
                `slug`, `title`, `posterUrl`, `rating`, `year`, `genre`, `type`, `addedTimestamp`
            ) VALUES (
                'tmdb_movie_603', NULL, 603, 'movie', 'The Matrix', 'US', 'en',
                '', 'The Matrix', 'https://image.tmdb.org/t/p/w500/m.jpg', '8.7', '1999', 'Sci-Fi', 'movie', 1700000000000
            )
            """.trimIndent()
        )
        val cursorTmdb = v1Db.query("SELECT * FROM watchlist WHERE tmdbId = 603")
        assertTrue("TMDB item coexists in migrated database", cursorTmdb.moveToFirst())
        assertEquals("tmdb_movie_603", cursorTmdb.getString(cursorTmdb.getColumnIndexOrThrow("id")))
        cursorTmdb.close()

        // Verify total watchlist count is 2 (1 MovieBox + 1 TMDB)
        val cursorCount = v1Db.query("SELECT COUNT(*) FROM watchlist")
        cursorCount.moveToFirst()
        assertEquals(2, cursorCount.getInt(0))
        cursorCount.close()

        openHelper.close()
    }
}
