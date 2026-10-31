package com.example.data.repository

import com.example.data.model.*
import com.example.data.remote.TmdbApiService
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

/**
 * Validates that TMDB multi-search results are filtered correctly so that person records
 * are never converted into playable movie/TV records, while preserving dedicated person search.
 */
class TmdbSearchFilterTest {

    class FakeTmdbApiService : TmdbApiService {
        override suspend fun getTrending(mediaType: String, timeWindow: String, page: Int): TmdbPagedResponse<TmdbMovieItem> = TmdbPagedResponse()
        override suspend fun getPopularMovies(page: Int, region: String?): TmdbPagedResponse<TmdbMovieItem> = TmdbPagedResponse()
        override suspend fun getPopularTv(page: Int): TmdbPagedResponse<TmdbMovieItem> = TmdbPagedResponse()
        override suspend fun getTopRatedMovies(page: Int): TmdbPagedResponse<TmdbMovieItem> = TmdbPagedResponse()
        override suspend fun getTopRatedTv(page: Int): TmdbPagedResponse<TmdbMovieItem> = TmdbPagedResponse()
        override suspend fun getNowPlayingMovies(page: Int): TmdbPagedResponse<TmdbMovieItem> = TmdbPagedResponse()
        override suspend fun getUpcomingMovies(page: Int): TmdbPagedResponse<TmdbMovieItem> = TmdbPagedResponse()
        override suspend fun discoverMovies(page: Int, sortBy: String?, withGenres: String?, withOriginalLanguage: String?, withOriginCountry: String?, primaryReleaseYear: Int?): TmdbPagedResponse<TmdbMovieItem> = TmdbPagedResponse()
        override suspend fun discoverTv(page: Int, sortBy: String?, withGenres: String?, withOriginalLanguage: String?, withOriginCountry: String?, firstAirDateYear: Int?): TmdbPagedResponse<TmdbMovieItem> = TmdbPagedResponse()

        override suspend fun searchMulti(query: String, page: Int): TmdbPagedResponse<TmdbMovieItem> {
            val mixedResults = listOf(
                TmdbMovieItem(
                    id = 361743L,
                    title = "Top Gun: Maverick",
                    mediaType = "movie",
                    releaseDate = "2022-05-24",
                    voteAverage = 8.3
                ),
                TmdbMovieItem(
                    id = 108978L,
                    name = "Reacher",
                    mediaType = "tv",
                    firstAirDate = "2022-02-03",
                    voteAverage = 8.1
                ),
                TmdbMovieItem(
                    id = 500L,
                    name = "Tom Cruise",
                    mediaType = "person",
                    knownForDepartment = "Acting",
                    profilePath = "/8qB91Ze4Go972J4bAv1gCj7b5nF.jpg"
                )
            )
            return TmdbPagedResponse(
                page = page,
                results = mixedResults,
                totalPages = 1,
                totalResults = mixedResults.size
            )
        }

        override suspend fun searchMovies(query: String, page: Int, year: Int?): TmdbPagedResponse<TmdbMovieItem> = TmdbPagedResponse()
        override suspend fun searchTv(query: String, page: Int, year: Int?): TmdbPagedResponse<TmdbMovieItem> = TmdbPagedResponse()
        override suspend fun getMovieGenres(): TmdbGenreResponse = TmdbGenreResponse()
        override suspend fun getTvGenres(): TmdbGenreResponse = TmdbGenreResponse()
        override suspend fun getMovieDetail(movieId: Long, append: String): TmdbDetailResponse = TmdbDetailResponse(id = movieId)
        override suspend fun getTvDetail(tvId: Long, append: String): TmdbDetailResponse = TmdbDetailResponse(id = tvId)
    }

    @Test
    fun testSearch_filtersOutPersonResultsFromPlayableCatalogue() = runBlocking {
        val fakeApi = FakeTmdbApiService()
        val repository = TmdbRepository(api = fakeApi)

        val result = repository.search("Tom")
        assertTrue("Search should succeed", result.isSuccess)

        val items = result.getOrNull()?.results.orEmpty()
        assertEquals("Person result must be filtered out, leaving only movie and tv", 2, items.size)

        // Verify only movie and TV records remain
        val titles = items.map { it.safeTitle }
        assertTrue("Movie must be present", titles.contains("Top Gun: Maverick"))
        assertTrue("TV show must be present", titles.contains("Reacher"))
        assertFalse("Person 'Tom Cruise' must NOT be in playable catalogue", titles.contains("Tom Cruise"))

        // Verify none of the items are marked as person
        items.forEach { item ->
            assertFalse("No item in playable catalogue may be person", item.isPerson)
            assertTrue("Item must be playable content", item.isPlayableContent)
        }
    }

    @Test
    fun testSearchPersons_returnsOnlyPersonRecords() = runBlocking {
        val fakeApi = FakeTmdbApiService()
        val repository = TmdbRepository(api = fakeApi)

        val result = repository.searchPersons("Tom")
        assertTrue("Person search should succeed", result.isSuccess)

        val persons = result.getOrNull()?.results.orEmpty()
        assertEquals("Only person result should be returned", 1, persons.size)

        val person = persons[0]
        assertEquals(500L, person.id)
        assertEquals("Tom Cruise", person.safeTitle)
        assertTrue("Item must be identified as person", person.isPerson)
        assertFalse("Person must not be playable content", person.isPlayableContent)
        assertEquals("person", person.safeMediaType)
    }
}
