package com.example.data.model

import com.example.data.remote.TmdbNetworkClient
import com.squareup.moshi.JsonAdapter
import com.squareup.moshi.Types
import org.junit.Assert.*
import org.junit.Test
import java.lang.reflect.Type

class TmdbModelsTest {

    @Test
    fun testTmdbMovieItem_toMovieItem_conversion_protectsMovieBoxIds() {
        val tmdbMovie = TmdbMovieItem(
            id = 550,
            title = "Fight Club",
            originalTitle = "Fight Club",
            overview = "A ticking-time-bomb insomniac...",
            posterPath = "/pB8BM7pdSp6B6Ih7QZ4DrQ3PmJK.jpg",
            backdropPath = "/hZkgoQYus5vegHoetLkCJzb17zJ.jpg",
            mediaType = "movie",
            releaseDate = "1999-10-15",
            voteAverage = 8.433,
            voteCount = 26000,
            genreIds = listOf(18)
        )

        assertEquals("Fight Club", tmdbMovie.safeTitle)
        assertEquals("1999", tmdbMovie.year)
        assertEquals("8.4", tmdbMovie.ratingString)
        assertFalse(tmdbMovie.isTv)
        assertEquals("https://image.tmdb.org/t/p/w500/pB8BM7pdSp6B6Ih7QZ4DrQ3PmJK.jpg", tmdbMovie.posterUrl())

        val movieItem = tmdbMovie.toMovieItem(genreMap = mapOf(18 to "Drama"))
        assertEquals("Fight Club", movieItem.safeName)
        assertEquals("tmdb_movie_550", movieItem.slug)

        // CRITICAL PROTECTION: TMDB items must NEVER populate subjectId with TMDB IDs!
        assertEquals("subjectId must be empty for unverified TMDB item", "", movieItem.subjectId)
        assertFalse("TMDB discovery item must NOT claim a MovieBox subject ID", movieItem.hasMovieBoxSubjectId)
        assertTrue("Item must be marked as originating from TMDB", movieItem.isFromTmdb)
        assertEquals(550L, movieItem.tmdbId)
        assertEquals("movie", movieItem.tmdbMediaType)

        assertEquals("MOVIE", movieItem.badge)
        assertEquals("8.4", movieItem.rating)
        assertEquals("1999", movieItem.year)
        assertEquals("Drama", movieItem.genre)
        assertEquals("movie", movieItem.type)
    }

    @Test
    fun testTmdbTvItem_toMovieItem_conversion_protectsMovieBoxIds() {
        val tmdbTv = TmdbMovieItem(
            id = 1399,
            name = "Game of Thrones",
            originalName = "Game of Thrones",
            overview = "Seven noble families fight for control of the mythical land of Westeros.",
            posterPath = "/1XS1oqL89opfnbLl8WnZY1O1uJx.jpg",
            firstAirDate = "2011-04-17",
            voteAverage = 8.45,
            genreIds = listOf(10765, 18)
        )

        assertEquals("Game of Thrones", tmdbTv.safeTitle)
        assertEquals("2011", tmdbTv.year)
        assertEquals("8.5", tmdbTv.ratingString)
        assertTrue(tmdbTv.isTv)

        val movieItem = tmdbTv.toMovieItem(genreMap = mapOf(10765 to "Sci-Fi & Fantasy"))
        assertEquals("Game of Thrones", movieItem.safeName)
        assertEquals("tmdb_tv_1399", movieItem.slug)

        // CRITICAL PROTECTION: TMDB TV items must NEVER populate subjectId with TMDB IDs!
        assertEquals("subjectId must be empty for unverified TMDB TV item", "", movieItem.subjectId)
        assertFalse("TMDB TV item must NOT claim a MovieBox subject ID", movieItem.hasMovieBoxSubjectId)
        assertTrue("Item must be marked as originating from TMDB", movieItem.isFromTmdb)
        assertEquals(1399L, movieItem.tmdbId)
        assertEquals("tv", movieItem.tmdbMediaType)

        assertEquals("TV", movieItem.badge)
        assertEquals("Sci-Fi & Fantasy", movieItem.genre)
        assertEquals("tv", movieItem.type)
    }

    @Test
    fun testTmdbPersonItem_toMovieItem_doesNotMasqueradeAsMovie() {
        val tmdbPerson = TmdbMovieItem(
            id = 500,
            name = "Tom Cruise",
            mediaType = "person",
            knownForDepartment = "Acting",
            profilePath = "/8qB91Ze4Go972J4bAv1gCj7b5nF.jpg"
        )

        assertTrue("Person item must be identified as person", tmdbPerson.isPerson)
        assertFalse("Person item must not be playable content", tmdbPerson.isPlayableContent)
        assertFalse("Person item must not be tv", tmdbPerson.isTv)
        assertFalse("Person item must not be movie", tmdbPerson.isMovie)
        assertEquals("person", tmdbPerson.safeMediaType)
        assertEquals("https://image.tmdb.org/t/p/w185/8qB91Ze4Go972J4bAv1gCj7b5nF.jpg", tmdbPerson.profileUrl())

        val movieItem = tmdbPerson.toMovieItem()
        assertEquals("Tom Cruise", movieItem.safeName)
        assertEquals("tmdb_person_500", movieItem.slug)
        assertEquals("", movieItem.subjectId)
        assertEquals("PERSON", movieItem.badge)
        assertEquals("person", movieItem.type)
        assertEquals("Acting", movieItem.genre)
        assertFalse("Person cannot initiate playback", movieItem.canInitiatePlayback)
    }

