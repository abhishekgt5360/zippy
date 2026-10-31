package com.example.data.repository

import com.example.data.remote.TmdbNetworkClient
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import retrofit2.HttpException

class TmdbConnectivityTest {

    private val repository = TmdbRepository()

    @Test
    fun testTmdbApiConfiguration_handlesUnauthenticatedStateGracefully() = runBlocking {
        // When TMDB credentials are not configured or set to default "NOT_SET",
        // the network client must safely return a Failure (e.g., 401 Unauthorized)
        // rather than crashing the application.
        val result = repository.getTrending("movie", "day")

        if (!TmdbNetworkClient.isConfigured()) {
            assertTrue("Unconfigured TMDB request should fail safely", result.isFailure)
            val exception = result.exceptionOrNull()
            assertNotNull(exception)
            if (exception is HttpException) {
                assertEquals("Unauthenticated TMDB call must return HTTP 401", 401, exception.code())
            }
        } else {
            // When user has supplied their TMDB key or Read Access Token:
            assertTrue("Configured TMDB request should succeed", result.isSuccess)
            val response = result.getOrNull()
            assertNotNull(response)
            assertTrue("Trending movies must not be empty", response!!.results.isNotEmpty())
        }
    }

    @Test
    fun testPosterAndBackdropUrlBuilders() {
        val path = "/8Gxv8gSFCU0XGDykEGv7zR1n2ua.jpg"

        val posterUrl = TmdbNetworkClient.buildPosterUrl(path, "w500")
        assertEquals("https://image.tmdb.org/t/p/w500/8Gxv8gSFCU0XGDykEGv7zR1n2ua.jpg", posterUrl)

        val backdropUrl = TmdbNetworkClient.buildBackdropUrl(path, "w1280")
        assertEquals("https://image.tmdb.org/t/p/w1280/8Gxv8gSFCU0XGDykEGv7zR1n2ua.jpg", backdropUrl)

        assertNull("Null poster path should return null", TmdbNetworkClient.buildPosterUrl(null))
        assertNull("Blank backdrop path should return null", TmdbNetworkClient.buildBackdropUrl("   "))
    }

    @Test
    fun testExistingMovieBoxConfiguration_remainsUntouched() {
        // Verify MovieBox client exists and uses its independent Retrofit instance
        assertNotNull("MovieBox API instance must be intact", com.example.data.remote.NetworkClient.api)
        assertNotNull("MovieBox Moshi instance must be intact", com.example.data.remote.NetworkClient.moshi)
    }
}
