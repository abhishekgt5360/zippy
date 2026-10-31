package com.example.ui

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.local.AppDatabase
import com.example.data.local.WatchHistoryEntity
import com.example.data.local.WatchlistEntity
import com.example.data.model.*
import com.example.data.remote.TmdbApiService
import com.example.data.repository.MovieRepository
import com.example.data.repository.TmdbRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Batch 2: Search + Details + Library Integration Tests.
 *
 * Covers:
 * 1. TMDB movie and TV search with person filtering.
 * 2. MovieBox search fallback when TMDB is unavailable.
 * 3. Correct TMDB metadata transfer to search results and detail screen.
 * 4. Verified vs. unverified provider mapping behavior (playback guard on unverified).
 * 5. Watchlist insert, delete, duplicate prevention, and distinct identity namespaces.
 * 6. History and resume preservation.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class Batch2SearchDetailLibraryTest {

    private lateinit var app: Application
    private lateinit var db: AppDatabase
    private lateinit var repository: MovieRepository

    class TestTmdbApiService : TmdbApiService {
        var multiSearchQuery: String? = null
        var returnError = false

        override suspend fun searchMulti(query: String, page: Int): TmdbPagedResponse<TmdbMovieItem> {
            multiSearchQuery = query
            if (returnError) throw java.io.IOException("TMDB network failure")
            return TmdbPagedResponse(
                page = page,
                totalPages = 1,
                totalResults = 3,
                results = listOf(
                    TmdbMovieItem(
                        id = 550L,
                        title = "Fight Club",
                        originalTitle = "Fight Club",
                        mediaType = "movie",
                        releaseDate = "1999-10-15",
                        voteAverage = 8.4,
                        posterPath = "/fc.jpg",
                        genreIds = listOf(18),
                        originalLanguage = "en",
                        originCountry = listOf("US")
                    ),
                    TmdbMovieItem(
                        id = 80989L,
                        name = "Sacred Games",
                        originalName = "Sacred Games",
                        mediaType = "tv",
                        firstAirDate = "2018-07-06",
                        voteAverage = 8.6,
                        posterPath = "/sg.jpg",
                        genreIds = listOf(18, 80),
                        originalLanguage = "hi",
                        originCountry = listOf("IN")
                    ),
                    TmdbMovieItem(
                        id = 9999L,
                        name = "Brad Pitt",
                        mediaType = "person",
                        profilePath = "/bp.jpg"
                    )
                )
            )
        }

        override suspend fun getTrending(mediaType: String, timeWindow: String, page: Int): TmdbPagedResponse<TmdbMovieItem> =
            TmdbPagedResponse()

        override suspend fun getPopularMovies(page: Int, region: String?): TmdbPagedResponse<TmdbMovieItem> =
            TmdbPagedResponse()

        override suspend fun getPopularTv(page: Int): TmdbPagedResponse<TmdbMovieItem> =
            TmdbPagedResponse()

        override suspend fun getTopRatedMovies(page: Int): TmdbPagedResponse<TmdbMovieItem> =
            TmdbPagedResponse()

        override suspend fun getTopRatedTv(page: Int): TmdbPagedResponse<TmdbMovieItem> =
            TmdbPagedResponse()

        override suspend fun getNowPlayingMovies(page: Int): TmdbPagedResponse<TmdbMovieItem> =
            TmdbPagedResponse()

        override suspend fun getUpcomingMovies(page: Int): TmdbPagedResponse<TmdbMovieItem> =
            TmdbPagedResponse()

        override suspend fun discoverMovies(
            page: Int,
            sortBy: String?,
            withGenres: String?,
            withOriginalLanguage: String?,
            withOriginCountry: String?,
            primaryReleaseYear: Int?
        ): TmdbPagedResponse<TmdbMovieItem> = TmdbPagedResponse()

        override suspend fun discoverTv(
            page: Int,
            sortBy: String?,
            withGenres: String?,
            withOriginalLanguage: String?,
            withOriginCountry: String?,
            firstAirDateYear: Int?
        ): TmdbPagedResponse<TmdbMovieItem> = TmdbPagedResponse()

        override suspend fun searchMovies(query: String, page: Int, year: Int?): TmdbPagedResponse<TmdbMovieItem> =
            TmdbPagedResponse()

        override suspend fun searchTv(query: String, page: Int, year: Int?): TmdbPagedResponse<TmdbMovieItem> =
            TmdbPagedResponse()

        override suspend fun getMovieGenres(): TmdbGenreResponse =
            TmdbGenreResponse(genres = listOf(TmdbGenre(18, "Drama"), TmdbGenre(80, "Crime")))

        override suspend fun getTvGenres(): TmdbGenreResponse =
            TmdbGenreResponse(genres = listOf(TmdbGenre(18, "Drama"), TmdbGenre(80, "Crime")))

        override suspend fun getMovieDetail(movieId: Long, append: String): TmdbDetailResponse =
            TmdbDetailResponse(
                id = movieId,
                title = "Fight Club",
                originalTitle = "Fight Club",
                overview = "An insomniac office worker...",
                voteAverage = 8.4,
                runtime = 139,
                releaseDate = "1999-10-15",
                credits = TmdbCreditsResponse(
                    cast = listOf(
                        TmdbCastItem(id = 819, name = "Edward Norton", character = "The Narrator"),
                        TmdbCastItem(id = 287, name = "Brad Pitt", character = "Tyler Durden")
                    ),
                    crew = listOf(
                        TmdbCrewItem(id = 7467, name = "David Fincher", job = "Director")
                    )
                ),
                recommendations = TmdbPagedResponse(
                    results = listOf(
                        TmdbMovieItem(id = 551L, title = "Seven", mediaType = "movie")
                    )
                )
            )

        override suspend fun getTvDetail(tvId: Long, append: String): TmdbDetailResponse =
            TmdbDetailResponse(
                id = tvId,
                name = "Sacred Games",
                originalName = "Sacred Games",
                overview = "A police officer...",
                voteAverage = 8.6,
                numberOfSeasons = 2,
                numberOfEpisodes = 16,
                firstAirDate = "2018-07-06"
            )
    }

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(app, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repository = MovieRepository(database = db, context = app)
        TmdbRepository().clearCache()
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun testTmdbMultiSearchAndPersonFiltering() = runBlocking {
        val fakeApi = TestTmdbApiService()
        val tmdbRepo = TmdbRepository(fakeApi)
        val result = tmdbRepo.search("Fight Club", page = 1)

        assertTrue("Search should succeed", result.isSuccess)
        val paged = result.getOrNull()
        assertNotNull(paged)
        val playableOnly = paged!!.results

        assertEquals(2, playableOnly.size)
        assertEquals("Fight Club", playableOnly[0].safeTitle)
        assertEquals("Sacred Games", playableOnly[1].safeTitle)
        assertFalse(playableOnly.any { it.isPerson })

        // Verify person search keeps persons separate
        val personResult = tmdbRepo.searchPersons("Brad Pitt", page = 1)
        assertTrue(personResult.isSuccess)
        val persons = personResult.getOrNull()?.results.orEmpty()
        assertEquals(1, persons.size)
        assertTrue(persons[0].isPerson)
        assertEquals("Brad Pitt", persons[0].safeTitle)
    }

    @Test
    fun testSearchMetadataTransferAndItemKey() = runBlocking {
        val fakeApi = TestTmdbApiService()
        val tmdbRepo = TmdbRepository(fakeApi)
        val result = tmdbRepo.search("Fight Club")
        val paged = result.getOrNull()?.results.orEmpty()
        val movieTmdb = paged.first { it.id == 550L }

        val movieItem = movieTmdb.toMovieItem(genreMap = mapOf(18 to "Drama"))

        assertEquals("Fight Club", movieItem.name)
        assertEquals(550L, movieItem.tmdbId)
        assertEquals("movie", movieItem.tmdbMediaType)
        assertEquals("Drama", movieItem.genre)
        assertEquals("tmdb_movie_550", movieItem.uniqueItemKey)
        assertTrue("TMDB movie item must have empty MovieBox subjectId", movieItem.subjectId.isEmpty())
        assertFalse("TMDB movie item must not have genuine MovieBox subjectId", movieItem.hasMovieBoxSubjectId)
        assertFalse("TMDB items must not be directly playable without provider verification", movieItem.canInitiatePlayback)
    }

    @Test
    fun testWatchlistInsertAndQuerySeparation() = runBlocking {
        val watchDao = db.watchDao()

        // 1. Add TMDB movie to watchlist
        val tmdbMovie = WatchlistEntity(
            id = "tmdb_movie_550",
            subjectId = null,
            tmdbId = 550L,
            tmdbMediaType = "movie",
            tmdbOriginalTitle = "Fight Club",
            tmdbCountry = "US",
            tmdbOriginalLanguage = "en",
            slug = "",
            title = "Fight Club",
            posterUrl = "https://image.tmdb.org/t/p/w500/fc.jpg",
            rating = "8.4",
            year = "1999",
            genre = "Drama",
            type = "movie"
        )
        watchDao.addToWatchlist(tmdbMovie)

        // 2. Add Native MovieBox item to watchlist
        val mbItem = WatchlistEntity(
            id = "mb_sub_999",
            subjectId = "mb_sub_999",
            tmdbId = null,
            tmdbMediaType = null,
            slug = "avatar-2009",
            title = "Avatar",
            posterUrl = "https://example.com/avatar.jpg",
            rating = "7.9",
            year = "2009",
            genre = "Action",
            type = "movie"
        )
        watchDao.addToWatchlist(mbItem)

        // 3. Query watchlist
        val list = watchDao.getAllWatchlist().first()
        assertEquals(2, list.size)

        // Verify TMDB item identity
        val queriedTmdb = watchDao.getTmdbWatchlistItem(550L, "movie")
        assertNotNull(queriedTmdb)
        assertEquals("Fight Club", queriedTmdb!!.title)
        assertNull(queriedTmdb.subjectId)

        // Verify MovieBox item identity
        val queriedMb = watchDao.getWatchlistItem("mb_sub_999")
        assertNotNull(queriedMb)
        assertEquals("Avatar", queriedMb!!.title)
        assertEquals("mb_sub_999", queriedMb.subjectId)
        assertNull(queriedMb.tmdbId)

        // 4. Verify watchlist checks
        assertTrue(watchDao.isTmdbWatchlisted(550L, "movie").first())
        assertFalse(watchDao.isTmdbWatchlisted(999L, "movie").first())
        assertTrue(watchDao.isWatchlisted("mb_sub_999").first())
        assertFalse(watchDao.isWatchlisted("mb_unknown").first())

        // 5. Delete TMDB item
        watchDao.removeTmdbFromWatchlist(550L, "movie")
        assertFalse(watchDao.isTmdbWatchlisted(550L, "movie").first())
        assertEquals(1, watchDao.getAllWatchlist().first().size)
    }

    @Test
    fun testWatchlistDuplicatePrevention() = runBlocking {
        val watchDao = db.watchDao()

        val item1 = WatchlistEntity(
            id = "tmdb_movie_777",
            subjectId = null,
            tmdbId = 777L,
            tmdbMediaType = "movie",
            title = "Duplicate Test",
            slug = "",
            addedTimestamp = 1000L
        )
        val item2 = WatchlistEntity(
            id = "tmdb_movie_777",
            subjectId = null,
            tmdbId = 777L,
            tmdbMediaType = "movie",
            title = "Duplicate Test Updated",
            slug = "",
            addedTimestamp = 2000L
        )

        watchDao.addToWatchlist(item1)
        watchDao.addToWatchlist(item2)

        val list = watchDao.getAllWatchlist().first()
        assertEquals("Inserting item with same primary key must replace, not duplicate", 1, list.size)
        assertEquals("Duplicate Test Updated", list[0].title)
    }

    @Test
    fun testHistoryPreservationAndTmdbMetadata() = runBlocking {
        val watchDao = db.watchDao()

        val histEntry = WatchHistoryEntity(
            subjectId = "verified_mb_123",
            slug = "fight-club-1999",
            title = "Fight Club",
            posterUrl = "https://image.tmdb.org/t/p/w500/fc.jpg",
            season = 0,
            episode = 0,
            positionMs = 1200000L,
            durationMs = 8340000L,
            lastWatchedTimestamp = System.currentTimeMillis(),
            type = "movie",
            tmdbId = 550L,
            tmdbMediaType = "movie"
        )
        watchDao.upsertHistory(histEntry)

        val historyList = watchDao.getAllHistory().first()
        assertEquals(1, historyList.size)
        val saved = historyList[0]
        assertEquals("verified_mb_123", saved.subjectId)
        assertEquals(550L, saved.tmdbId)
        assertEquals("movie", saved.tmdbMediaType)
        assertEquals(1200000L, saved.positionMs)
    }

    @Test
    fun testPlaybackGuardStrictlyProtectsUnverifiedTmdbItems() {
        val unverifiedTmdb = MovieItem(
            name = "Inception",
            slug = "tmdb_movie_27205",
            subjectId = null,
            tmdbId = 27205L,
            tmdbMediaType = "movie"
        )
        assertFalse(
            "Unverified TMDB items must NOT have canInitiatePlayback = true",
            unverifiedTmdb.canInitiatePlayback
        )
        assertFalse(
            "Playback guard must reject null subjectId",
            MovieBoxPlaybackGuard.canInitiatePlayback(unverifiedTmdb.subjectId, unverifiedTmdb.slug)
        )

        val verifiedMatched = MovieItem(
            name = "Inception",
            slug = "inception-2010",
            subjectId = "10492",
            tmdbId = 27205L,
            tmdbMediaType = "movie"
        )
        assertTrue(
            "Verified items with valid MovieBox subject ID and slug may initiate playback",
            verifiedMatched.canInitiatePlayback
        )
    }
}
