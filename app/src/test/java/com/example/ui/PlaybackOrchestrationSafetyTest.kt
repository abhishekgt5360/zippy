package com.example.ui

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.local.AppDatabase
import com.example.data.model.*
import com.example.data.remote.MovieApiService
import com.example.data.repository.MovieRepository
import com.example.data.repository.TmdbRepository
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Validates real production playback orchestration behavior against MovieBox streaming APIs.
 *
 * Verifies the 5 required safety cases:
 * A. Native MovieBox item with valid subject ID -> Playback continues normally, getStream() called.
 * B. Unmatched TMDB item -> Playback rejected, getStream() NEVER called.
 * C. TMDB item with verified mapping -> Mapped MovieBox subject ID used, getStream() called with mapped ID.
 * D. TMDB item with missing or invalid mapping -> Playback rejected, getStream() NEVER called.
 * E. TMDB ID must never be sent as MovieBox subject ID -> Playback rejected, getStream() NEVER called.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PlaybackOrchestrationSafetyTest {

    private lateinit var context: Context
    private lateinit var database: AppDatabase
    private lateinit var recordingApi: RecordingMovieApiService
    private lateinit var repository: MovieRepository

    class RecordingMovieApiService : MovieApiService {
        var getStreamCallCount = 0
        var lastSubjectIdPassed: String? = null
        var lastDetailPathPassed: String? = null
        var lastSeasonPassed: Int? = null
        var lastEpisodePassed: Int? = null

        var getDetailCallCount = 0
        var lastDetailSlugPassed: String? = null

        var getCaptionsCallCount = 0
        var lastCaptionsSubjectIdPassed: String? = null

        override suspend fun checkHealth(): HealthResponse = HealthResponse(status = "ok")
        override suspend fun getHome(): HomeResponse = HomeResponse(status = "ok", sections = emptyList())
        override suspend fun getMovies(page: Int, sort: String?, genre: String?, country: String?, year: String?): CatalogResponse = CatalogResponse()
        override suspend fun getTvSeries(page: Int, sort: String?, genre: String?, country: String?, year: String?): CatalogResponse = CatalogResponse()
        override suspend fun getAnimation(page: Int, sort: String?, genre: String?): CatalogResponse = CatalogResponse()
        override suspend fun search(query: String, page: Int): SearchResponse = SearchResponse()
        override suspend fun getSuggestions(query: String): SuggestResponse = SuggestResponse()

        override suspend fun getDetail(slug: String): DetailResponse {
            getDetailCallCount++
            lastDetailSlugPassed = slug
            return DetailResponse(
                code = 200,
                data = DetailData(
                    subject = SubjectDetail(
                        rawSubjectId = "mb_detail_id",
                        title = "Detail Title",
                        rawSubjectType = 1
                    )
                )
            )
        }

        override suspend fun getStream(subjectId: String, detailPath: String, season: Int, episode: Int): StreamResponse {
            getStreamCallCount++
            lastSubjectIdPassed = subjectId
            lastDetailPathPassed = detailPath
            lastSeasonPassed = season
            lastEpisodePassed = episode
            return StreamResponse(
                rawSubjectId = subjectId,
                se = season,
                ep = episode,
                hasResource = true,
                sources = listOf(StreamSource(resolution = "1080p", url = "https://stream.example.com/video.mp4"))
            )
        }

        override suspend fun getCaptions(subjectId: String, detailPath: String, season: Int, episode: Int): CaptionsResponse {
            getCaptionsCallCount++
            lastCaptionsSubjectIdPassed = subjectId
            return CaptionsResponse(rawSubjectId = subjectId, captions = emptyList())
        }
    }

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        recordingApi = RecordingMovieApiService()
        repository = MovieRepository(api = recordingApi, database = database, context = context)
    }

    @After
    fun tearDown() {
        database.close()
    }

    // ── CASE A: Native MovieBox item with valid subject ID ───────────────────
    @Test
    fun testCaseA_nativeMovieBoxItem_playbackProceedsNormally() = runBlocking {
        val subjectId = "1001"
        val slug = "inception-2010"

        val result = repository.getStream(subjectId = subjectId, slug = slug, season = 0, episode = 0)

        assertTrue("Native MovieBox item stream request must succeed", result.isSuccess)
        assertEquals("getStream() must be called on MovieBox API", 1, recordingApi.getStreamCallCount)
        assertEquals("Exact native subjectId must reach MovieBox API", subjectId, recordingApi.lastSubjectIdPassed)
        assertEquals("Exact native slug must reach MovieBox API", slug, recordingApi.lastDetailPathPassed)
    }

    // ── CASE B: Unmatched TMDB item ──────────────────────────────────────────
    @Test
    fun testCaseB_unmatchedTmdbItem_playbackRejected_getStreamNeverCalled() = runBlocking {
        val tmdbMovie = TmdbMovieItem(
            id = 550L,
            title = "Fight Club",
            mediaType = "movie"
        ).toMovieItem()

        // Unmatched TMDB item has subjectId == "" and slug == "tmdb_movie_550"
        assertEquals("", tmdbMovie.subjectId)
        assertEquals("tmdb_movie_550", tmdbMovie.slug)
        assertFalse("Unmatched item cannot initiate playback", tmdbMovie.canInitiatePlayback)

        val result = repository.getStream(
            subjectId = tmdbMovie.subjectId,
            slug = tmdbMovie.safeSlug,
            season = 0,
            episode = 0
        )

        assertTrue("Unmatched TMDB stream request must be rejected", result.isFailure)
        assertEquals("MovieBox getStream() API must NEVER be called for unmatched TMDB item", 0, recordingApi.getStreamCallCount)
        assertNull(recordingApi.lastSubjectIdPassed)
    }

    // ── CASE C: TMDB item with a verified mapping ────────────────────────────
    @Test
    fun testCaseC_tmdbItemWithVerifiedMapping_usesMappedMovieBoxSubjectId() = runBlocking {
        // TMDB item matched to MovieBox provider by Content Matching Engine
        val verifiedMappedSubjectId = "987654"
        val verifiedMappedSlug = "fight-club-1999"

        val verifiedItem = MovieItem(
            name = "Fight Club",
            posterUrl = "https://image.tmdb.org/t/p/w500/poster.jpg",
            slug = verifiedMappedSlug,
            subjectId = verifiedMappedSubjectId,
            tmdbId = 550L,
            tmdbMediaType = "movie"
        )

        assertTrue("Verified mapped item has legitimate MovieBox ID", verifiedItem.hasMovieBoxSubjectId)
        assertTrue("Verified mapped item has legitimate MovieBox slug", verifiedItem.hasMovieBoxSlug)
        assertTrue("Verified mapped item can initiate playback", verifiedItem.canInitiatePlayback)

        val result = repository.getStream(
            subjectId = verifiedItem.subjectId,
            slug = verifiedItem.safeSlug,
            season = 0,
            episode = 0
        )

        assertTrue("Verified mapped stream request must succeed", result.isSuccess)
        assertEquals("getStream() must be called once", 1, recordingApi.getStreamCallCount)
        assertEquals("Actual mapped MovieBox subject ID must be supplied to MovieBox API", verifiedMappedSubjectId, recordingApi.lastSubjectIdPassed)
        assertEquals("Actual mapped MovieBox detail path must be supplied to MovieBox API", verifiedMappedSlug, recordingApi.lastDetailPathPassed)
    }

    // ── CASE D: TMDB item with missing or invalid mapping ────────────────────
    @Test
    fun testCaseD_tmdbItemWithMissingOrInvalidMapping_playbackRejected() = runBlocking {
        // Subcase D1: Blank subjectId with real slug
        val res1 = repository.getStream(subjectId = "", slug = "some-movie", season = 0, episode = 0)
        assertTrue("Blank subjectId must fail", res1.isFailure)
        assertEquals(0, recordingApi.getStreamCallCount)

        // Subcase D2: Pseudo TMDB subject ID ("tmdb_movie_550")
        val res2 = repository.getStream(subjectId = "tmdb_movie_550", slug = "some-movie", season = 0, episode = 0)
        assertTrue("TMDB prefixed subjectId must fail", res2.isFailure)
        assertEquals(0, recordingApi.getStreamCallCount)

        // Subcase D3: Valid subject ID but TMDB slug ("tmdb_movie_550")
        val res3 = repository.getStream(subjectId = "1001", slug = "tmdb_movie_550", season = 0, episode = 0)
        assertTrue("TMDB slug must fail", res3.isFailure)
        assertEquals(0, recordingApi.getStreamCallCount)

        // Subcase D4: Blank slug
        val res4 = repository.getStream(subjectId = "1001", slug = "", season = 0, episode = 0)
        assertTrue("Blank slug must fail", res4.isFailure)
        assertEquals(0, recordingApi.getStreamCallCount)
    }

    // ── CASE E: TMDB ID must never be sent as MovieBox subject ID ────────────
    @Test
    fun testCaseE_tmdbIdMustNeverBeSubstitutedAsMovieBoxSubjectId() = runBlocking {
        val tmdbId = 872585L // Oppenheimer TMDB ID
        val tmdbIdString = tmdbId.toString()

        // TMDB-originated item with TMDB slug
        val itemWithTmdbSlug = MovieItem(
            name = "Oppenheimer",
            posterUrl = null,
            slug = "tmdb_movie_$tmdbId",
            subjectId = tmdbIdString,
            tmdbId = tmdbId,
            tmdbMediaType = "movie"
        )

        // TMDB-generated slug must not be allowed to initiate playback
        assertFalse("Playback must not be allowed for TMDB-generated slug", itemWithTmdbSlug.canInitiatePlayback)
        assertFalse("Guard must reject TMDB-generated slug", MovieBoxPlaybackGuard.canInitiatePlayback(tmdbIdString, "tmdb_movie_$tmdbId"))

        // When slug is TMDB-style slug, stream request is rejected:
        val res = repository.getStream(subjectId = tmdbIdString, slug = "tmdb_movie_$tmdbId", season = 0, episode = 0)
        assertTrue("TMDB slug must be rejected", res.isFailure)
        assertEquals("MovieBox API must NEVER be invoked with TMDB slug", 0, recordingApi.getStreamCallCount)
    }

    // ── DETAIL ENDPOINT PROTECTION ───────────────────────────────────────────
    @Test
    fun testDetailEndpoint_protectsAgainstTmdbGeneratedSlugs() = runBlocking {
        // TMDB-generated slug must never reach MovieBox /detail/{slug}
        val tmdbSlug = "tmdb_movie_550"
        val result = repository.getDetail(tmdbSlug)

        assertTrue("Detail request with TMDB slug must be rejected", result.isFailure)
        assertEquals("MovieBox getDetail API must NEVER be called with TMDB slug", 0, recordingApi.getDetailCallCount)

        // Real MovieBox slug succeeds
        val realSlug = "fight-club-1999"
        val realResult = repository.getDetail(realSlug)
        assertTrue("Real MovieBox slug detail request succeeds", realResult.isSuccess)
        assertEquals("MovieBox getDetail API called for real slug", 1, recordingApi.getDetailCallCount)
        assertEquals(realSlug, recordingApi.lastDetailSlugPassed)
    }

    // ── CAPTIONS ENDPOINT PROTECTION ─────────────────────────────────────────
    @Test
    fun testCaptionsEndpoint_protectsAgainstUnmatchedTmdbItems() = runBlocking {
        val tmdbSlug = "tmdb_movie_550"
        val result = repository.getCaptions(subjectId = "", slug = tmdbSlug, season = 0, episode = 0)

        assertTrue("Captions request with empty subjectId must be rejected", result.isFailure)
        assertEquals("MovieBox getCaptions API must NEVER be called with invalid IDs", 0, recordingApi.getCaptionsCallCount)
    }

    // ── MAIN VIEW MODEL ORCHESTRATION INTEGRATION ────────────────────────────
    @Test
    fun testMainViewModel_playItem_rejectsUnmatchedTmdbItemsImmediately() {
        val app = ApplicationProvider.getApplicationContext<android.app.Application>()
        val viewModel = MainViewModel(app)

        // Attempting to play unmatched TMDB item
        viewModel.playItem(slug = "tmdb_movie_550", subjectId = "")

        val state = viewModel.detailPlayerState.value
        assertFalse("Playback must not start", state.isPlaying)
        assertFalse("Stream loading must not start", state.isLoadingStream)
        assertEquals("This title is not currently available for streaming.", state.streamError)
    }

    @Test
    fun testMainViewModel_openDetail_doesNotQueryMovieBoxForTmdbSlugs() {
        val app = ApplicationProvider.getApplicationContext<android.app.Application>()
        val viewModel = MainViewModel(app)

        viewModel.openDetail(
            slug = "tmdb_movie_550",
            subjectId = "",
            title = "Fight Club",
            initialType = "movie",
            posterUrl = "https://image.tmdb.org/t/p/w500/poster.jpg"
        )

        val state = viewModel.detailPlayerState.value
        assertEquals("tmdb_movie_550", state.slug)
        assertEquals("Fight Club", state.title)
        assertFalse("isLoadingDetail must be false for TMDB slugs (not queried against MovieBox)", state.isLoadingDetail)
        assertNull("No stream error on opening detail", state.streamError)
    }
}
