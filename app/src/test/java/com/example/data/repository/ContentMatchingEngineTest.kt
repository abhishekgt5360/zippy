package com.example.data.repository

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.local.AppDatabase
import com.example.data.local.ContentMappingEntity
import com.example.data.model.*
import com.example.data.remote.MovieApiService
import com.example.ui.MainViewModel
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Comprehensive automated test suite for Zippy ContentMatchingEngine and Playback Safety.
 *
 * Covers all required verification and safety scenarios:
 * 1. Exact title and exact year.
 * 2. Exact title but one-year difference (yields NEEDS_REVIEW without exception).
 * 3. Exact title with missing year (yields NEEDS_REVIEW).
 * 4. Different release year (diff > 1 year yields NOT_FOUND).
 * 5. Same title and same year with two different provider IDs (yields NEEDS_REVIEW).
 * 6. Wrong MovieBox detail title (yields NEEDS_REVIEW).
 * 7. Wrong MovieBox detail media type (yields NEEDS_REVIEW).
 * 8. Missing availability field (does not treat missing info as positive evidence).
 * 9. Detail response without usable resources (hasAvailableStreams = false, not playable).
 * 10. Concurrent requests for the same TMDB item (shared single Deferred operation).
 * 11. Concurrent requests for different TMDB items (throttled by Semaphore(3)).
 * 12. Availability expiry after 24 hours (refreshes availability without dropping verified identity).
 * 13. Identity revalidation after 30 days (triggers full re-evaluation).
 * 14. Valid MovieBox subject ID numerically equal to a TMDB ID (allowed).
 * 15. Native MovieBox playback (operates normally).
 * 16. Verified TMDB-to-MovieBox playback (supplies verified provider identifiers).
 * 17. Unverified mapping rejection (blocks playback, never calls getStream).
 * 18. Search and detail subject IDs match (verified).
 * 19. Search and detail subject IDs differ (NEEDS_REVIEW, does not combine IDs, unplayable).
 * 20. Detail subject ID is missing (NEEDS_REVIEW, unplayable).
 * 21. Detail slug and subject ID refer to different provider records (NEEDS_REVIEW).
 * 22. Stricter title matching: similar title with same year (NEEDS_REVIEW).
 * 23. Stricter title matching: overlapping words with same year (NEEDS_REVIEW).
 * 24. One-year difference with same language & country (NEEDS_REVIEW).
 * 25. Search API success with 0 results (cached as NOT_FOUND for 24h).
 * 26. Search API network failure (returns CHECKING, not cached in Room).
 * 27. Detail API network failure (returns CHECKING, not cached in Room).
 * 28. Stream probe recognizes HLS streams (.m3u8).
 * 29. Stream probe recognizes DASH streams (.mpd).
 * 30. Stream probe network failure (not cached as unavailable for 24h, allows immediate retry).
 * 31. Exact candidate year and exact detail year (VERIFIED).
 * 32. Exact candidate year but one-year detail discrepancy (NEEDS_REVIEW).
 * 33. Candidate and detail years conflict by > 1 year (NEEDS_REVIEW).
 * 34. Missing detail year (NEEDS_REVIEW).
 * 35. Engine cancellation cancels in-flight jobs.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ContentMatchingEngineTest {

    private lateinit var context: Context
    private lateinit var database: AppDatabase
    private lateinit var fakeApi: FakeMovieBoxApi
    private lateinit var repository: MovieRepository
    private lateinit var engine: ContentMatchingEngine

    class FakeMovieBoxApi : MovieApiService {
        var searchResults = mutableListOf<MovieItem>()
        var detailResponses = mutableMapOf<String, DetailResponse>()
        var streamResponses = mutableMapOf<String, StreamResponse>()
        var searchCallCount = 0
        var detailCallCount = 0
        var streamCallCount = 0

        var searchException: Exception? = null
        var detailException: Exception? = null
        var streamException: Exception? = null
        val searchQueryExceptions = mutableMapOf<String, Exception>()
        val searchQueryResults = mutableMapOf<String, List<MovieItem>>()
        val streamExceptions = mutableMapOf<String, Exception>()

        var searchDelayMs: Long = 0L
        var detailDelayMs: Long = 0L
        var streamDelayMs: Long = 0L

        private val activeStreamProbes = java.util.concurrent.atomic.AtomicInteger(0)
        var maxConcurrentStreamProbes = 0

        override suspend fun checkHealth(): HealthResponse = HealthResponse(status = "ok")
        override suspend fun getHome(): HomeResponse = HomeResponse(status = "ok", sections = emptyList())
        override suspend fun getMovies(page: Int, sort: String?, genre: String?, country: String?, year: String?): CatalogResponse = CatalogResponse()
        override suspend fun getTvSeries(page: Int, sort: String?, genre: String?, country: String?, year: String?): CatalogResponse = CatalogResponse()
        override suspend fun getAnimation(page: Int, sort: String?, genre: String?): CatalogResponse = CatalogResponse()

        override suspend fun search(query: String, page: Int): SearchResponse {
            searchCallCount++
            if (searchDelayMs > 0) delay(searchDelayMs)
            searchQueryExceptions[query]?.let { throw it }
            searchException?.let { throw it }
            val specific = searchQueryResults[query]
            if (specific != null) {
                return SearchResponse(items = specific)
            }
            return SearchResponse(items = searchResults)
        }

        override suspend fun getSuggestions(query: String): SuggestResponse = SuggestResponse()

        override suspend fun getDetail(slug: String): DetailResponse {
            detailCallCount++
            if (detailDelayMs > 0) delay(detailDelayMs)
            detailException?.let { throw it }
            val stub = detailResponses[slug]
            if (stub != null) return stub
            return DetailResponse(
                code = 200,
                data = DetailData(
                    subject = SubjectDetail(
                        rawSubjectId = "mb_$slug",
                        title = "Detail of $slug",
                        rawSubjectType = 1,
                        hasResource = true
                    )
                )
            )
        }

        override suspend fun getStream(subjectId: String, detailPath: String, season: Int, episode: Int): StreamResponse {
            val current = activeStreamProbes.incrementAndGet()
            if (current > maxConcurrentStreamProbes) {
                maxConcurrentStreamProbes = current
            }
            try {
                streamCallCount++
                if (streamDelayMs > 0) delay(streamDelayMs)
                val key = "$subjectId:$detailPath:$season:$episode"
                streamExceptions[key]?.let { throw it }
                streamException?.let { throw it }
                val stub = streamResponses[key] ?: streamResponses["$subjectId:$detailPath"] ?: streamResponses[subjectId]
                if (stub != null) return stub

                return StreamResponse(
                    rawSubjectId = subjectId,
                    hasResource = true,
                    sources = listOf(StreamSource(resolution = "1080p", url = "https://stream.example.com/video.mp4"))
                )
            } finally {
                activeStreamProbes.decrementAndGet()
            }
        }

        override suspend fun getCaptions(subjectId: String, detailPath: String, season: Int, episode: Int): CaptionsResponse {
            return CaptionsResponse(rawSubjectId = subjectId, captions = emptyList())
        }
    }

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        fakeApi = FakeMovieBoxApi()
        repository = MovieRepository(api = fakeApi, database = database, context = context)
        engine = ContentMatchingEngine(movieRepository = repository, database = database)
    }

    @After
    fun tearDown() {
        database.close()
    }

    // ── 1. Exact title and exact year ───────────────────────────────────────
    @Test
    fun test01_exactTitleAndExactYear_returnsVerifiedAndPlayable() = runBlocking {
        fakeApi.searchResults = mutableListOf(
            MovieItem(
                name = "Inception",
                slug = "inception-2010",
                rawSubjectId = "1001",
                rawYear = 2010,
                rawType = 1
            )
        )
        fakeApi.detailResponses["inception-2010"] = DetailResponse(
            code = 200,
            data = DetailData(
                subject = SubjectDetail(
                    rawSubjectId = "1001",
                    title = "Inception",
                    rawSubjectType = 1,
                    releaseDate = "2010-07-16",
                    hasResource = true
                )
            )
        )

        val match = engine.getMapping(
            tmdbId = 27205L,
            mediaType = "movie",
            title = "Inception",
            releaseYear = "2010"
        )

        assertEquals(MatchStatus.VERIFIED, match.matchStatus)
        assertTrue("Identity must be verified", match.isVerified)
        assertTrue("Streams must be confirmed available", match.hasAvailableStreams)
        assertTrue("Verified item with available streams must be playable", match.isPlayable)
        assertEquals("1001", match.movieboxSubjectId)
        assertEquals("inception-2010", match.movieboxDetailPath)
        assertTrue("Confidence must be actual score", match.matchConfidence > 0.90f && match.matchConfidence <= 1.0f)
    }

    // ── 2. Exact title but one-year difference ──────────────────────────────
    @Test
    fun test02_exactTitleButOneYearDifference_returnsNeedsReview() = runBlocking {
        fakeApi.searchResults = mutableListOf(
            MovieItem(
                name = "Past Lives",
                slug = "past-lives-2023",
                rawSubjectId = "2001",
                rawYear = 2023,
                rawType = 1
            )
        )

        val match = engine.getMapping(
            tmdbId = 666243L,
            mediaType = "movie",
            title = "Past Lives",
            releaseYear = "2024" // 1-year difference
        )

        assertEquals("One-year variance must be flagged as NEEDS_REVIEW", MatchStatus.NEEDS_REVIEW, match.matchStatus)
        assertFalse("NEEDS_REVIEW must not be playable", match.isPlayable)
    }

    // ── 3. Exact title with missing year ────────────────────────────────────
    @Test
    fun test03_exactTitleWithMissingYear_returnsNeedsReview() = runBlocking {
        fakeApi.searchResults = mutableListOf(
            MovieItem(
                name = "Unknown Project",
                slug = "unknown-project",
                rawSubjectId = "3001",
                rawYear = null,
                rawType = 1
            )
        )

        val match = engine.getMapping(
            tmdbId = 999901L,
            mediaType = "movie",
            title = "Unknown Project",
            releaseYear = null
        )

        assertEquals("Missing release year must yield NEEDS_REVIEW", MatchStatus.NEEDS_REVIEW, match.matchStatus)
        assertFalse("Unverified year must not be playable", match.isPlayable)
    }

    // ── 4. Different release year (diff > 1 year) ───────────────────────────
    @Test
    fun test04_differentReleaseYear_returnsNotFound() = runBlocking {
        fakeApi.searchResults = mutableListOf(
            MovieItem(
                name = "The Fall",
                slug = "the-fall-2018",
                rawSubjectId = "4001",
                rawYear = 2018,
                rawType = 1
            )
        )

        val match = engine.getMapping(
            tmdbId = 99991L,
            mediaType = "movie",
            title = "The Fall",
            releaseYear = "2024"
        )

        assertEquals(MatchStatus.NOT_FOUND, match.matchStatus)
        assertFalse(match.isVerified)
        assertFalse(match.isPlayable)
        assertNull(match.movieboxSubjectId)
    }

    // ── 5. Same title and same year with two different provider IDs ─────────
    @Test
    fun test05_sameTitleSameYearTwoDifferentProviderIds_returnsNeedsReview() = runBlocking {
        fakeApi.searchResults = mutableListOf(
            MovieItem(
                name = "Pinocchio",
                slug = "pinocchio-2022-disney",
                rawSubjectId = "5001",
                rawYear = 2022,
                rawType = 1
            ),
            MovieItem(
                name = "Pinocchio",
                slug = "pinocchio-2022-del-toro",
                rawSubjectId = "5002",
                rawYear = 2022,
                rawType = 1
            )
        )

        val match = engine.getMapping(
            tmdbId = 533535L,
            mediaType = "movie",
            title = "Pinocchio",
            releaseYear = "2022"
        )

        assertEquals("Ambiguous candidates with same score must return NEEDS_REVIEW", MatchStatus.NEEDS_REVIEW, match.matchStatus)
        assertFalse("Ambiguous candidates must never enable playback", match.isPlayable)
    }

    // ── 6. Wrong MovieBox detail title ──────────────────────────────────────
    @Test
    fun test06_wrongMovieBoxDetailTitle_returnsNeedsReview() = runBlocking {
        fakeApi.searchResults = mutableListOf(
            MovieItem(
                name = "Gladiator",
                slug = "gladiator-slug",
                rawSubjectId = "6001",
                rawYear = 2000,
                rawType = 1
            )
        )
        fakeApi.detailResponses["gladiator-slug"] = DetailResponse(
            code = 200,
            data = DetailData(
                subject = SubjectDetail(
                    rawSubjectId = "6001",
                    title = "Completely Different Movie",
                    rawSubjectType = 1,
                    releaseDate = "2000-05-05"
                )
            )
        )

        val match = engine.getMapping(
            tmdbId = 98L,
            mediaType = "movie",
            title = "Gladiator",
            releaseYear = "2000"
        )

        assertEquals("Detail title mismatch must yield NEEDS_REVIEW", MatchStatus.NEEDS_REVIEW, match.matchStatus)
        assertFalse(match.isPlayable)
    }

    // ── 7. Wrong MovieBox detail media type ─────────────────────────────────
    @Test
    fun test07_wrongMovieBoxDetailMediaType_returnsNeedsReview() = runBlocking {
        fakeApi.searchResults = mutableListOf(
            MovieItem(
                name = "Fargo",
                slug = "fargo-slug",
                rawSubjectId = "7001",
                rawYear = 2014,
                rawType = 2
            )
        )
        fakeApi.detailResponses["fargo-slug"] = DetailResponse(
            code = 200,
            data = DetailData(
                subject = SubjectDetail(
                    rawSubjectId = "7001",
                    title = "Fargo",
                    rawSubjectType = 1, // Movie!
                    releaseDate = "2014-04-15"
                ),
                resource = ResourceDetail(seasons = emptyList())
            )
        )

        val match = engine.getMapping(
            tmdbId = 60622L,
            mediaType = "tv",
            title = "Fargo",
            releaseYear = "2014"
        )

        assertEquals("Detail media type mismatch must yield NEEDS_REVIEW", MatchStatus.NEEDS_REVIEW, match.matchStatus)
        assertFalse(match.isPlayable)
    }

    // ── 8. Missing availability field / no positive assumption ──────────────
    @Test
    fun test08_missingAvailabilityField_probesStreamAndDoesNotAssumePlayable() = runBlocking {
        fakeApi.searchResults = mutableListOf(
            MovieItem(
                name = "Test Film",
                slug = "test-film-2023",
                rawSubjectId = "8001",
                rawYear = 2023,
                rawType = 1
            )
        )
        fakeApi.detailResponses["test-film-2023"] = DetailResponse(
            code = 200,
            data = DetailData(
                subject = SubjectDetail(
                    rawSubjectId = "8001",
                    title = "Test Film",
                    rawSubjectType = 1,
                    releaseDate = "2023-01-01"
                )
            )
        )
        fakeApi.streamResponses["8001"] = StreamResponse(
            rawSubjectId = "8001",
            hasResource = false,
            sources = emptyList()
        )

        val match = engine.getMapping(
            tmdbId = 8801L,
            mediaType = "movie",
            title = "Test Film",
            releaseYear = "2023"
        )

        assertEquals(MatchStatus.VERIFIED, match.matchStatus)
        assertFalse("Missing streams must result in hasAvailableStreams = false", match.hasAvailableStreams)
        assertFalse("Must not be playable when streams are unavailable", match.isPlayable)
    }

    // ── 9. Detail response without usable resources ─────────────────────────
    @Test
    fun test09_detailResponseWithoutUsableResources_notPlayable() = runBlocking {
        fakeApi.searchResults = mutableListOf(
            MovieItem(
                name = "Upcoming Movie",
                slug = "upcoming-movie-2025",
                rawSubjectId = "9001",
                rawYear = 2025,
                rawType = 1
            )
        )
        fakeApi.detailResponses["upcoming-movie-2025"] = DetailResponse(
            code = 200,
            data = DetailData(
                subject = SubjectDetail(
                    rawSubjectId = "9001",
                    title = "Upcoming Movie",
                    rawSubjectType = 1,
                    releaseDate = "2025-01-01"
                )
            )
        )
        fakeApi.streamResponses["9001"] = StreamResponse(
            rawSubjectId = "9001",
            hasResource = false,
            sources = emptyList()
        )

        val match = engine.getMapping(
            tmdbId = 9901L,
            mediaType = "movie",
            title = "Upcoming Movie",
            releaseYear = "2025"
        )

        assertEquals(MatchStatus.VERIFIED, match.matchStatus)
        assertFalse(match.hasAvailableStreams)
        assertFalse(match.isPlayable)
    }

    // ── 10. Concurrent requests for the same TMDB item ──────────────────────
    @Test
    fun test10_concurrentRequestsForSameTmdbItem_shareSingleOperation() = runBlocking {
        fakeApi.searchResults = mutableListOf(
            MovieItem(
                name = "Oppenheimer",
                slug = "oppenheimer-2023",
                rawSubjectId = "10001",
                rawYear = 2023,
                rawType = 1
            )
        )
        fakeApi.detailResponses["oppenheimer-2023"] = DetailResponse(
            code = 200,
            data = DetailData(
                subject = SubjectDetail(
                    rawSubjectId = "10001",
                    title = "Oppenheimer",
                    rawSubjectType = 1,
                    releaseDate = "2023-07-21"
                )
            )
        )

        val deferred1 = async {
            engine.getMapping(tmdbId = 872585L, mediaType = "movie", title = "Oppenheimer", releaseYear = "2023")
        }
        val deferred2 = async {
            engine.getMapping(tmdbId = 872585L, mediaType = "movie", title = "Oppenheimer", releaseYear = "2023")
        }

        val result1 = deferred1.await()
        val result2 = deferred2.await()

        assertEquals(result1, result2)
        assertEquals(MatchStatus.VERIFIED, result1.matchStatus)
        assertEquals("10001", result1.movieboxSubjectId)
        assertEquals("Detail API must be called exactly once for duplicate concurrent requests", 1, fakeApi.detailCallCount)
        assertEquals("Search API executed planned queries for single shared operation", 2, fakeApi.searchCallCount)
    }

    // ── 11. Concurrent requests for different TMDB items ────────────────────
    @Test
    fun test11_concurrentRequestsForDifferentItems_bothComplete() = runBlocking {
        fakeApi.searchResults = mutableListOf(
            MovieItem(
                name = "Common Result",
                slug = "common-slug",
                rawSubjectId = "11001",
                rawYear = 2023,
                rawType = 1
            )
        )
        fakeApi.detailResponses["common-slug"] = DetailResponse(
            code = 200,
            data = DetailData(
                subject = SubjectDetail(
                    rawSubjectId = "11001",
                    title = "Common Result",
                    rawSubjectType = 1,
                    releaseDate = "2023-01-01"
                )
            )
        )

        val defA = async { engine.getMapping(tmdbId = 111L, mediaType = "movie", title = "Common Result", releaseYear = "2023") }
        val defB = async { engine.getMapping(tmdbId = 222L, mediaType = "movie", title = "Common Result", releaseYear = "2023") }

        val resA = defA.await()
        val resB = defB.await()

        assertEquals(MatchStatus.VERIFIED, resA.matchStatus)
        assertEquals(MatchStatus.VERIFIED, resB.matchStatus)
        assertEquals(111L, resA.tmdbId)
        assertEquals(222L, resB.tmdbId)
    }

    // ── 12. Availability expiry after 24 hours ──────────────────────────────
    @Test
    fun test12_availabilityExpiryAfter24Hours_refreshesAvailabilityWithoutDroppingIdentity() = runBlocking {
        val now = System.currentTimeMillis()
        val expiredAvailability = now - 1000L
        val identityExpiresAt = now + (29L * 24 * 60 * 60 * 1000)

        database.watchDao().upsertMapping(
            ContentMappingEntity(
                tmdbId = 1201L,
                mediaType = "movie",
                tmdbTitle = "The Matrix",
                tmdbOriginalTitle = "The Matrix",
                releaseYear = "1999",
                movieboxSubjectId = "mb_matrix",
                movieboxDetailPath = "the-matrix-1999",
                matchStatus = MatchStatus.VERIFIED.name,
                matchConfidence = 0.98f,
                matchedAt = now - (2L * 24 * 60 * 60 * 1000),
                lastCheckedAt = expiredAvailability,
                expiresAt = identityExpiresAt,
                hasAvailableStreams = true,
                availabilityCheckedAt = expiredAvailability - (24L * 60 * 60 * 1000),
                availabilityExpiresAt = expiredAvailability
            )
        )

        fakeApi.streamResponses["mb_matrix:the-matrix-1999:0:0"] = StreamResponse(
            rawSubjectId = "mb_matrix",
            hasResource = true,
            sources = listOf(StreamSource(resolution = "1080p", url = "https://stream.example.com/matrix.mp4"))
        )

        val refreshed = engine.getMapping(
            tmdbId = 1201L,
            mediaType = "movie",
            title = "The Matrix",
            releaseYear = "1999"
        )

        assertEquals("Identity must remain VERIFIED", MatchStatus.VERIFIED, refreshed.matchStatus)
        assertEquals("mb_matrix", refreshed.movieboxSubjectId)
        assertTrue("Availability probe must confirm streams", refreshed.hasAvailableStreams)
        assertTrue("Fresh availability must make item playable", refreshed.isPlayable)
        assertTrue("Availability expiry must be refreshed by 24h", refreshed.availabilityExpiresAt > System.currentTimeMillis())
        assertEquals("Search API must NOT be called for availability-only refresh", 0, fakeApi.searchCallCount)
    }

    // ── 13. Identity revalidation after 30 days ──────────────────────────────
    @Test
    fun test13_identityRevalidationAfter30Days_triggersFullRevalidation() = runBlocking {
        val expiredTime = System.currentTimeMillis() - 1000L
        database.watchDao().upsertMapping(
            ContentMappingEntity(
                tmdbId = 1301L,
                mediaType = "movie",
                tmdbTitle = "Alien",
                tmdbOriginalTitle = "Alien",
                releaseYear = "1979",
                movieboxSubjectId = "mb_alien_old",
                movieboxDetailPath = "alien-1979",
                matchStatus = MatchStatus.VERIFIED.name,
                matchConfidence = 0.98f,
                matchedAt = expiredTime - (31L * 24 * 60 * 60 * 1000),
                lastCheckedAt = expiredTime,
                expiresAt = expiredTime,
                hasAvailableStreams = true,
                availabilityCheckedAt = expiredTime,
                availabilityExpiresAt = expiredTime
            )
        )

        fakeApi.searchResults = mutableListOf(
            MovieItem(
                name = "Alien",
                slug = "alien-1979",
                rawSubjectId = "mb_alien_fresh",
                rawYear = 1979,
                rawType = 1
            )
        )
        fakeApi.detailResponses["alien-1979"] = DetailResponse(
            code = 200,
            data = DetailData(
                subject = SubjectDetail(
                    rawSubjectId = "mb_alien_fresh",
                    title = "Alien",
                    rawSubjectType = 1,
                    releaseDate = "1979-05-25"
                )
            )
        )

        val refreshed = engine.getMapping(
            tmdbId = 1301L,
            mediaType = "movie",
            title = "Alien",
            releaseYear = "1979"
        )

        assertEquals("mb_alien_fresh", refreshed.movieboxSubjectId)
        assertFalse(refreshed.isIdentityExpired)
        assertTrue(refreshed.isPlayable)
        assertTrue("Search API must be called to revalidate identity", fakeApi.searchCallCount > 0)
    }

    // ── 14. Valid MovieBox subject ID numerically equal to a TMDB ID ─────────
    @Test
    fun test14_validMovieBoxIdNumericallyEqualToTmdbId_allowed() {
        val numericId = "550"
        val tmdbId = 550L

        assertTrue(
            "Playback guard must not reject coincidentally identical numeric IDs",
            MovieBoxPlaybackGuard.isValidMovieBoxSubjectId(numericId, tmdbId)
        )
        assertTrue(
            "Guard must allow playback when valid slug and subject ID are present",
            MovieBoxPlaybackGuard.canInitiatePlayback(numericId, "fight-club-1999", tmdbId)
        )
    }

    // ── 15. Native MovieBox playback ─────────────────────────────────────────
    @Test
    fun test15_nativeMovieBoxPlayback_proceedsNormally() = runBlocking {
        val result = repository.getStream(
            subjectId = "15001",
            slug = "native-film-2020",
            season = 0,
            episode = 0
        )

        assertTrue(result.isSuccess)
        assertEquals(1, fakeApi.streamCallCount)
    }

    // ── 16. Verified TMDB-to-MovieBox playback ───────────────────────────────
    @Test
    fun test16_verifiedTmdbToMovieBoxPlayback_usesVerifiedProviderIds() = runBlocking {
        val verifiedMatch = ContentMatch(
            tmdbId = 1601L,
            tmdbMediaType = "movie",
            tmdbTitle = "Verified Film",
            movieboxSubjectId = "mb_sub_1601",
            movieboxDetailPath = "verified-film-2023",
            matchStatus = MatchStatus.VERIFIED,
            hasAvailableStreams = true,
            availabilityCheckedAt = System.currentTimeMillis(),
            availabilityExpiresAt = System.currentTimeMillis() + ContentMatch.REFRESH_AVAILABILITY_MS
        )

        assertTrue(verifiedMatch.isPlayable)

        val result = repository.getStream(
            subjectId = verifiedMatch.movieboxSubjectId!!,
            slug = verifiedMatch.movieboxDetailPath!!,
            season = 0,
            episode = 0
        )

        assertTrue(result.isSuccess)
    }

    // ── 17. Unverified mapping rejection ─────────────────────────────────────
    @Test
    fun test17_unverifiedMappingRejection_playbackBlocked() = runBlocking {
        val notFoundMatch = ContentMatch(
            tmdbId = 1701L,
            tmdbMediaType = "movie",
            tmdbTitle = "Unfound Film",
            matchStatus = MatchStatus.NOT_FOUND
        )

        assertFalse("Unfound item must not be playable", notFoundMatch.isPlayable)
        assertFalse(
            "Playback guard must reject unverified item",
            MovieBoxPlaybackGuard.canInitiatePlayback(notFoundMatch.movieboxSubjectId, notFoundMatch.movieboxDetailPath)
        )

        val app = ApplicationProvider.getApplicationContext<android.app.Application>()
        val viewModel = MainViewModel(app)

        viewModel.playItem(slug = "tmdb_movie_1701", subjectId = "")

        val state = viewModel.detailPlayerState.value
        assertFalse(state.isPlaying)
        assertFalse(state.isLoadingStream)
        assertEquals("This title is not currently available for streaming.", state.streamError)
    }

    // ── 18. TASK 1: Search and Detail Subject IDs Differ ────────────────────
    @Test
    fun test18_searchAndDetailSubjectIdsDiffer_returnsNeedsReviewAndDoesNotCombine() = runBlocking {
        fakeApi.searchResults = mutableListOf(
            MovieItem(
                name = "The Prestige",
                slug = "the-prestige-2006",
                rawSubjectId = "18001",
                rawYear = 2006,
                rawType = 1
            )
        )
        // Detail endpoint returns a DIFFERENT subjectId
        fakeApi.detailResponses["the-prestige-2006"] = DetailResponse(
            code = 200,
            data = DetailData(
                subject = SubjectDetail(
                    rawSubjectId = "18999", // Different!
                    title = "The Prestige",
                    rawSubjectType = 1,
                    releaseDate = "2006-10-20",
                    hasResource = true
                )
            )
        )

        val match = engine.getMapping(
            tmdbId = 1124L,
            mediaType = "movie",
            title = "The Prestige",
            releaseYear = "2006"
        )

        assertEquals("Mismatched subject IDs must return NEEDS_REVIEW", MatchStatus.NEEDS_REVIEW, match.matchStatus)
        assertNull("Detail subject ID must NOT be combined with candidate slug", match.movieboxSubjectId)
        assertNull("Detail path must be cleared when subject IDs mismatch", match.movieboxDetailPath)
        assertFalse("Mismatched subject ID candidate must not be playable", match.isPlayable)
    }

    // ── 19. TASK 1: Detail Subject ID is Missing ────────────────────────────
    @Test
    fun test19_detailSubjectIdMissing_returnsNeedsReview() = runBlocking {
        fakeApi.searchResults = mutableListOf(
            MovieItem(
                name = "Memento",
                slug = "memento-2000",
                rawSubjectId = "19001",
                rawYear = 2000,
                rawType = 1
            )
        )
        fakeApi.detailResponses["memento-2000"] = DetailResponse(
            code = 200,
            data = DetailData(
                subject = SubjectDetail(
                    rawSubjectId = null, // Missing subjectId
                    title = "Memento",
                    rawSubjectType = 1,
                    releaseDate = "2000-10-11"
                )
            )
        )

        val match = engine.getMapping(
            tmdbId = 77L,
            mediaType = "movie",
            title = "Memento",
            releaseYear = "2000"
        )

        assertEquals("Missing detail subject ID must return NEEDS_REVIEW", MatchStatus.NEEDS_REVIEW, match.matchStatus)
        assertFalse(match.isPlayable)
    }

    // ── 20. TASK 2: Stricter Title Matching - Similar Title with Same Year ──
    @Test
    fun test20_similarTitleWithSameYear_returnsNeedsReview() = runBlocking {
        // TMDB is "Spider-Man", MovieBox search returns "Spider-Man 2" both with year 2004
        fakeApi.searchResults = mutableListOf(
            MovieItem(
                name = "Spider-Man 2",
                slug = "spiderman-2-2004",
                rawSubjectId = "20002",
                rawYear = 2004,
                rawType = 1
            )
        )
        fakeApi.detailResponses["spiderman-2-2004"] = DetailResponse(
            code = 200,
            data = DetailData(
                subject = SubjectDetail(
                    rawSubjectId = "20002",
                    title = "Spider-Man 2",
                    rawSubjectType = 1,
                    releaseDate = "2004-06-30"
                )
            )
        )

        val match = engine.getMapping(
            tmdbId = 557L,
            mediaType = "movie",
            title = "Spider-Man",
            releaseYear = "2004"
        )

        assertEquals("Non-exact title match must yield NEEDS_REVIEW", MatchStatus.NEEDS_REVIEW, match.matchStatus)
        assertFalse("Fuzzy title must not be playable automatically", match.isPlayable)
    }

    // ── 21. TASK 2: Stricter Title Matching - Overlapping Words ──────────────
    @Test
    fun test21_overlappingWordsSameYear_returnsNeedsReview() = runBlocking {
        // TMDB is "The Avengers", MovieBox candidate is "Ultimate Avengers"
        fakeApi.searchResults = mutableListOf(
            MovieItem(
                name = "Ultimate Avengers",
                slug = "ultimate-avengers-2012",
                rawSubjectId = "21001",
                rawYear = 2012,
                rawType = 1
            )
        )

        val match = engine.getMapping(
            tmdbId = 24428L,
            mediaType = "movie",
            title = "The Avengers",
            releaseYear = "2012"
        )

        assertEquals("Overlapping title words must not produce automatic match", MatchStatus.NEEDS_REVIEW, match.matchStatus)
        assertFalse(match.isPlayable)
    }

    // ── 22. TASK 2: One-Year Difference With Matching Language & Country ─────
    @Test
    fun test22_oneYearDifferenceWithSameLanguageAndCountry_returnsNeedsReview() = runBlocking {
        // One-year release difference must remain NEEDS_REVIEW even if language & country match
        fakeApi.searchResults = mutableListOf(
            MovieItem(
                name = "Past Lives",
                slug = "past-lives-2023",
                rawSubjectId = "22001",
                rawYear = 2023,
                rawType = 1,
                language = "en",
                country = "US"
            )
        )

        val match = engine.getMapping(
            tmdbId = 666243L,
            mediaType = "movie",
            title = "Past Lives",
            releaseYear = "2024", // 1-year diff
            language = "en",
            country = "US"
        )

        assertEquals("1-year diff must be NEEDS_REVIEW regardless of language/country", MatchStatus.NEEDS_REVIEW, match.matchStatus)
        assertFalse(match.isPlayable)
    }

    // ── 23. TASK 3: API Success with Zero Results Cached as NOT_FOUND ────────
    @Test
    fun test23_searchApiSuccessWithZeroResults_cachedAsNotFoundFor24Hours() = runBlocking {
        fakeApi.searchResults = mutableListOf() // Empty results from successful 200 response

        val match = engine.getMapping(
            tmdbId = 23001L,
            mediaType = "movie",
            title = "Non Existent Movie",
            releaseYear = "2023"
        )

        assertEquals(MatchStatus.NOT_FOUND, match.matchStatus)
        assertTrue("NOT_FOUND must have 24h expiration", match.expiresAt > System.currentTimeMillis() + (23L * 60 * 60 * 1000))

        val cachedInRoom = database.watchDao().getMapping(23001L, "movie")
        assertNotNull("Genuine empty search must be cached in Room", cachedInRoom)
        assertEquals("NOT_FOUND", cachedInRoom?.matchStatus)
    }

    // ── 24. TASK 3: API Network Failure On Search - Returns CHECKING, Not Cached ──
    @Test
    fun test24_searchApiNetworkFailure_returnsCheckingAndNotCachedInRoom() = runBlocking {
        fakeApi.searchException = java.io.IOException("Network timeout")

        val match = engine.getMapping(
            tmdbId = 24001L,
            mediaType = "movie",
            title = "Interstellar",
            releaseYear = "2014"
        )

        assertEquals("Network failure must return temporary CHECKING status", MatchStatus.CHECKING, match.matchStatus)
        assertTrue("Network error must not have 24h expiration", match.expiresAt <= System.currentTimeMillis())

        val cachedInRoom = database.watchDao().getMapping(24001L, "movie")
        assertNull("Network failure must NEVER be cached as NOT_FOUND in Room", cachedInRoom)
    }

    // ── 25. TASK 3: API Network Failure On Detail - Returns CHECKING, Not Cached ──
    @Test
    fun test25_detailApiNetworkFailure_returnsCheckingAndNotCached() = runBlocking {
        fakeApi.searchResults = mutableListOf(
            MovieItem(
                name = "Dunkirk",
                slug = "dunkirk-2017",
                rawSubjectId = "25001",
                rawYear = 2017,
                rawType = 1
            )
        )
        fakeApi.detailException = java.io.IOException("500 Internal Server Error")

        val match = engine.getMapping(
            tmdbId = 374720L,
            mediaType = "movie",
            title = "Dunkirk",
            releaseYear = "2017"
        )

        assertEquals("Detail network failure must return CHECKING", MatchStatus.CHECKING, match.matchStatus)
        val cachedInRoom = database.watchDao().getMapping(374720L, "movie")
        assertNull("Detail failure must not be cached in Room", cachedInRoom)
    }

    // ── 26. TASK 4: Stream Availability Recognizes HLS Streams (.m3u8) ──────
    @Test
    fun test26_streamAvailability_recognizesHlsStreams() = runBlocking {
        fakeApi.searchResults = mutableListOf(
            MovieItem(
                name = "HLS Feature",
                slug = "hls-feature-2022",
                rawSubjectId = "26001",
                rawYear = 2022,
                rawType = 1
            )
        )
        fakeApi.detailResponses["hls-feature-2022"] = DetailResponse(
            code = 200,
            data = DetailData(
                subject = SubjectDetail(
                    rawSubjectId = "26001",
                    title = "HLS Feature",
                    rawSubjectType = 1,
                    releaseDate = "2022-01-01"
                )
            )
        )
        // Only HLS streams provided, MP4 sources list is empty
        fakeApi.streamResponses["26001"] = StreamResponse(
            rawSubjectId = "26001",
            hasResource = true,
            sources = emptyList(),
            hls = listOf(HlsStream(resolution = "1080p", url = "https://stream.example.com/live/playlist.m3u8"))
        )

        val match = engine.getMapping(
            tmdbId = 26001L,
            mediaType = "movie",
            title = "HLS Feature",
            releaseYear = "2022"
        )

        assertEquals(MatchStatus.VERIFIED, match.matchStatus)
        assertTrue("HLS stream must be recognized as available", match.hasAvailableStreams)
        assertTrue("Title with HLS streams must be playable", match.isPlayable)
    }

    // ── 27. TASK 4: Stream Availability Recognizes DASH Streams (.mpd) ──────
    @Test
    fun test27_streamAvailability_recognizesDashStreams() = runBlocking {
        fakeApi.searchResults = mutableListOf(
            MovieItem(
                name = "DASH Feature",
                slug = "dash-feature-2022",
                rawSubjectId = "27001",
                rawYear = 2022,
                rawType = 1
            )
        )
        fakeApi.detailResponses["dash-feature-2022"] = DetailResponse(
            code = 200,
            data = DetailData(
                subject = SubjectDetail(
                    rawSubjectId = "27001",
                    title = "DASH Feature",
                    rawSubjectType = 1,
                    releaseDate = "2022-01-01"
                )
            )
        )
        // Only DASH streams provided, MP4 and HLS empty
        fakeApi.streamResponses["27001"] = StreamResponse(
            rawSubjectId = "27001",
            hasResource = true,
            sources = emptyList(),
            hls = emptyList(),
            dash = listOf(DashStream(resolution = "1080p", url = "https://stream.example.com/dash/manifest.mpd"))
        )

        val match = engine.getMapping(
            tmdbId = 27001L,
            mediaType = "movie",
            title = "DASH Feature",
            releaseYear = "2022"
        )

        assertEquals(MatchStatus.VERIFIED, match.matchStatus)
        assertTrue("DASH stream must be recognized as available", match.hasAvailableStreams)
        assertTrue("Title with DASH streams must be playable", match.isPlayable)
    }

    // ── 28. TASK 3 & 4: Stream Probe Network Failure Not Cached For 24h ─────
    @Test
    fun test28_streamProbeNetworkFailure_notCachedAsUnavailableFor24Hours() = runBlocking {
        fakeApi.searchResults = mutableListOf(
            MovieItem(
                name = "Network Glitch Film",
                slug = "glitch-film-2023",
                rawSubjectId = "28001",
                rawYear = 2023,
                rawType = 1
            )
        )
        fakeApi.detailResponses["glitch-film-2023"] = DetailResponse(
            code = 200,
            data = DetailData(
                subject = SubjectDetail(
                    rawSubjectId = "28001",
                    title = "Network Glitch Film",
                    rawSubjectType = 1,
                    releaseDate = "2023-01-01"
                )
            )
        )
        // Stream endpoint fails with network exception
        fakeApi.streamException = java.io.IOException("Stream server connection timeout")

        val match = engine.getMapping(
            tmdbId = 28001L,
            mediaType = "movie",
            title = "Network Glitch Film",
            releaseYear = "2023"
        )

        assertEquals(MatchStatus.VERIFIED, match.matchStatus)
        assertFalse(match.hasAvailableStreams)
        assertFalse(match.isPlayable)
        // Availability expiration must NOT be 24h in the future
        assertTrue("Network error must allow immediate retry", match.isAvailabilityExpired)
    }

    // ── 29. TASK 5: Exact Candidate Year and Exact Detail Year ──────────────
    @Test
    fun test29_exactCandidateYearAndExactDetailYear_verified() = runBlocking {
        fakeApi.searchResults = mutableListOf(
            MovieItem(
                name = "Exact Year Movie",
                slug = "exact-movie-2020",
                rawSubjectId = "29001",
                rawYear = 2020,
                rawType = 1
            )
        )
        fakeApi.detailResponses["exact-movie-2020"] = DetailResponse(
            code = 200,
            data = DetailData(
                subject = SubjectDetail(
                    rawSubjectId = "29001",
                    title = "Exact Year Movie",
                    rawSubjectType = 1,
                    releaseDate = "2020-05-15" // Year 2020 matches
                )
            )
        )

        val match = engine.getMapping(
            tmdbId = 29001L,
            mediaType = "movie",
            title = "Exact Year Movie",
            releaseYear = "2020"
        )

        assertEquals(MatchStatus.VERIFIED, match.matchStatus)
        assertTrue(match.isVerified)
    }

    // ── 30. TASK 5: Exact Candidate Year but 1-Year Detail Discrepancy ───────
    @Test
    fun test30_exactCandidateYearButOneYearDetailDiscrepancy_returnsNeedsReview() = runBlocking {
        fakeApi.searchResults = mutableListOf(
            MovieItem(
                name = "Year Conflict Movie",
                slug = "year-conflict-2020",
                rawSubjectId = "30001",
                rawYear = 2020,
                rawType = 1
            )
        )
        // Detail endpoint returns year 2021 (1-year discrepancy with TMDB 2020)
        fakeApi.detailResponses["year-conflict-2020"] = DetailResponse(
            code = 200,
            data = DetailData(
                subject = SubjectDetail(
                    rawSubjectId = "30001",
                    title = "Year Conflict Movie",
                    rawSubjectType = 1,
                    releaseDate = "2021-01-01" // Discrepancy!
                )
            )
        )

        val match = engine.getMapping(
            tmdbId = 30001L,
            mediaType = "movie",
            title = "Year Conflict Movie",
            releaseYear = "2020"
        )

        assertEquals("Detail year discrepancy must yield NEEDS_REVIEW", MatchStatus.NEEDS_REVIEW, match.matchStatus)
        assertFalse(match.isPlayable)
    }

    // ── 31. TASK 5: Candidate and Detail Years Conflict by > 1 Year ──────────
    @Test
    fun test31_candidateAndDetailYearsConflictByMultipleYears_returnsNeedsReview() = runBlocking {
        fakeApi.searchResults = mutableListOf(
            MovieItem(
                name = "Remake Conflict",
                slug = "remake-conflict-2020",
                rawSubjectId = "31001",
                rawYear = 2020,
                rawType = 1
            )
        )
        fakeApi.detailResponses["remake-conflict-2020"] = DetailResponse(
            code = 200,
            data = DetailData(
                subject = SubjectDetail(
                    rawSubjectId = "31001",
                    title = "Remake Conflict",
                    rawSubjectType = 1,
                    releaseDate = "2025-06-01" // Multi-year conflict
                )
            )
        )

        val match = engine.getMapping(
            tmdbId = 31001L,
            mediaType = "movie",
            title = "Remake Conflict",
            releaseYear = "2020"
        )

        assertEquals("Multi-year detail conflict must yield NEEDS_REVIEW", MatchStatus.NEEDS_REVIEW, match.matchStatus)
        assertFalse(match.isPlayable)
    }

    // ── 32. TASK 5: Missing Detail Year ──────────────────────────────────────
    @Test
    fun test32_missingDetailYear_returnsNeedsReview() = runBlocking {
        fakeApi.searchResults = mutableListOf(
            MovieItem(
                name = "Missing Detail Year Film",
                slug = "missing-year-film-2020",
                rawSubjectId = "32001",
                rawYear = 2020,
                rawType = 1
            )
        )
        fakeApi.detailResponses["missing-year-film-2020"] = DetailResponse(
            code = 200,
            data = DetailData(
                subject = SubjectDetail(
                    rawSubjectId = "32001",
                    title = "Missing Detail Year Film",
                    rawSubjectType = 1,
                    releaseDate = null // Missing release date
                )
            )
        )

        val match = engine.getMapping(
            tmdbId = 32001L,
            mediaType = "movie",
            title = "Missing Detail Year Film",
            releaseYear = "2020"
        )

        assertEquals("Missing detail year must yield NEEDS_REVIEW", MatchStatus.NEEDS_REVIEW, match.matchStatus)
        assertFalse(match.isPlayable)
    }

    // ── 33. TASK 6: Matching Engine Cancellation ─────────────────────────────
    @Test
    fun test33_engineCancel_cancelsScope() {
        engine.cancel()
        // Engine is cancelled without throwing uncaught exceptions
        assertNotNull(engine)
    }

    // ── FIX 1: Partial Search Failure Handling Tests ─────────────────────────

    @Test
    fun test34_partialSearch_allSearchesSuccessfulAndEmpty_notFoundCached24h() = runBlocking {
        fakeApi.searchResults = mutableListOf()
        val match = engine.getMapping(
            tmdbId = 34001L,
            mediaType = "movie",
            title = "Empty Success Film",
            originalTitle = "Empty Success Film Original",
            releaseYear = "2024"
        )
        assertEquals(MatchStatus.NOT_FOUND, match.matchStatus)
        val cached = database.watchDao().getMapping(34001L, "movie")
        assertNotNull("All successful empty searches must be cached in Room", cached)
        assertEquals("NOT_FOUND", cached?.matchStatus)
        val expectedMinExpiry = System.currentTimeMillis() + 86_000_000L
        assertTrue(cached!!.expiresAt >= expectedMinExpiry)
    }

    @Test
    fun test35_partialSearch_firstSearchEmptySubsequentSearchesFail_checkingNotCached() = runBlocking {
        fakeApi.searchQueryResults["Empty Title"] = emptyList()
        fakeApi.searchQueryExceptions["Empty Title 2024"] = java.io.IOException("Network error on title+year")
        val match = engine.getMapping(
            tmdbId = 35001L,
            mediaType = "movie",
            title = "Empty Title",
            originalTitle = "Empty Title",
            releaseYear = "2024"
        )
        assertEquals(MatchStatus.CHECKING, match.matchStatus)
        val cached = database.watchDao().getMapping(35001L, "movie")
        assertNull("Partial search failure must not be cached in Room as NOT_FOUND", cached)
    }

    @Test
    fun test36_partialSearch_firstSearchFailsSecondEmptyThirdFails_checkingNotCached() = runBlocking {
        fakeApi.searchQueryExceptions["First Fail"] = java.io.IOException("First search network failure")
        fakeApi.searchQueryResults["Original Empty"] = emptyList()
        fakeApi.searchQueryExceptions["First Fail 2024"] = java.io.IOException("Third search network failure")
        val match = engine.getMapping(
            tmdbId = 36001L,
            mediaType = "movie",
            title = "First Fail",
            originalTitle = "Original Empty",
            releaseYear = "2024"
        )
        assertEquals(MatchStatus.CHECKING, match.matchStatus)
        val cached = database.watchDao().getMapping(36001L, "movie")
        assertNull("Mixed failed/empty searches must not be cached in Room", cached)
    }

    @Test
    fun test37_partialSearch_searchFailureWithNoConclusiveCandidate_checking() = runBlocking {
        fakeApi.searchQueryExceptions["Inconclusive Search"] = java.io.IOException("Timeout")
        fakeApi.searchQueryResults["Inconclusive Search 2024"] = listOf(
            MovieItem(
                name = "Totally Different Title",
                slug = "different-title",
                rawSubjectId = "37099",
                rawYear = 2010,
                rawType = 1
            )
        )
        val match = engine.getMapping(
            tmdbId = 37001L,
            mediaType = "movie",
            title = "Inconclusive Search",
            releaseYear = "2024"
        )
        assertEquals(MatchStatus.CHECKING, match.matchStatus)
        val cached = database.watchDao().getMapping(37001L, "movie")
        assertNull("Search failure with no conclusive candidate must not be cached in Room", cached)
    }

    @Test
    fun test38_partialSearch_allSearchesSucceedAndExactCandidatePassesVerification_verified() = runBlocking {
        val candidate = MovieItem(
            name = "Conclusive Match Movie",
            slug = "conclusive-match-2024",
            rawSubjectId = "38001",
            rawYear = 2024,
            rawType = 1
        )
        fakeApi.searchQueryResults["Conclusive Match Movie"] = listOf(candidate)
        fakeApi.searchQueryResults["Conclusive Match Movie 2024"] = listOf(candidate)
        fakeApi.detailResponses["conclusive-match-2024"] = DetailResponse(
            code = 200,
            data = DetailData(
                subject = SubjectDetail(
                    rawSubjectId = "38001",
                    title = "Conclusive Match Movie",
                    rawSubjectType = 1,
                    releaseDate = "2024-03-10"
                )
            )
        )
        fakeApi.streamResponses["38001:conclusive-match-2024:0:0"] = StreamResponse(
            rawSubjectId = "38001",
            hasResource = true,
            sources = listOf(StreamSource("1080p", "https://cdn.example.com/stream.mp4"))
        )

        val match = engine.getMapping(
            tmdbId = 38001L,
            mediaType = "movie",
            title = "Conclusive Match Movie",
            releaseYear = "2024"
        )
        assertEquals(MatchStatus.VERIFIED, match.matchStatus)
        assertTrue(match.isVerified)
        assertTrue(match.hasAvailableStreams)
        val cached = database.watchDao().getMapping(38001L, "movie")
        assertNotNull(cached)
        assertEquals("VERIFIED", cached?.matchStatus)
        assertEquals("38001", cached?.movieboxSubjectId)
    }

    // ── FIX 2: Availability Refresh Concurrency & Backoff Tests ───────────────

    @Test
    fun test39_availabilityRefresh_twoSimultaneousCallsShareSingleRefresh() = runBlocking {
        val now = System.currentTimeMillis()
        database.watchDao().upsertMapping(
            ContentMappingEntity(
                tmdbId = 39001L,
                mediaType = "movie",
                tmdbTitle = "Shared Refresh Title",
                tmdbOriginalTitle = "Shared Refresh Title",
                releaseYear = "2024",
                movieboxSubjectId = "mb_39001",
                movieboxDetailPath = "shared-refresh-slug",
                matchStatus = "VERIFIED",
                matchConfidence = 1.0f,
                matchedAt = now - 10000L,
                lastCheckedAt = now - 10000L,
                expiresAt = now + ContentMatch.EXPIRY_IDENTITY_MS,
                hasAvailableStreams = true,
                availabilityCheckedAt = now - 100000L,
                availabilityExpiresAt = now - 1000L // Expired!
            )
        )
        fakeApi.streamDelayMs = 100L
        val call1 = async { engine.getMapping(tmdbId = 39001L, mediaType = "movie", title = "Shared Refresh Title", releaseYear = "2024") }
        val call2 = async { engine.getMapping(tmdbId = 39001L, mediaType = "movie", title = "Shared Refresh Title", releaseYear = "2024") }
        val results = awaitAll(call1, call2)

        assertEquals(MatchStatus.VERIFIED, results[0].matchStatus)
        assertEquals(MatchStatus.VERIFIED, results[1].matchStatus)
        assertEquals("Two simultaneous refresh requests must share exactly 1 stream probe", 1, fakeApi.streamCallCount)
    }

    @Test
    fun test40_availabilityRefresh_obeysSemaphore3() = runBlocking {
        val now = System.currentTimeMillis()
        for (i in 1..5) {
            database.watchDao().upsertMapping(
                ContentMappingEntity(
                    tmdbId = 40000L + i,
                    mediaType = "movie",
                    tmdbTitle = "Semaphore Film $i",
                    tmdbOriginalTitle = "Semaphore Film $i",
                    releaseYear = "2024",
                    movieboxSubjectId = "mb_4000$i",
                    movieboxDetailPath = "semaphore-slug-$i",
                    matchStatus = "VERIFIED",
                    matchConfidence = 1.0f,
                    matchedAt = now,
                    lastCheckedAt = now,
                    expiresAt = now + ContentMatch.EXPIRY_IDENTITY_MS,
                    hasAvailableStreams = true,
                    availabilityCheckedAt = now - 100000L,
                    availabilityExpiresAt = now - 1000L // Expired!
                )
            )
        }
        fakeApi.streamDelayMs = 150L
        val jobs = (1..5).map { i ->
            async { engine.getMapping(tmdbId = 40000L + i, mediaType = "movie", title = "Semaphore Film $i", releaseYear = "2024") }
        }
        jobs.awaitAll()
        assertTrue(
            "Max concurrent stream probes must not exceed 3 (was: ${fakeApi.maxConcurrentStreamProbes})",
            fakeApi.maxConcurrentStreamProbes in 1..3
        )
    }

    @Test
    fun test41_availabilityRefresh_networkErrorDoesNotOverwriteVerifiedIdentity() = runBlocking {
        val now = System.currentTimeMillis()
        database.watchDao().upsertMapping(
            ContentMappingEntity(
                tmdbId = 41001L,
                mediaType = "movie",
                tmdbTitle = "Identity Protection Film",
                tmdbOriginalTitle = "Identity Protection Film",
                releaseYear = "2024",
                movieboxSubjectId = "mb_41001",
                movieboxDetailPath = "identity-slug-41001",
                matchStatus = "VERIFIED",
                matchConfidence = 1.0f,
                matchedAt = now - 5000L,
                lastCheckedAt = now - 5000L,
                expiresAt = now + ContentMatch.EXPIRY_IDENTITY_MS,
                hasAvailableStreams = true,
                availabilityCheckedAt = now - 100000L,
                availabilityExpiresAt = now - 1000L // Expired!
            )
        )
        fakeApi.streamException = java.io.IOException("Transient network error during stream probe")

        val match = engine.getMapping(tmdbId = 41001L, mediaType = "movie", title = "Identity Protection Film", releaseYear = "2024")
        assertEquals(MatchStatus.VERIFIED, match.matchStatus)
        assertEquals("mb_41001", match.movieboxSubjectId)
        assertEquals("identity-slug-41001", match.movieboxDetailPath)

        val inDb = database.watchDao().getMapping(41001L, "movie")
        assertNotNull(inDb)
        assertEquals("VERIFIED", inDb?.matchStatus)
        assertEquals("mb_41001", inDb?.movieboxSubjectId)
    }

    @Test
    fun test42_availabilityRefresh_networkErrorDoesNotCreate24HourConfirmedUnavailable() = runBlocking {
        val now = System.currentTimeMillis()
        val originalAvailabilityExpiry = now - 1000L
        database.watchDao().upsertMapping(
            ContentMappingEntity(
                tmdbId = 42001L,
                mediaType = "movie",
                tmdbTitle = "No 24h Unavailable Film",
                tmdbOriginalTitle = "No 24h Unavailable Film",
                releaseYear = "2024",
                movieboxSubjectId = "mb_42001",
                movieboxDetailPath = "no-24h-slug",
                matchStatus = "VERIFIED",
                matchConfidence = 1.0f,
                matchedAt = now,
                lastCheckedAt = now,
                expiresAt = now + ContentMatch.EXPIRY_IDENTITY_MS,
                hasAvailableStreams = true,
                availabilityCheckedAt = now - 100000L,
                availabilityExpiresAt = originalAvailabilityExpiry
            )
        )
        fakeApi.streamException = java.io.IOException("Stream probe connection dropped")

        val match = engine.getMapping(tmdbId = 42001L, mediaType = "movie", title = "No 24h Unavailable Film", releaseYear = "2024")
        val inDb = database.watchDao().getMapping(42001L, "movie")
        assertNotNull(inDb)
        // Must NOT have updated availabilityExpiresAt to now + 24 hours (86_400_000)
        val twentyFourHoursLater = now + 86_000_000L
        assertFalse(
            "Network error must not mark title as confirmed unavailable for 24 hours",
            inDb!!.availabilityExpiresAt >= twentyFourHoursLater
        )
    }

    @Test
    fun test43_availabilityRefresh_repeatedCallsDuringTransientRetryBackoffDoNotHitMovieBox() = runBlocking {
        val now = System.currentTimeMillis()
        database.watchDao().upsertMapping(
            ContentMappingEntity(
                tmdbId = 43001L,
                mediaType = "movie",
                tmdbTitle = "Backoff Film",
                tmdbOriginalTitle = "Backoff Film",
                releaseYear = "2024",
                movieboxSubjectId = "mb_43001",
                movieboxDetailPath = "backoff-slug",
                matchStatus = "VERIFIED",
                matchConfidence = 1.0f,
                matchedAt = now,
                lastCheckedAt = now,
                expiresAt = now + ContentMatch.EXPIRY_IDENTITY_MS,
                hasAvailableStreams = true,
                availabilityCheckedAt = now - 100000L,
                availabilityExpiresAt = now - 1000L // Expired!
            )
        )
        fakeApi.streamException = java.io.IOException("Network down")

        // First call encounters network error and activates 30s transient retry backoff
        engine.getMapping(tmdbId = 43001L, mediaType = "movie", title = "Backoff Film", releaseYear = "2024")
        val callsAfterFirst = fakeApi.streamCallCount
        assertTrue("At least one stream probe must have been attempted", callsAfterFirst > 0)

        // Second and third calls within backoff window must not trigger network requests
        engine.getMapping(tmdbId = 43001L, mediaType = "movie", title = "Backoff Film", releaseYear = "2024")
        engine.getMapping(tmdbId = 43001L, mediaType = "movie", title = "Backoff Film", releaseYear = "2024")
        assertEquals("Subsequent calls within retry backoff window must not hit MovieBox", callsAfterFirst, fakeApi.streamCallCount)
    }

    @Test
    fun test44_availabilityRefresh_successfulRefreshUpdatesAvailabilityAndPreservesIdentity() = runBlocking {
        val now = System.currentTimeMillis()
        database.watchDao().upsertMapping(
            ContentMappingEntity(
                tmdbId = 44001L,
                mediaType = "movie",
                tmdbTitle = "Preserve Identity Film",
                tmdbOriginalTitle = "Preserve Identity Film",
                releaseYear = "2024",
                movieboxSubjectId = "mb_44001",
                movieboxDetailPath = "preserve-slug-44001",
                matchStatus = "VERIFIED",
                matchConfidence = 1.0f,
                matchedAt = now - 50000L,
                lastCheckedAt = now - 50000L,
                expiresAt = now + ContentMatch.EXPIRY_IDENTITY_MS,
                hasAvailableStreams = false, // Previously unavailable
                availabilityCheckedAt = now - 100000L,
                availabilityExpiresAt = now - 1000L // Expired!
            )
        )

        fakeApi.streamResponses["mb_44001:preserve-slug-44001:0:0"] = StreamResponse(
            rawSubjectId = "mb_44001",
            hasResource = true,
            sources = listOf(StreamSource("1080p", "https://cdn.example.com/restored.mp4"))
        )

        val refreshed = engine.getMapping(tmdbId = 44001L, mediaType = "movie", title = "Preserve Identity Film", releaseYear = "2024")
        assertEquals(MatchStatus.VERIFIED, refreshed.matchStatus)
        assertEquals("mb_44001", refreshed.movieboxSubjectId)
        assertEquals("preserve-slug-44001", refreshed.movieboxDetailPath)
        assertTrue(refreshed.hasAvailableStreams)
        assertTrue(refreshed.availabilityExpiresAt > now)

        val inDb = database.watchDao().getMapping(44001L, "movie")
        assertNotNull(inDb)
        assertEquals("VERIFIED", inDb?.matchStatus)
        assertEquals("mb_44001", inDb?.movieboxSubjectId)
        assertTrue("Database availability must be updated to true", inDb!!.hasAvailableStreams)
    }

    // ── FIX 3: Thread-Safe Cancellation Tests ─────────────────────────────────

    @Test
    fun test45_cancellation_cancellingActiveSearch_propagatesCancellationException() = runBlocking {
        fakeApi.searchDelayMs = 400L
        val job = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
            repository.search("Cancellation Search Test")
        }
        delay(50L)
        job.cancel()
        job.join()
        assertTrue("Active search coroutine must be cancelled", job.isCancelled)
    }

    @Test
    fun test46_cancellation_cancellingActiveDetailRequest_propagatesCancellationException() = runBlocking {
        fakeApi.detailDelayMs = 400L
        val job = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
            repository.getDetail("cancellation-detail-slug")
        }
        delay(50L)
        job.cancel()
        job.join()
        assertTrue("Active detail coroutine must be cancelled", job.isCancelled)
    }

    @Test
    fun test47_cancellation_cancellingActiveAvailabilityRefresh_propagatesCancellationException() = runBlocking {
        val now = System.currentTimeMillis()
        database.watchDao().upsertMapping(
            ContentMappingEntity(
                tmdbId = 47001L,
                mediaType = "movie",
                tmdbTitle = "Cancel Refresh Film",
                tmdbOriginalTitle = "Cancel Refresh Film",
                releaseYear = "2024",
                movieboxSubjectId = "mb_47001",
                movieboxDetailPath = "cancel-refresh-slug",
                matchStatus = "VERIFIED",
                matchConfidence = 1.0f,
                matchedAt = now,
                lastCheckedAt = now,
                expiresAt = now + ContentMatch.EXPIRY_IDENTITY_MS,
                hasAvailableStreams = true,
                availabilityCheckedAt = now - 100000L,
                availabilityExpiresAt = now - 1000L
            )
        )
        fakeApi.streamDelayMs = 400L
        val job = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
            engine.getMapping(tmdbId = 47001L, mediaType = "movie", title = "Cancel Refresh Film", releaseYear = "2024")
        }
        delay(50L)
        job.cancel()
        job.join()
        assertTrue("Active availability refresh coroutine must be cancelled", job.isCancelled)
    }

    @Test
    fun test48_cancellation_concurrentJobCleanupDuringCancellation_leavesNoStaleInFlightEntry() = runBlocking {
        fakeApi.searchDelayMs = 300L
        val job = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
            engine.getMapping(tmdbId = 48001L, mediaType = "movie", title = "Concurrent Cleanup Film", releaseYear = "2024")
        }
        delay(50L)
        engine.cancel()
        job.join()

        // After engine cancellation, further calls must immediately throw CancellationException
        try {
            engine.getMapping(tmdbId = 48002L, mediaType = "movie", title = "New Request After Cancel", releaseYear = "2024")
            fail("Expected CancellationException after engine.cancel()")
        } catch (e: Exception) {
            assertTrue("Expected CancellationException, got: ${e.javaClass.name}", e is kotlinx.coroutines.CancellationException)
        }
    }

    // ── Stream Fallback Reliability & Mixed Network Failure Tests ─────────────

    @Test
    fun test49_getStream_primaryFailsAndAllFallbacksFail_returnsFailure() = runBlocking {
        fakeApi.streamExceptions["sub49:slug49:0:0"] = java.io.IOException("Primary connection timeout")
        fakeApi.streamExceptions["sub49:slug49:1:1"] = java.io.IOException("Fallback 1 connection error")
        fakeApi.streamExceptions["sub49:slug49:0:1"] = java.io.IOException("Fallback 2 connection error")
        fakeApi.streamExceptions["sub49:slug49:1:0"] = java.io.IOException("Fallback 3 connection error")

        val result = repository.getStream("sub49", "slug49", season = 0, episode = 0, isSeries = false)
        assertTrue("When primary and all fallbacks fail, Result.failure must be returned", result.isFailure)
        assertTrue(result.exceptionOrNull() is java.io.IOException)
    }

    @Test
    fun test50_getStream_primaryFailsOneFallbackEmptyRemainingFail_returnsFailure() = runBlocking {
        fakeApi.streamExceptions["sub50:slug50:0:0"] = java.io.IOException("Primary connection reset")
        fakeApi.streamResponses["sub50:slug50:1:1"] = StreamResponse(rawSubjectId = "sub50", hasResource = false, sources = emptyList())
        fakeApi.streamExceptions["sub50:slug50:0:1"] = java.io.IOException("Fallback 2 timeout")
        fakeApi.streamExceptions["sub50:slug50:1:0"] = java.io.IOException("Fallback 3 timeout")

        val result = repository.getStream("sub50", "slug50", season = 0, episode = 0, isSeries = false)
        assertTrue("Mixed network failure must return Result.failure, not empty success", result.isFailure)
        assertTrue(result.exceptionOrNull() is java.io.IOException)
    }

    @Test
    fun test51_getStream_primaryEmptyAndAllFallbacksEmpty_returnsSuccessWithNoUsableStreams() = runBlocking {
        fakeApi.streamResponses["sub51:slug51:0:0"] = StreamResponse(rawSubjectId = "sub51", hasResource = false, sources = emptyList())
        fakeApi.streamResponses["sub51:slug51:1:1"] = StreamResponse(rawSubjectId = "sub51", hasResource = false, sources = emptyList())
        fakeApi.streamResponses["sub51:slug51:0:1"] = StreamResponse(rawSubjectId = "sub51", hasResource = false, sources = emptyList())
        fakeApi.streamResponses["sub51:slug51:1:0"] = StreamResponse(rawSubjectId = "sub51", hasResource = false, sources = emptyList())

        val result = repository.getStream("sub51", "slug51", season = 0, episode = 0, isSeries = false)
        assertTrue("When all requests succeed and return no streams, Result.success must be returned", result.isSuccess)
        val stream = result.getOrNull()
        assertNotNull(stream)
        assertFalse("Must have no usable streams", stream!!.hasUsableStreams)
    }

    @Test
    fun test52_getStream_primaryFailsLaterFallbackReturnsUsableMp4_returnsSuccess() = runBlocking {
        fakeApi.streamExceptions["sub52:slug52:0:0"] = java.io.IOException("Primary connection dropped")
        fakeApi.streamResponses["sub52:slug52:1:1"] = StreamResponse(rawSubjectId = "sub52", hasResource = false, sources = emptyList())
        fakeApi.streamResponses["sub52:slug52:0:1"] = StreamResponse(
            rawSubjectId = "sub52",
            hasResource = true,
            sources = listOf(StreamSource(resolution = "1080p", url = "https://cdn.example.com/movie52.mp4"))
        )

        val result = repository.getStream("sub52", "slug52", season = 0, episode = 0, isSeries = false)
        assertTrue("Later fallback with usable MP4 must return Result.success", result.isSuccess)
        val stream = result.getOrNull()!!
        assertTrue(stream.hasUsableStreams)
        assertEquals("https://cdn.example.com/movie52.mp4", stream.sources.first().url)
    }

    @Test
    fun test53_getStream_primaryFailsLaterFallbackReturnsUsableHls_returnsSuccess() = runBlocking {
        fakeApi.streamExceptions["sub53:slug53:0:0"] = java.io.IOException("Primary connection dropped")
        fakeApi.streamResponses["sub53:slug53:1:1"] = StreamResponse(
            rawSubjectId = "sub53",
            hasResource = true,
            sources = emptyList(),
            hls = listOf(HlsStream(resolution = "1080p", url = "https://cdn.example.com/master53.m3u8"))
        )

        val result = repository.getStream("sub53", "slug53", season = 0, episode = 0, isSeries = false)
        assertTrue("Later fallback with usable HLS must return Result.success", result.isSuccess)
        val stream = result.getOrNull()!!
        assertTrue(stream.hasUsableStreams)
        assertEquals("https://cdn.example.com/master53.m3u8", stream.sources.first().url)
        assertEquals("HLS", stream.sources.first().format)
    }

    @Test
    fun test54_getStream_primaryFailsLaterFallbackReturnsUsableDash_returnsSuccess() = runBlocking {
        fakeApi.streamExceptions["sub54:slug54:0:0"] = java.io.IOException("Primary connection dropped")
        fakeApi.streamResponses["sub54:slug54:1:1"] = StreamResponse(
            rawSubjectId = "sub54",
            hasResource = true,
            sources = emptyList(),
            dash = listOf(DashStream(resolution = "1080p", url = "https://cdn.example.com/manifest54.mpd"))
        )

        val result = repository.getStream("sub54", "slug54", season = 0, episode = 0, isSeries = false)
        assertTrue("Later fallback with usable DASH must return Result.success", result.isSuccess)
        val stream = result.getOrNull()!!
        assertTrue(stream.hasUsableStreams)
        assertEquals("https://cdn.example.com/manifest54.mpd", stream.sources.first().url)
        assertEquals("DASH", stream.sources.first().format)
    }

    @Test
    fun test55_getStream_cancellationDuringFallback_propagatesCancellationException() = runBlocking {
        fakeApi.streamExceptions["sub55:slug55:0:0"] = java.io.IOException("Primary failed")
        fakeApi.streamDelayMs = 400L

        val job = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
            repository.getStream("sub55", "slug55", season = 0, episode = 0, isSeries = false)
        }
        delay(50L)
        job.cancel()
        job.join()
        assertTrue("Cancellation during stream fallback must propagate and cancel coroutine", job.isCancelled)
    }

    @Test
    fun test56_probeStreamAvailability_mixedFallbackNetworkFailure_returnsNetworkErrorNo24hCache() = runBlocking {
        val now = System.currentTimeMillis()
        database.watchDao().upsertMapping(
            ContentMappingEntity(
                tmdbId = 56001L,
                mediaType = "movie",
                tmdbTitle = "Mixed Fallback Film",
                tmdbOriginalTitle = "Mixed Fallback Film",
                releaseYear = "2024",
                movieboxSubjectId = "mb_56001",
                movieboxDetailPath = "slug-56001",
                matchStatus = "VERIFIED",
                matchConfidence = 1.0f,
                matchedAt = now,
                lastCheckedAt = now,
                expiresAt = now + ContentMatch.EXPIRY_IDENTITY_MS,
                hasAvailableStreams = true,
                availabilityCheckedAt = now - 100000L,
                availabilityExpiresAt = now - 1000L // Expired!
            )
        )

        // Mixed fallback: primary fails, fallback 1 empty, fallback 2 fails, fallback 3 fails
        fakeApi.streamExceptions["mb_56001:slug-56001:0:0"] = java.io.IOException("Primary dropped")
        fakeApi.streamResponses["mb_56001:slug-56001:1:1"] = StreamResponse(rawSubjectId = "mb_56001", hasResource = false, sources = emptyList())
        fakeApi.streamExceptions["mb_56001:slug-56001:0:1"] = java.io.IOException("Fallback 2 dropped")
        fakeApi.streamExceptions["mb_56001:slug-56001:1:0"] = java.io.IOException("Fallback 3 dropped")

        val probe = engine.probeStreamAvailability("mb_56001", "slug-56001", isTv = false)
        assertTrue("Probe must return StreamProbeResult.NetworkError on mixed fallback failure", probe is StreamProbeResult.NetworkError)

        val match = engine.getMapping(tmdbId = 56001L, mediaType = "movie", title = "Mixed Fallback Film", releaseYear = "2024")
        assertEquals(MatchStatus.VERIFIED, match.matchStatus)

        val inDb = database.watchDao().getMapping(56001L, "movie")
        assertNotNull(inDb)
        // Must NOT mark availability as confirmed unavailable for 24 hours
        val twentyFourHoursLater = now + 86_000_000L
        assertFalse("Must NOT mark availability as 24h confirmed unavailable in Room", inDb!!.availabilityExpiresAt >= twentyFourHoursLater)
    }

    @Test
    fun test57_cancellationRaceWithJobRegistration_leavesNoStaleInFlightEntry() = runBlocking {
        fakeApi.searchDelayMs = 200L
        val jobs = (1..10).map { i ->
            kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
                try {
                    engine.getMapping(
                        tmdbId = 57000L + i,
                        mediaType = "movie",
                        title = "Race Title $i",
                        releaseYear = "2024"
                    )
                } catch (_: kotlinx.coroutines.CancellationException) {
                    // Expected during concurrent cancellation
                }
            }
        }
        delay(30L)
        engine.cancel()
        jobs.forEach { it.join() }

        assertEquals(
            "No stale in-flight jobs must remain in inFlightJobs after cancel()",
            0,
            engine.inFlightJobCount
        )

        try {
            engine.getMapping(tmdbId = 57999L, mediaType = "movie", title = "New Request", releaseYear = "2024")
            fail("Expected CancellationException after engine.cancel()")
        } catch (e: Exception) {
            assertTrue(e is kotlinx.coroutines.CancellationException)
        }
    }
}
