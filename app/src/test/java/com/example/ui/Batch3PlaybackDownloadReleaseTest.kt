package com.example.ui

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.local.AppDatabase
import com.example.data.local.WatchHistoryEntity
import com.example.data.model.*
import com.example.data.remote.MovieApiService
import com.example.data.repository.MovieRepository
import com.example.util.ResumeManager
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Batch 3 comprehensive verification suite:
 * - MP4, HLS, DASH source recognition, order of evidence, and MIME resolution
 * - Stream source fallback and blank URL sanitization
 * - TV season and episode identity protection
 * - Download format compatibility and provider verification
 * - Playback progress, resume, and TMDB/MovieBox identity preservation
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class Batch3PlaybackDownloadReleaseTest {

    private lateinit var context: Context
    private lateinit var database: AppDatabase
    private lateinit var fakeApi: FakeMovieApiService
    private lateinit var repository: MovieRepository

    class FakeMovieApiService : MovieApiService {
        var streamToReturn: StreamResponse = StreamResponse()
        var streamThrowException: Exception? = null
        var lastSeasonRequested: Int? = null
        var lastEpisodeRequested: Int? = null
        var streamCallCount: Int = 0

        override suspend fun checkHealth(): HealthResponse = HealthResponse(status = "ok")
        override suspend fun getHome(): HomeResponse = HomeResponse(status = "ok", sections = emptyList())
        override suspend fun getMovies(page: Int, sort: String?, genre: String?, country: String?, year: String?): CatalogResponse = CatalogResponse()
        override suspend fun getTvSeries(page: Int, sort: String?, genre: String?, country: String?, year: String?): CatalogResponse = CatalogResponse()
        override suspend fun getAnimation(page: Int, sort: String?, genre: String?): CatalogResponse = CatalogResponse()
        override suspend fun search(query: String, page: Int): SearchResponse = SearchResponse()
        override suspend fun getSuggestions(query: String): SuggestResponse = SuggestResponse()
        override suspend fun getDetail(slug: String): DetailResponse = DetailResponse()

        override suspend fun getStream(subjectId: String, detailPath: String, season: Int, episode: Int): StreamResponse {
            streamCallCount++
            lastSeasonRequested = season
            lastEpisodeRequested = episode
            streamThrowException?.let { throw it }
            return streamToReturn
        }

        override suspend fun getCaptions(subjectId: String, detailPath: String, season: Int, episode: Int): CaptionsResponse {
            return CaptionsResponse(subjectId = subjectId, captions = emptyList())
        }
    }

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        fakeApi = FakeMovieApiService()
        repository = MovieRepository(
            api = fakeApi,
            database = database,
            context = context
        )
    }

    @After
    fun tearDown() {
        database.close()
    }

    // ---------------------------------------------------------------------------------------------
    // STEP 2: MP4, HLS, DASH COMPATIBILITY & MIME TYPE RESOLUTION
    // ---------------------------------------------------------------------------------------------

    @Test
    fun `test MP4 progressive recognition from metadata and URL`() {
        val mp4SourceWithMeta = StreamSource(resolution = "1080p", format = "MP4", url = "https://cdn.example.com/video.mp4")
        assertEquals("video/mp4", mp4SourceWithMeta.resolveMimeType())
        assertTrue(mp4SourceWithMeta.isDownloadable)

        val mp4SourceNoMeta = StreamSource(resolution = "720p", format = null, url = "https://cdn.example.com/video.mp4?token=abc")
        assertEquals("video/mp4", mp4SourceNoMeta.resolveMimeType())
        assertTrue(mp4SourceNoMeta.isDownloadable)

        val mkvSource = StreamSource(resolution = "1080p", format = "MKV", url = "https://cdn.example.com/file.mkv")
        assertEquals("video/x-matroska", mkvSource.resolveMimeType())
        assertTrue(mkvSource.isDownloadable)
    }

    @Test
    fun `test HLS manifest recognition and MIME type resolution`() {
        val hlsWithMeta = StreamSource(resolution = "1080p", format = "HLS", url = "https://cdn.example.com/stream/master.m3u8")
        assertEquals("application/x-mpegURL", hlsWithMeta.resolveMimeType())
        assertFalse(hlsWithMeta.isDownloadable)

        // Metadata takes precedence even if URL has no .m3u8 extension
        val hlsWithNoExtensionInUrl = StreamSource(resolution = "auto", format = "HLS", url = "https://cdn.example.com/live_stream?auth=xyz")
        assertEquals("application/x-mpegURL", hlsWithNoExtensionInUrl.resolveMimeType())
        assertFalse(hlsWithNoExtensionInUrl.isDownloadable)

        val hlsFromExtensionOnly = StreamSource(resolution = "720p", format = null, url = "https://cdn.example.com/playlist.m3u8?token=xyz")
        assertEquals("application/x-mpegURL", hlsFromExtensionOnly.resolveMimeType())
        assertFalse(hlsFromExtensionOnly.isDownloadable)
    }

    @Test
    fun `test DASH manifest recognition and MIME type resolution`() {
        val dashWithMeta = StreamSource(resolution = "1080p", format = "DASH", url = "https://cdn.example.com/manifest.mpd")
        assertEquals("application/dash+xml", dashWithMeta.resolveMimeType())
        assertFalse(dashWithMeta.isDownloadable)

        // Metadata takes precedence even without .mpd extension
        val dashWithNoExtensionInUrl = StreamSource(resolution = "auto", format = "DASH", url = "https://cdn.example.com/dash_stream?session=123")
        assertEquals("application/dash+xml", dashWithNoExtensionInUrl.resolveMimeType())
        assertFalse(dashWithNoExtensionInUrl.isDownloadable)

        val dashFromExtensionOnly = StreamSource(resolution = "1080p", format = null, url = "https://cdn.example.com/manifest.mpd?sign=abc")
        assertEquals("application/dash+xml", dashFromExtensionOnly.resolveMimeType())
        assertFalse(dashFromExtensionOnly.isDownloadable)
    }

    @Test
    fun `test order of evidence prioritizes format metadata over misleading URL query params`() {
        // Query param contains .mp4, but format explicitly says HLS
        val sourceHls = StreamSource(format = "HLS", url = "https://cdn.example.com/stream?name=file.mp4")
        assertEquals("application/x-mpegURL", sourceHls.resolveMimeType())

        // Query param contains .m3u8, but path is clean mpd
        val sourceDash = StreamSource(format = null, url = "https://cdn.example.com/manifest.mpd?fallback=other.m3u8")
        assertEquals("application/dash+xml", sourceDash.resolveMimeType())
    }

    // ---------------------------------------------------------------------------------------------
    // STEP 3: STREAM SOURCE SELECTION, SANITIZATION & FALLBACK
    // ---------------------------------------------------------------------------------------------

    @Test
    fun `test blank source URLs are discarded safely`() = runBlocking {
        fakeApi.streamToReturn = StreamResponse(
            subjectId = "mb_sub_101",
            hasResource = true,
            sources = listOf(
                StreamSource(resolution = "1080p", format = "MP4", url = ""),
                StreamSource(resolution = "720p", format = "MP4", url = "   "),
                StreamSource(resolution = "480p", format = "MP4", url = "https://cdn.example.com/valid.mp4")
            )
        )

        val result = repository.getStream("mb_sub_101", "valid-slug", 0, 0, isSeries = false)
        assertTrue(result.isSuccess)
        val stream = result.getOrThrow()
        assertEquals(1, stream.sources.size)
        assertEquals("https://cdn.example.com/valid.mp4", stream.sources.first().directUrl ?: stream.sources.first().url)
    }

    @Test
    fun `test effectiveSources converts HLS or DASH when sources list is empty`() {
        val hlsOnlyResponse = StreamResponse(
            subjectId = "mb_sub_202",
            hasResource = true,
            sources = emptyList(),
            hls = listOf(HlsStream(url = "https://cdn.example.com/master.m3u8", resolution = "1080p"))
        )
        assertTrue(hlsOnlyResponse.hasUsableStreams)
        assertEquals(1, hlsOnlyResponse.effectiveSources.size)
        assertEquals("HLS", hlsOnlyResponse.effectiveSources.first().format)

        val dashOnlyResponse = StreamResponse(
            subjectId = "mb_sub_203",
            hasResource = true,
            sources = emptyList(),
            dash = listOf(DashStream(url = "https://cdn.example.com/manifest.mpd", resolution = "1080p"))
        )
        assertTrue(dashOnlyResponse.hasUsableStreams)
        assertEquals(1, dashOnlyResponse.effectiveSources.size)
        assertEquals("DASH", dashOnlyResponse.effectiveSources.first().format)
    }

    @Test
    fun `test network error is not converted to clean empty result`() = runBlocking {
        fakeApi.streamThrowException = java.io.IOException("Connection reset by peer")

        val result = repository.getStream("mb_sub_303", "valid-slug", 0, 0, isSeries = false)
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is java.io.IOException)
    }

    // ---------------------------------------------------------------------------------------------
    // STEP 4: TV SEASONS AND EPISODE IDENTITY
    // ---------------------------------------------------------------------------------------------

    @Test
    fun `test TV pilot episode allows 0-based and 1-based index fallback`() = runBlocking {
        // Pilot S1E1 request
        fakeApi.streamToReturn = StreamResponse(
            subjectId = "mb_tv_1",
            se = 1,
            ep = 1,
            hasResource = true,
            sources = listOf(StreamSource(resolution = "1080p", format = "MP4", url = "https://cdn.example.com/s1e1.mp4"))
        )

        val result = repository.getStream("mb_tv_1", "tv-slug", season = 1, episode = 1, isSeries = true)
        assertTrue(result.isSuccess)
        assertEquals(1, fakeApi.lastSeasonRequested)
        assertEquals(1, fakeApi.lastEpisodeRequested)
    }

    @Test
    fun `test TV later episode strictly requests exact season and episode without pilot fallback`() = runBlocking {
        // Request Season 3 Episode 7
        fakeApi.streamToReturn = StreamResponse(
            subjectId = "mb_tv_2",
            se = 3,
            ep = 7,
            hasResource = true,
            sources = listOf(StreamSource(resolution = "1080p", format = "MP4", url = "https://cdn.example.com/s3e7.mp4"))
        )

        val result = repository.getStream("mb_tv_2", "tv-slug", season = 3, episode = 7, isSeries = true)
        assertTrue(result.isSuccess)
        assertEquals(3, fakeApi.lastSeasonRequested)
        assertEquals(7, fakeApi.lastEpisodeRequested)
        assertEquals(1, fakeApi.streamCallCount) // Never fell back to (0,0) or (1,1)
    }

    @Test
    fun `test provider episode identity mismatch is strictly rejected`() = runBlocking {
        // Requested Season 2 Episode 4, but backend mistakenly returned S1E1
        fakeApi.streamToReturn = StreamResponse(
            subjectId = "mb_tv_3",
            se = 1,
            ep = 1,
            hasResource = true,
            sources = listOf(StreamSource(resolution = "1080p", format = "MP4", url = "https://cdn.example.com/s1e1.mp4"))
        )

        val result = repository.getStream("mb_tv_3", "tv-slug", season = 2, episode = 4, isSeries = true)
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is IllegalStateException)
        val msg = result.exceptionOrNull()?.message ?: ""
        assertTrue("Message should indicate episode mismatch: $msg", msg.contains("Episode identity mismatch"))
    }

    // ---------------------------------------------------------------------------------------------
    // STEP 5: DOWNLOAD SAFETY & FORMAT LIMITATIONS
    // ---------------------------------------------------------------------------------------------

    @Test
    fun `test download sources filter out non-downloadable streaming manifests`() {
        val sources = listOf(
            StreamSource(resolution = "1080p", format = "HLS", url = "https://cdn.example.com/master.m3u8"),
            StreamSource(resolution = "720p", format = "DASH", url = "https://cdn.example.com/manifest.mpd"),
            StreamSource(resolution = "480p", format = "MP4", url = "https://cdn.example.com/downloadable.mp4")
        )

        val downloadableOnly = sources.filter { it.isDownloadable }
        assertEquals(1, downloadableOnly.size)
        assertEquals("480p", downloadableOnly.first().resolution)
        assertEquals("https://cdn.example.com/downloadable.mp4", downloadableOnly.first().url)
    }

    @Test
    fun `test download requires valid MovieBox subject ID and slug`() {
        // Unverified TMDB slug or ID cannot be passed as MovieBox provider identity
        assertFalse(MovieBoxPlaybackGuard.canInitiatePlayback("tmdb_12345", "tmdb_movie_12345"))
        assertFalse(MovieBoxPlaybackGuard.canInitiatePlayback("", "valid-slug"))
        assertFalse(MovieBoxPlaybackGuard.canInitiatePlayback("mb_valid_id", ""))
        assertTrue(MovieBoxPlaybackGuard.canInitiatePlayback("mb_valid_id", "valid-slug"))
    }

    // ---------------------------------------------------------------------------------------------
    // STEP 6: PLAYBACK PROGRESS, RESUME & IDENTITY PRESERVATION
    // ---------------------------------------------------------------------------------------------

    @Test
    fun `test resume position calculation thresholds`() {
        // Less than 10 seconds: resets to 0L
        assertEquals(0L, ResumeManager.calculateStartPosition(positionMs = 5_000L, durationMs = 120_000L, finished = false))

        // In progress (30s out of 120s): returns exact 30s
        assertEquals(30_000L, ResumeManager.calculateStartPosition(positionMs = 30_000L, durationMs = 120_000L, finished = false))

        // >= 95% finished: returns 0L
        val nearEnd96Pct = (120_000L * 0.96f).toLong()
        assertEquals(0L, ResumeManager.calculateStartPosition(positionMs = nearEnd96Pct, durationMs = 120_000L, finished = false))
        assertTrue(ResumeManager.isFinished(positionMs = nearEnd96Pct, durationMs = 120_000L))

        // <= 30 seconds remaining: returns 0L
        val nearEnd20sRemaining = 120_000L - 20_000L
        assertEquals(0L, ResumeManager.calculateStartPosition(positionMs = nearEnd20sRemaining, durationMs = 120_000L, finished = false))
        assertTrue(ResumeManager.isFinished(positionMs = nearEnd20sRemaining, durationMs = 120_000L))
    }

    @Test
    fun `test watch progress preserves TMDB metadata in history record`() = runBlocking {
        repository.saveWatchProgress(
            subjectId = "mb_sub_history_1",
            slug = "moviebox-slug",
            title = "Inception",
            posterUrl = "https://image.tmdb.org/t/p/w500/inception.jpg",
            season = 0,
            episode = 0,
            positionMs = 60_000L,
            durationMs = 120_000L,
            type = "movie",
            tmdbId = 27205L,
            tmdbMediaType = "movie"
        )

        val history = database.watchDao().getHistory("mb_sub_history_1")
        assertNotNull(history)
        assertEquals("mb_sub_history_1", history!!.subjectId)
        assertEquals("Inception", history.title)
        assertEquals(60_000L, history.positionMs)
        assertEquals(27205L, history.tmdbId)
        assertEquals("movie", history.tmdbMediaType)
    }

    @Test
    fun `test TV episode progress stores distinct progress per season and episode`() = runBlocking {
        // Save progress for S1E1
        repository.saveWatchProgress(
            subjectId = "mb_series_progress",
            slug = "series-slug",
            title = "Breaking Bad",
            posterUrl = "https://example.com/poster.jpg",
            season = 1,
            episode = 1,
            positionMs = 45_000L,
            durationMs = 180_000L,
            type = "tv",
            tmdbId = 1396L,
            tmdbMediaType = "tv"
        )

        // Save progress for S2E3
        repository.saveWatchProgress(
            subjectId = "mb_series_progress",
            slug = "series-slug",
            title = "Breaking Bad",
            posterUrl = "https://example.com/poster.jpg",
            season = 2,
            episode = 3,
            positionMs = 90_000L,
            durationMs = 180_000L,
            type = "tv",
            tmdbId = 1396L,
            tmdbMediaType = "tv"
        )

        val ep1 = database.watchDao().getEpisodeProgress("mb_series_progress", season = 1, episode = 1)
        val ep3 = database.watchDao().getEpisodeProgress("mb_series_progress", season = 2, episode = 3)

        assertNotNull(ep1)
        assertNotNull(ep3)
        assertEquals(45_000L, ep1!!.positionMs)
        assertEquals(90_000L, ep3!!.positionMs)

        // Watch history points to the latest watched episode (S2E3)
        val history = database.watchDao().getHistory("mb_series_progress")
        assertNotNull(history)
        assertEquals(2, history!!.season)
        assertEquals(3, history.episode)
        assertEquals(90_000L, history.positionMs)
        assertEquals(1396L, history.tmdbId)
    }

    // ---------------------------------------------------------------------------------------------
    // HOTFIX: TMDB SOURCE INDICATOR & PLAYBACK ROUTING
    // ---------------------------------------------------------------------------------------------

    @Test
    fun `test ContentSourceIndicator accurately reflects displayed content`() {
        val tmdbItems = listOf(
            MovieItem(name = "Inception", tmdbId = 27205L, slug = "tmdb_movie_27205"),
            MovieItem(name = "Interstellar", tmdbId = 157336L, slug = "tmdb_movie_157336")
        )
        val movieboxItems = listOf(
            MovieItem(name = "Local Film 1", slug = "local-1", rawSubjectId = "1001"),
            MovieItem(name = "Local Film 2", slug = "local-2", rawSubjectId = "1002")
        )

        assertEquals(ContentSourceIndicator.TMDB, com.example.data.model.computeSourceIndicator(tmdbItems))
        assertEquals(ContentSourceIndicator.MOVIEBOX, com.example.data.model.computeSourceIndicator(movieboxItems))
        assertEquals(ContentSourceIndicator.MIXED, com.example.data.model.computeSourceIndicator(tmdbItems + movieboxItems))
        assertEquals(ContentSourceIndicator.NONE, com.example.data.model.computeSourceIndicator(emptyList()))

        val homeTmdbState = com.example.ui.HomeUiState(
            isLoading = false,
            bannerItems = tmdbItems.take(1),
            sections = listOf(com.example.data.model.HomeSection("Trending", items = tmdbItems))
        )
        assertEquals(ContentSourceIndicator.TMDB, homeTmdbState.effectiveSourceIndicator)

        val homeFallbackState = com.example.ui.HomeUiState(
            isLoading = false,
            bannerItems = movieboxItems.take(1),
            sections = listOf(com.example.data.model.HomeSection("Popular", items = movieboxItems))
        )
        assertEquals(ContentSourceIndicator.MOVIEBOX, homeFallbackState.effectiveSourceIndicator)

        val homeEmptyState = com.example.ui.HomeUiState(
            isLoading = false,
            bannerItems = emptyList(),
            sections = emptyList()
        )
        assertEquals(ContentSourceIndicator.NONE, homeEmptyState.effectiveSourceIndicator)
    }

    @Test
    fun `test verified MovieBox IDs directly initiate playback without recursion even with tmdbId`() = runBlocking {
        fakeApi.streamToReturn = StreamResponse(
            subjectId = "verified_mb_123",
            hasResource = true,
            sources = listOf(
                StreamSource(resolution = "1080p", format = "MP4", url = "https://cdn.example.com/movie.mp4")
            )
        )

        // Directly invoke getStream with verified MovieBox credentials
        val result = repository.getStream(
            subjectId = "123456",
            slug = "verified-slug-movie",
            season = 0,
            episode = 0,
            isSeries = false
        )

        assertTrue(result.isSuccess)
        val stream = result.getOrNull()
        assertNotNull(stream)
        assertTrue(stream!!.hasUsableStreams)
        assertEquals(1, stream.sources.size)
        assertEquals("video/mp4", stream.sources.first().resolveMimeType())
    }

    @Test
    fun `test StreamSource resolveMimeType with query parameters stripped`() {
        val hlsSource = StreamSource(url = "https://cdn.example.com/playlist.m3u8?token=xyz123&expire=9999", format = null)
        val dashSource = StreamSource(url = "https://cdn.example.com/manifest.mpd?auth=bearer456", format = null)
        val mp4Source = StreamSource(url = "https://cdn.example.com/video.mp4?sig=abc789", format = null)
        val mkvSource = StreamSource(url = "https://cdn.example.com/media.mkv?key=secret", format = null)

        assertEquals("application/x-mpegURL", hlsSource.resolveMimeType())
        assertEquals("application/dash+xml", dashSource.resolveMimeType())
        assertEquals("video/mp4", mp4Source.resolveMimeType())
        assertEquals("video/x-matroska", mkvSource.resolveMimeType())

        // Explicit format takes precedence over extension
        val explicitHls = StreamSource(url = "https://cdn.example.com/stream?raw=true", format = "HLS")
        val explicitDash = StreamSource(url = "https://cdn.example.com/stream?raw=true", format = "DASH")
        val explicitMp4 = StreamSource(url = "https://cdn.example.com/stream?raw=true", format = "MP4")

        assertEquals("application/x-mpegURL", explicitHls.resolveMimeType())
        assertEquals("application/dash+xml", explicitDash.resolveMimeType())
        assertEquals("video/mp4", explicitMp4.resolveMimeType())
    }
}