    @Test
    fun testProductionMovieBoxPlaybackGuard_protectsAgainstUnmatchedAndTmdbIds() {
        // A discovery item straight from TMDB
        val tmdbItem = TmdbMovieItem(
            id = 872585L,
            title = "Oppenheimer",
            releaseDate = "2023-07-19",
            mediaType = "movie"
        ).toMovieItem()

        // 1. Verify MovieBox subject ID is absent and slug is TMDB-specific
        assertTrue("Unmatched TMDB item subjectId must be blank", tmdbItem.subjectId.isBlank())
        assertFalse("Unmatched item must not have a MovieBox ID", tmdbItem.hasMovieBoxSubjectId)
        assertFalse("Unmatched item must not have a MovieBox slug", tmdbItem.hasMovieBoxSlug)
        assertFalse("Unmatched item canInitiatePlayback must be false", tmdbItem.canInitiatePlayback)

        // 2. Direct production MovieBoxPlaybackGuard checks
        assertFalse(
            "Guard must reject unmatched TMDB item (empty subjectId)",
            MovieBoxPlaybackGuard.canInitiatePlayback(tmdbItem.subjectId, tmdbItem.slug, tmdbItem.tmdbId)
        )
        assertTrue(
            "Guard allows valid MovieBox ID format even if numerically equal to TMDB ID",
            MovieBoxPlaybackGuard.isValidMovieBoxSubjectId("872585", tmdbId = 872585L)
        )
        assertFalse(
            "Guard must reject TMDB pseudo-ID (tmdb_...)",
            MovieBoxPlaybackGuard.canInitiatePlayback("tmdb_movie_872585", "oppenheimer-2023", tmdbId = 872585L)
        )
        assertFalse(
            "Guard must reject TMDB slug (tmdb_...)",
            MovieBoxPlaybackGuard.canInitiatePlayback("9999", "tmdb_movie_872585", tmdbId = 872585L)
        )

        // 3. Test verified match (from Content Matching Engine)
        val verifiedMappedItem = tmdbItem.copy(
            rawSubjectId = "987654",
            slug = "oppenheimer-2023"
        )
        assertTrue("Verified item has legitimate MovieBox ID", verifiedMappedItem.hasMovieBoxSubjectId)
        assertTrue("Verified item has legitimate MovieBox slug", verifiedMappedItem.hasMovieBoxSlug)
        assertTrue("Verified item can initiate playback", verifiedMappedItem.canInitiatePlayback)
        assertTrue(
            "Guard approves verified MovieBox ID and slug",
            MovieBoxPlaybackGuard.canInitiatePlayback(verifiedMappedItem.subjectId, verifiedMappedItem.slug, verifiedMappedItem.tmdbId)
        )

        // 4. Test native MovieBox item
        val nativeMovieBoxItem = MovieItem(
            name = "Inception",
            posterUrl = "https://example.com/poster.jpg",
            slug = "inception-2010",
            subjectId = "1001",
            year = "2010",
            type = "movie"
        )
        assertTrue("Native MovieBox item has valid subjectId", nativeMovieBoxItem.hasMovieBoxSubjectId)
        assertTrue("Native MovieBox item has valid slug", nativeMovieBoxItem.hasMovieBoxSlug)
        assertTrue("Native MovieBox item can initiate playback", nativeMovieBoxItem.canInitiatePlayback)
        assertTrue(
            "Guard approves native MovieBox item",
            MovieBoxPlaybackGuard.canInitiatePlayback(nativeMovieBoxItem.subjectId, nativeMovieBoxItem.slug)
        )
    }

    @Test
    fun testTmdbJsonParsing_withMoshi() {
        val json = """
            {
                "page": 1,
                "results": [
                    {
                        "id": 999,
                        "title": "Interstellar",
                        "release_date": "2014-11-05",
                        "vote_average": 8.6,
                        "poster_path": "/gEU2QniE6E77NI6lCU6MxlNBvIx.jpg"
                    }
                ],
                "total_pages": 1,
                "total_results": 1
            }
        """.trimIndent()

        val responseType: Type = Types.newParameterizedType(
            TmdbPagedResponse::class.java,
            TmdbMovieItem::class.java
        )
        val adapter: JsonAdapter<TmdbPagedResponse<TmdbMovieItem>> =
            TmdbNetworkClient.moshi.adapter(responseType)

        val parsed = adapter.fromJson(json)
        assertNotNull(parsed)
        assertEquals(1, parsed!!.results.size)
        val item = parsed.results[0]
        assertEquals(999L, item.id)
        assertEquals("Interstellar", item.safeTitle)
        assertEquals("2014", item.year)
    }
}
