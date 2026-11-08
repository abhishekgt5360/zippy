package com.example.ui

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.example.data.model.*
import com.example.data.remote.TmdbApiService
import com.example.data.repository.TmdbRepository
import com.example.util.RegionalCategoryConfig
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Phase 3A: TMDB Home and Browse Catalogue Integration Unit Tests.
 *
 * Verifies:
 * 1. Unique item key differentiation preventing Compose LazyRow/Grid collision.
 * 2. TMDB card metadata preservation (TMDB ID, media type, original title, country, language).
 * 3. Playback safety guards (TMDB items strictly prohibited from direct MovieBox streaming).
 * 4. Native MovieBox items remain playable.
 * 5. In-memory caching prevents duplicate network calls during navigation.
 * 6. Quick category selection properly updates CatalogUiState.
 * 7. openDetail(item) correctly transfers metadata to DetailPlayerUiState.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TmdbCatalogueUiIntegrationTest {

    private lateinit var app: Application
    private lateinit var viewModel: MainViewModel

    class FakeTmdbApiService : TmdbApiService {
        var trendingCallCount = 0
        var discoverMovieCallCount = 0
        var discoverTvCallCount = 0

        override suspend fun getTrending(
            mediaType: String,
            timeWindow: String,
            page: Int
        ): TmdbPagedResponse<TmdbMovieItem> {
            trendingCallCount++
            return TmdbPagedResponse(
                page = page,
                totalPages = 5,
                totalResults = 100,
                results = listOf(
                    TmdbMovieItem(
                        id = 550L,
                        title = "Fight Club",
                        releaseDate = "1999-10-15",
                        voteAverage = 8.4,
                        posterPath = "/pB8BM7pdSp6B6Ih7QZ4DrQ3PmJK.jpg",
                        genreIds = listOf(28, 18),
                        mediaType = "movie"
                    ),
                    TmdbMovieItem(
                        id = 551L,
                        title = "Inception",
                        releaseDate = "2010-07-16",
                        voteAverage = 8.8,
                        posterPath = "/edv5CZvWj09upOsy2Y6IwDhK8bt.jpg",
                        genreIds = listOf(28, 878),
                        mediaType = "movie"
                    )
                )
            )
        }

        override suspend fun getPopularMovies(page: Int, region: String?): TmdbPagedResponse<TmdbMovieItem> =
            TmdbPagedResponse(page = page, results = emptyList())

        override suspend fun getPopularTv(page: Int): TmdbPagedResponse<TmdbMovieItem> =
            TmdbPagedResponse(page = page, results = emptyList())

        override suspend fun getTopRatedMovies(page: Int): TmdbPagedResponse<TmdbMovieItem> =
            TmdbPagedResponse(page = page, results = emptyList())

        override suspend fun getTopRatedTv(page: Int): TmdbPagedResponse<TmdbMovieItem> =
            TmdbPagedResponse(page = page, results = emptyList())

        override suspend fun getNowPlayingMovies(page: Int): TmdbPagedResponse<TmdbMovieItem> =
            TmdbPagedResponse(page = page, results = emptyList())

        override suspend fun getUpcomingMovies(page: Int): TmdbPagedResponse<TmdbMovieItem> =
            TmdbPagedResponse(page = page, results = emptyList())

        override suspend fun discoverMovies(
            page: Int,
            sortBy: String?,
            withGenres: String?,
            withOriginalLanguage: String?,
            withOriginCountry: String?,
            primaryReleaseYear: Int?
        ): TmdbPagedResponse<TmdbMovieItem> {
            discoverMovieCallCount++
            return TmdbPagedResponse(
                page = page,
                totalPages = 10,
                results = listOf(
                    TmdbMovieItem(
                        id = 899112L,
                        title = "RRR",
                        originalTitle = "రౌద్రం రణం రుధిరం",
                        releaseDate = "2022-03-24",
                        voteAverage = 7.8,
                        posterPath = "/wE0Q2a17DqR3f5XN2vT8c4Bv.jpg",
                        originalLanguage = "te",
                        originCountry = listOf("IN"),
                        mediaType = "movie"
                    )
                )
            )
        }

        override suspend fun discoverTv(
            page: Int,
            sortBy: String?,
            withGenres: String?,
            withOriginalLanguage: String?,
            withOriginCountry: String?,
            firstAirDateYear: Int?
        ): TmdbPagedResponse<TmdbMovieItem> {
            discoverTvCallCount++
            return TmdbPagedResponse(
                page = page,
                totalPages = 5,
                results = listOf(
                    TmdbMovieItem(
                        id = 80989L,
                        name = "Sacred Games",
                        originalName = "Sacred Games",
                        firstAirDate = "2018-07-06",
                        voteAverage = 8.6,
                        posterPath = "/g1p5C5o2H.jpg",
                        originCountry = listOf("IN"),
                        mediaType = "tv"
                    )
                )
            )
        }

        override suspend fun searchMulti(query: String, page: Int): TmdbPagedResponse<TmdbMovieItem> =
            TmdbPagedResponse()

        override suspend fun searchMovies(query: String, page: Int, year: Int?): TmdbPagedResponse<TmdbMovieItem> =
            TmdbPagedResponse()

        override suspend fun searchTv(query: String, page: Int, year: Int?): TmdbPagedResponse<TmdbMovieItem> =
            TmdbPagedResponse()

        override suspend fun getMovieGenres(): TmdbGenreResponse =
            TmdbGenreResponse(genres = listOf(TmdbGenre(28, "Action"), TmdbGenre(18, "Drama")))

        override suspend fun getTvGenres(): TmdbGenreResponse =
            TmdbGenreResponse(genres = listOf(TmdbGenre(10759, "Action & Adventure")))

        override suspend fun getMovieDetail(movieId: Long, append: String): TmdbDetailResponse =
            TmdbDetailResponse(id = movieId)

        override suspend fun getTvDetail(tvId: Long, append: String): TmdbDetailResponse =
            TmdbDetailResponse(id = tvId)
    }

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        viewModel = MainViewModel(app)
    }

    @Test
    fun testTmdbItemKeyUniqueness() {
        val tmdbMovie1 = MovieItem(
            name = "Test Title",
            slug = "tmdb_movie_1001",
            subjectId = null,
            tmdbId = 1001L,
            tmdbMediaType = "movie"
        )
        val tmdbMovie2 = MovieItem(
            name = "Test Title",
            slug = "tmdb_movie_1002",
            subjectId = null,
            tmdbId = 1002L,
            tmdbMediaType = "movie"
        )
        val movieboxItem = MovieItem(
            name = "Test Title",
            slug = "mb-slug-123",
            subjectId = "mb_sub_456"
        )

        assertEquals("tmdb_movie_1001", tmdbMovie1.uniqueItemKey)
        assertEquals("tmdb_movie_1002", tmdbMovie2.uniqueItemKey)
        assertEquals("mb-slug-123_mb_sub_456", movieboxItem.uniqueItemKey)

        assertNotEquals(tmdbMovie1.uniqueItemKey, tmdbMovie2.uniqueItemKey)
    }

    @Test
    fun testTmdbCardMetadataPreserved() {
        val tmdbItem = TmdbMovieItem(
            id = 899112L,
            title = "RRR",
            originalTitle = "రౌద్రం రణం రుధిరం",
            releaseDate = "2022-03-24",
            voteAverage = 7.8,
            posterPath = "/rrr.jpg",
            originalLanguage = "te",
            originCountry = listOf("IN"),
            mediaType = "movie"
        )

        val movieItem = tmdbItem.toMovieItem(mapOf(28 to "Action"))

        assertEquals(899112L, movieItem.tmdbId)
        assertEquals("movie", movieItem.tmdbMediaType)
        assertEquals("రౌద్రం రణం రుధిరం", movieItem.tmdbOriginalTitle)
        assertEquals("te", movieItem.tmdbOriginalLanguage)
        assertEquals("IN", movieItem.tmdbCountry)
        assertEquals("2022", movieItem.year)
        assertEquals("7.8", movieItem.rating)
        assertTrue(movieItem.isFromTmdb)
        assertTrue(movieItem.posterUrl?.contains("/rrr.jpg") == true)

        // MovieBox fields MUST remain empty
        assertEquals("", movieItem.subjectId)
        assertFalse(movieItem.hasMovieBoxSubjectId)
        assertFalse(movieItem.hasMovieBoxSlug)
        assertFalse(movieItem.canInitiatePlayback)
    }

    @Test
    fun testTmdbCardPlaybackGuardPreventsPlayback() {
        val tmdbMovie = MovieItem(
            name = "Inception",
            slug = "tmdb_movie_551",
            subjectId = null,
            tmdbId = 551L,
            tmdbMediaType = "movie"
        )

        assertFalse(MovieBoxPlaybackGuard.isValidMovieBoxSubjectId(tmdbMovie.subjectId, tmdbMovie.tmdbId))
        assertFalse(MovieBoxPlaybackGuard.isValidMovieBoxSlug(tmdbMovie.slug))
        assertFalse(MovieBoxPlaybackGuard.canInitiatePlayback(tmdbMovie.subjectId, tmdbMovie.slug, tmdbMovie.tmdbId))
        assertFalse(tmdbMovie.canInitiatePlayback)
    }

    @Test
    fun testNativeMovieBoxPlaybackAllowed() {
        val nativeItem = MovieItem(
            name = "Native Movie",
            slug = "moviebox-native-detail-123",
            subjectId = "mb_sub_98765"
        )

        assertTrue(MovieBoxPlaybackGuard.isValidMovieBoxSubjectId(nativeItem.subjectId, null))
        assertTrue(MovieBoxPlaybackGuard.isValidMovieBoxSlug(nativeItem.slug))
        assertTrue(MovieBoxPlaybackGuard.canInitiatePlayback(nativeItem.subjectId, nativeItem.slug, null))
        assertTrue(nativeItem.canInitiatePlayback)
    }

    @Test
    fun testTmdbRepositoryCachingAvoidsDuplicateCalls() = runBlocking {
        val fakeApi = FakeTmdbApiService()
        val repo = TmdbRepository(api = fakeApi)
        repo.clearCache()

        // First call triggers network
        val res1 = repo.getTrending("movie", "week", 1, forceRefresh = false)
        assertTrue(res1.isSuccess)
        assertEquals(1, fakeApi.trendingCallCount)

        // Second call with forceRefresh = false must hit in-memory cache
        val res2 = repo.getTrending("movie", "week", 1, forceRefresh = false)
        assertTrue(res2.isSuccess)
        assertEquals(1, fakeApi.trendingCallCount) // Call count did not increase!

        // Third call with forceRefresh = true must bypass cache
        val res3 = repo.getTrending("movie", "week", 1, forceRefresh = true)
        assertTrue(res3.isSuccess)
        assertEquals(2, fakeApi.trendingCallCount)
    }

    @Test
    fun testIndianCinemaDiscoveryMethods() = runBlocking {
        val fakeApi = FakeTmdbApiService()
        val repo = TmdbRepository(api = fakeApi)

        val moviesRes = repo.getIndianMovies(page = 1)
        assertTrue(moviesRes.isSuccess)
        assertEquals(1, fakeApi.discoverMovieCallCount)

        val tvRes = repo.getIndianTv(page = 1)
        assertTrue(tvRes.isSuccess)
        assertEquals(1, fakeApi.discoverTvCallCount)
    }

    @Test
    fun testOpenDetailWithMovieItemTransfersTmdbMetadata() {
        val tmdbMovie = MovieItem(
            name = "RRR",
            posterUrl = "https://image.tmdb.org/t/p/w500/rrr.jpg",
            slug = "tmdb_movie_899112",
            subjectId = null,
            badge = "MOVIE",
            rating = "7.8",
            year = "2022",
            genre = "Action",
            type = "movie",
            tmdbId = 899112L,
            tmdbMediaType = "movie",
            tmdbOriginalTitle = "రౌద్రం రణం రుధిరం",
            tmdbOriginalLanguage = "te",
            tmdbCountry = "IN",
            backdropUrl = "https://image.tmdb.org/t/p/w1280/backdrop.jpg",
            overview = "A fictional story about two legendary revolutionaries..."
        )

        viewModel.openDetail(tmdbMovie)

        val state = viewModel.detailPlayerState.value
        assertEquals("RRR", state.title)
        assertEquals(899112L, state.tmdbId)
        assertEquals("movie", state.tmdbMediaType)
        assertEquals("రౌద్రం రణం రుధిరం", state.tmdbOriginalTitle)
        assertEquals("2022", state.releaseYear)
        assertEquals("te", state.originalLanguage)
        assertEquals("IN", state.country)
        assertEquals("Action", state.initialGenre)
        assertEquals("A fictional story about two legendary revolutionaries...", state.initialOverview)
        assertEquals("https://image.tmdb.org/t/p/w1280/backdrop.jpg", state.initialBackdropUrl)
        assertFalse(state.isLoadingDetail) // TMDB slug must not trigger raw MovieBox detail loading
    }

    @Test
    fun testQuickCategorySelectionInCatalog() {
        // Select Indian TV Shows
        viewModel.openCatalogCategory(
            type = CatalogType.TV_SERIES,
            presetId = RegionalCategoryConfig.PRESET_INDIA,
            sort = "foryou"
        )

        var catalog = viewModel.catalogState.value
        assertEquals(CatalogType.TV_SERIES, catalog.type)
        assertEquals(RegionalCategoryConfig.PRESET_INDIA, catalog.selectedPresetId)
        assertEquals("foryou", catalog.selectedSort)
        assertEquals(AppTab.CATALOG, viewModel.currentTab.value)

        // Select Trending Movies
        viewModel.openCatalogCategory(
            type = CatalogType.MOVIES,
            presetId = RegionalCategoryConfig.PRESET_ALL,
            sort = "hottest"
        )

        catalog = viewModel.catalogState.value
        assertEquals(CatalogType.MOVIES, catalog.type)
        assertEquals(RegionalCategoryConfig.PRESET_ALL, catalog.selectedPresetId)
        assertEquals("hottest", catalog.selectedSort)
    }
}
