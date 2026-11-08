package com.example.data.remote

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.local.AppDatabase
import com.example.data.model.*
import com.example.data.repository.ContentMatchingEngine
import com.example.data.repository.MovieRepository
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MovieBoxStreamDiscoveryTest {

    private lateinit var context: Context
    private lateinit var database: AppDatabase
    private lateinit var fakeApi: FakeMovieBoxApi
    private lateinit var repository: MovieRepository
    private lateinit var engine: ContentMatchingEngine

    companion object {
        // Sanitized fixture based on the confirmed real-world MovieBox Lupin S1E1 response
        val LUPIN_S1E1_STREAM_JSON = """
            {
              "subject_id": "838601903666779168",
              "se": 1,
              "ep": 1,
              "has_resource": true,
              "sources": [
                {
                  "resolution": "360p",
                  "format": "MP4",
                  "url": "https://bcdnxw.hakunaymatata.com/resource/360.mp4?sign=abc123456&t=1793439178",
                  "size": "160562793",
                  "duration": 2919,
                  "codec": "h264"
                },
                {
                  "resolution": "480p",
                  "format": "MP4",
                  "url": "https://bcdnxw.hakunaymatata.com/bt/480.mp4?sign=def789012&t=1793439865",
                  "size": "229762223",
                  "duration": 2919,
                  "codec": "h264"
                },
                {
                  "resolution": "720p",
                  "format": "MP4",
                  "url": "https://bcdnxw.hakunaymatata.com/resource/720.mp4?sign=ghi345678&t=1793438920",
                  "size": "357015005",
                  "duration": 2919,
                  "codec": "h264"
                },
                {
                  "resolution": "1080p",
                  "format": "MP4",
                  "url": "https://bcdnxw.hakunaymatata.com/resource/1080.mp4?sign=jkl901234&t=1793440193",
                  "size": "595533473",
                  "duration": 2919,
                  "codec": "h264"
                }
              ],
              "hls": [],
              "dash": [],
              "free_episodes": 999,
              "limited": false,
              "note": null
            }
        """.trimIndent()
    }

    class FakeMovieBoxApi : MovieApiService {
        var lastRequestedSubjectId: String? = null
        var lastRequestedDetailPath: String? = null
        var lastRequestedSeason: Int = 1
        var lastRequestedEpisode: Int = 1
        var streamToReturn: StreamResponse? = null
        var streamExceptionToThrow: Exception? = null

        override suspend fun checkHealth(): HealthResponse = HealthResponse(status = "ok")
        override suspend fun getHome(): HomeResponse = HomeResponse(status = "ok")
        override suspend fun getMovies(page: Int, sort: String?, genre: String?, country: String?, year: String?): CatalogResponse = CatalogResponse()
        override suspend fun getTvSeries(page: Int, sort: String?, genre: String?, country: String?, year: String?): CatalogResponse = CatalogResponse()
        override suspend fun getAnimation(page: Int, sort: String?, genre: String?): CatalogResponse = CatalogResponse()
        override suspend fun search(query: String, page: Int): SearchResponse = SearchResponse()
        override suspend fun getSuggestions(query: String): SuggestResponse = SuggestResponse()
        override suspend fun getDetail(slug: String): DetailResponse = DetailResponse()

        override suspend fun getStream(subjectId: String, detailPath: String, season: Int, episode: Int): StreamResponse {
            lastRequestedSubjectId = subjectId
            lastRequestedDetailPath = detailPath
            lastRequestedSeason = season
            lastRequestedEpisode = episode

            streamExceptionToThrow?.let { throw it }
            return streamToReturn ?: StreamResponse(
                rawSubjectId = subjectId,
                se = season,
                ep = episode,
                hasResource = false,
                sources = emptyList()
            )
        }

        override suspend fun getCaptions(subjectId: String, detailPath: String, season: Int, episode: Int): CaptionsResponse = CaptionsResponse()
    }

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
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

    // ── Test 1: Lupin S1E1 MP4 parsing ──────────────────────────────────────
    @Test
    fun test1_lupinS1E1_mp4Parsing() {
        val adapter = NetworkClient.moshi.adapter(StreamResponse::class.java)
        val response = adapter.fromJson(LUPIN_S1E1_STREAM_JSON)

        assertNotNull("StreamResponse must parse successfully", response)
        assertEquals("838601903666779168", response?.subjectId)
        assertEquals(1, response?.se)
        assertEquals(1, response?.ep)
        assertTrue("has_resource must be true", response?.hasResource == true)
        assertEquals(4, response?.sources?.size)

        val resolutions = response?.sources?.map { it.resolution } ?: emptyList()
        assertTrue("Must retain 360p", resolutions.contains("360p"))
        assertTrue("Must retain 480p", resolutions.contains("480p"))
        assertTrue("Must retain 720p", resolutions.contains("720p"))
        assertTrue("Must retain 1080p", resolutions.contains("1080p"))

        assertTrue("Empty HLS array must be retained without error", response?.hls?.isEmpty() == true)
        assertTrue("Empty DASH array must be retained without error", response?.dash?.isEmpty() == true)
        assertTrue("Empty HLS/DASH must not invalidate usable MP4 streams", response?.hasUsableStreams == true)
        assertEquals("All 4 MP4 sources must be present in effectiveSources", 4, response?.effectiveSources?.size)
    }

    // ── Test 2: MP4 source selection ────────────────────────────────────────
    @Test
    fun test2_mp4SourceSelection() {
        val adapter = NetworkClient.moshi.adapter(StreamResponse::class.java)
        val response = adapter.fromJson(LUPIN_S1E1_STREAM_JSON)!!
        val sources = response.effectiveSources

        // Select 1080p source
        val selected1080p = sources.firstOrNull { it.resolution.contains("1080", ignoreCase = true) }
        assertNotNull("1080p source must be found", selected1080p)
        assertEquals("MP4", selected1080p?.format)
        assertEquals("video/mp4", selected1080p?.resolveMimeType())
        assertEquals(
            "https://bcdnxw.hakunaymatata.com/resource/1080.mp4?sign=jkl901234&t=1793440193",
            selected1080p?.url
        )
        // Verify signed query parameters are preserved intact
        assertTrue("URL must preserve signed token parameter", selected1080p?.url?.contains("sign=jkl901234") == true)
        assertTrue("URL must preserve timestamp parameter", selected1080p?.url?.contains("t=1793440193") == true)

        // Select 720p source
        val selected720p = sources.firstOrNull { it.resolution.contains("720", ignoreCase = true) }
        assertNotNull("720p source must be selectable", selected720p)
        assertEquals("video/mp4", selected720p?.resolveMimeType())
        assertTrue(selected720p?.url?.contains("sign=ghi345678") == true)
    }

    // ── Test 3: Series episode identity ─────────────────────────────────────
    @Test
    fun test3_seriesEpisodeIdentity() = runBlocking {
        fakeApi.streamToReturn = StreamResponse(
            subjectId = "838601903666779168",
            se = 1,
            ep = 1,
            hasResource = true,
            sources = listOf(
                StreamSource(resolution = "1080p", format = "MP4", url = "https://example.com/s1e1.mp4")
            )
        )

        // Request Season 1 Episode 1
        val resS1E1 = repository.getStream("838601903666779168", "lupin-2rBwMMWNWZ", season = 1, episode = 1, isSeries = true)
        assertTrue(resS1E1.isSuccess)
        assertEquals(1, fakeApi.lastRequestedSeason)
        assertEquals(1, fakeApi.lastRequestedEpisode)
        val s1e1Source = resS1E1.getOrNull()?.sources?.first()
        assertNotNull(s1e1Source)
        assertEquals("https://example.com/s1e1.mp4", s1e1Source?.url)
        assertEquals("1080p", s1e1Source?.resolution)

        // Request Season 2 Episode 5
        // If provider wrongly returns S1E1 when S2E5 was requested, episode identity protection must reject it
        val resS2E5 = repository.getStream("838601903666779168", "lupin-2rBwMMWNWZ", season = 2, episode = 5, isSeries = true)
        assertTrue("Must fail with episode identity mismatch when S1E1 returned for S2E5", resS2E5.isFailure)
        assertTrue(resS2E5.exceptionOrNull() is IllegalStateException)
        assertEquals(2, fakeApi.lastRequestedSeason)
        assertEquals(5, fakeApi.lastRequestedEpisode)
    }

    // ── Test 4: Mapping identity ────────────────────────────────────────────
    @Test
    fun test4_mappingIdentity() = runBlocking {
        // TMDB ID must never substitute for MovieBox subject ID
        assertFalse(MovieBoxPlaybackGuard.isValidMovieBoxSubjectId("tmdb_tv_96677"))
        assertFalse(MovieBoxPlaybackGuard.isValidMovieBoxSlug("tmdb_tv_96677"))
        assertFalse(MovieBoxPlaybackGuard.canInitiatePlayback("tmdb_tv_96677", "lupin-2rBwMMWNWZ"))
        assertFalse(MovieBoxPlaybackGuard.canInitiatePlayback("838601903666779168", "tmdb_tv_96677"))

        // Genuine MovieBox identity must pass
        assertTrue(MovieBoxPlaybackGuard.isValidMovieBoxSubjectId("838601903666779168"))
        assertTrue(MovieBoxPlaybackGuard.isValidMovieBoxSlug("lupin-2rBwMMWNWZ"))
        assertTrue(MovieBoxPlaybackGuard.canInitiatePlayback("838601903666779168", "lupin-2rBwMMWNWZ"))

        // Attempting playback directly on an unverified TMDB slug must fail at the repository boundary
        val invalidCall = repository.getStream("tmdb_tv_96677", "tmdb_tv_96677", 1, 1, isSeries = true)
        assertTrue(invalidCall.isFailure)
        assertTrue(invalidCall.exceptionOrNull() is IllegalArgumentException)
    }

    // ── Test 5: Error handling ──────────────────────────────────────────────
    @Test
    fun test5_errorHandling() = runBlocking {
        // Case A: Empty source list from provider produces clear unavailable result
        fakeApi.streamToReturn = StreamResponse(
            subjectId = "838601903666779168",
            se = 1,
            ep = 1,
            hasResource = false,
            sources = emptyList()
        )
        val emptyResult = repository.getStream("838601903666779168", "lupin-2rBwMMWNWZ", 1, 1, isSeries = true)
        assertTrue(emptyResult.isSuccess)
        assertFalse(emptyResult.getOrNull()?.hasResource ?: true)
        assertTrue(emptyResult.getOrNull()?.sources.isNullOrEmpty())

        // Case B: HTTP network failure produces Result.failure without crash
        fakeApi.streamExceptionToThrow = IOException("HTTP 500 Server Error")
        val networkFailResult = repository.getStream("838601903666779168", "lupin-2rBwMMWNWZ", 1, 1, isSeries = true)
        assertTrue(networkFailResult.isFailure)
        assertEquals("HTTP 500 Server Error", networkFailResult.exceptionOrNull()?.message)
    }
}

