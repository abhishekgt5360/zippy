package com.example.data.repository

import com.example.data.model.*
import com.example.data.remote.TmdbApiService
import com.example.data.remote.TmdbNetworkClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

class TmdbRepository(
    private val api: TmdbApiService = TmdbNetworkClient.api
) {
    companion object {
        private val genreMapCache = ConcurrentHashMap<Int, String>()
        private val responseCache = ConcurrentHashMap<String, Pair<Long, Any>>()
        private const val CACHE_TTL_MS = 10 * 60 * 1000L // 10 minutes cache
    }

    fun isConfigured(): Boolean = TmdbNetworkClient.isConfigured()

    fun clearCache() {
        responseCache.clear()
    }

    @Suppress("UNCHECKED_CAST")
    private suspend fun <T : Any> getCachedOrFetch(
        cacheKey: String,
        forceRefresh: Boolean = false,
        fetcher: suspend () -> Result<T>
    ): Result<T> {
        val now = System.currentTimeMillis()
        if (!forceRefresh) {
            val cached = responseCache[cacheKey]
            if (cached != null && (now - cached.first) < CACHE_TTL_MS) {
                return Result.success(cached.second as T)
            }
        }
        val result = fetcher()
        result.onSuccess { data ->
            responseCache[cacheKey] = now to data
        }
        return result
    }

    suspend fun getGenreMap(): Map<Int, String> = withContext(Dispatchers.IO) {
        if (genreMapCache.isNotEmpty()) return@withContext genreMapCache

        try {
            coroutineScope {
                val movieGenresDeferred = async { api.getMovieGenres() }
                val tvGenresDeferred = async { api.getTvGenres() }

                val movieRes = movieGenresDeferred.await()
                val tvRes = tvGenresDeferred.await()

                movieRes.genres.forEach { genreMapCache[it.id] = it.name }
                tvRes.genres.forEach { genreMapCache[it.id] = it.name }
            }
        } catch (_: Exception) {}

        genreMapCache
    }

    suspend fun getTrending(
        mediaType: String = "all",
        timeWindow: String = "day",
        page: Int = 1,
        forceRefresh: Boolean = false
    ): Result<TmdbPagedResponse<TmdbMovieItem>> = withContext(Dispatchers.IO) {
        getCachedOrFetch("trending_${mediaType}_${timeWindow}_$page", forceRefresh) {
            safeApiCall { api.getTrending(mediaType = mediaType, timeWindow = timeWindow, page = page) }
        }
    }

    suspend fun getPopularMovies(page: Int = 1, forceRefresh: Boolean = false): Result<TmdbPagedResponse<TmdbMovieItem>> =
        withContext(Dispatchers.IO) {
            getCachedOrFetch("popular_movies_$page", forceRefresh) {
                safeApiCall { api.getPopularMovies(page = page) }
            }
        }

    suspend fun getPopularTv(page: Int = 1, forceRefresh: Boolean = false): Result<TmdbPagedResponse<TmdbMovieItem>> =
        withContext(Dispatchers.IO) {
            getCachedOrFetch("popular_tv_$page", forceRefresh) {
                safeApiCall { api.getPopularTv(page = page) }
            }
        }

    suspend fun getTopRatedMovies(page: Int = 1, forceRefresh: Boolean = false): Result<TmdbPagedResponse<TmdbMovieItem>> =
        withContext(Dispatchers.IO) {
            getCachedOrFetch("top_rated_movies_$page", forceRefresh) {
                safeApiCall { api.getTopRatedMovies(page = page) }
            }
        }

    suspend fun getTopRatedTv(page: Int = 1, forceRefresh: Boolean = false): Result<TmdbPagedResponse<TmdbMovieItem>> =
        withContext(Dispatchers.IO) {
            getCachedOrFetch("top_rated_tv_$page", forceRefresh) {
                safeApiCall { api.getTopRatedTv(page = page) }
            }
        }

    suspend fun getNowPlaying(page: Int = 1, forceRefresh: Boolean = false): Result<TmdbPagedResponse<TmdbMovieItem>> =
        withContext(Dispatchers.IO) {
            getCachedOrFetch("now_playing_$page", forceRefresh) {
                safeApiCall { api.getNowPlayingMovies(page = page) }
            }
        }

    suspend fun getUpcoming(page: Int = 1, forceRefresh: Boolean = false): Result<TmdbPagedResponse<TmdbMovieItem>> =
        withContext(Dispatchers.IO) {
            getCachedOrFetch("upcoming_$page", forceRefresh) {
                safeApiCall { api.getUpcomingMovies(page = page) }
            }
        }

    // ── Indian Cinema Discovery ──────────────────────────────────────────────
    suspend fun getIndianMovies(page: Int = 1, forceRefresh: Boolean = false): Result<TmdbPagedResponse<TmdbMovieItem>> =
        withContext(Dispatchers.IO) {
            getCachedOrFetch("indian_movies_$page", forceRefresh) {
                safeApiCall {
                    api.discoverMovies(
                        page = page,
                        withOriginCountry = "IN",
                        sortBy = "popularity.desc"
                    )
                }
            }
        }

    suspend fun getIndianTv(page: Int = 1, forceRefresh: Boolean = false): Result<TmdbPagedResponse<TmdbMovieItem>> =
        withContext(Dispatchers.IO) {
            getCachedOrFetch("indian_tv_$page", forceRefresh) {
                safeApiCall {
                    api.discoverTv(
                        page = page,
                        withOriginCountry = "IN",
                        sortBy = "popularity.desc"
                    )
                }
            }
        }

    suspend fun getBollywood(page: Int = 1, forceRefresh: Boolean = false): Result<TmdbPagedResponse<TmdbMovieItem>> =
        withContext(Dispatchers.IO) {
            getCachedOrFetch("bollywood_$page", forceRefresh) {
                safeApiCall {
                    api.discoverMovies(
                        page = page,
                        withOriginalLanguage = "hi",
                        withOriginCountry = "IN",
                        sortBy = "popularity.desc"
                    )
                }
            }
        }

    suspend fun getSouthIndian(page: Int = 1, forceRefresh: Boolean = false): Result<TmdbPagedResponse<TmdbMovieItem>> =
        withContext(Dispatchers.IO) {
            getCachedOrFetch("south_indian_$page", forceRefresh) {
                safeApiCall {
                    api.discoverMovies(
                        page = page,
                        withOriginalLanguage = "te|ta|ml|kn",
                        withOriginCountry = "IN",
                        sortBy = "popularity.desc"
                    )
                }
            }
        }

    suspend fun getBengali(page: Int = 1, forceRefresh: Boolean = false): Result<TmdbPagedResponse<TmdbMovieItem>> =
        withContext(Dispatchers.IO) {
            getCachedOrFetch("bengali_$page", forceRefresh) {
                safeApiCall {
                    api.discoverMovies(
                        page = page,
                        withOriginalLanguage = "bn",
                        sortBy = "popularity.desc"
                    )
                }
            }
        }

    // ── International Cinema / Animation ─────────────────────────────────────
    suspend fun getAnime(page: Int = 1, forceRefresh: Boolean = false): Result<TmdbPagedResponse<TmdbMovieItem>> =
        withContext(Dispatchers.IO) {
            getCachedOrFetch("anime_$page", forceRefresh) {
                safeApiCall {
                    api.discoverTv(
                        page = page,
                        withGenres = "16", // Animation
                        withOriginalLanguage = "ja",
                        sortBy = "popularity.desc"
                    )
                }
            }
        }

    suspend fun getEastAsian(page: Int = 1, forceRefresh: Boolean = false): Result<TmdbPagedResponse<TmdbMovieItem>> =
        withContext(Dispatchers.IO) {
            getCachedOrFetch("east_asian_$page", forceRefresh) {
                safeApiCall {
                    api.discoverMovies(
                        page = page,
                        withOriginalLanguage = "ko|zh|ja",
                        sortBy = "popularity.desc"
                    )
                }
            }
        }

    // ── Flexible Catalog Discovery ───────────────────────────────────────────
    suspend fun discover(
        isTv: Boolean,
        genreId: Int? = null,
        language: String? = null,
        country: String? = null,
        year: Int? = null,
        sortBy: String? = "popularity.desc",
        page: Int = 1,
        forceRefresh: Boolean = false
    ): Result<TmdbPagedResponse<TmdbMovieItem>> = withContext(Dispatchers.IO) {
        val cacheKey = "discover_${isTv}_${genreId}_${language}_${country}_${year}_${sortBy}_$page"
        getCachedOrFetch(cacheKey, forceRefresh) {
            safeApiCall {
                if (isTv) {
                    api.discoverTv(
                        page = page,
                        sortBy = sortBy,
                        withGenres = genreId?.toString(),
                        withOriginalLanguage = language,
                        withOriginCountry = country,
                        firstAirDateYear = year
                    )
                } else {
                    api.discoverMovies(
                        page = page,
                        sortBy = sortBy,
                        withGenres = genreId?.toString(),
                        withOriginalLanguage = language,
                        withOriginCountry = country,
                        primaryReleaseYear = year
                    )
                }
            }
        }
    }

    // ── Search & Details ─────────────────────────────────────────────────────
    /**
     * Searches TMDB for playable movie and TV content.
     * Person records are filtered out so they never enter the unified movie/TV catalogue.
     */
    suspend fun search(query: String, page: Int = 1, forceRefresh: Boolean = false): Result<TmdbPagedResponse<TmdbMovieItem>> =
        withContext(Dispatchers.IO) {
            val q = query.trim()
            if (q.isBlank()) {
                return@withContext Result.success(TmdbPagedResponse())
            }
            getCachedOrFetch("search_${q}_$page", forceRefresh) {
                safeApiCall {
                    val rawResponse = api.searchMulti(query = q, page = page)
                    val playableItems = rawResponse.results.filter { item ->
                        val type = item.mediaType?.lowercase()
                        type == "movie" || type == "tv" || (!item.isPerson && (item.isTv || !item.releaseDate.isNullOrBlank()))
                    }
                    rawResponse.copy(results = playableItems)
                }
            }
        }

    /**
     * Dedicated person search endpoint to keep person queries separate
     * from playable movie/TV catalogue results.
     */
    suspend fun searchPersons(query: String, page: Int = 1, forceRefresh: Boolean = false): Result<TmdbPagedResponse<TmdbMovieItem>> =
        withContext(Dispatchers.IO) {
            val q = query.trim()
            if (q.isBlank()) {
                return@withContext Result.success(TmdbPagedResponse())
            }
            getCachedOrFetch("search_person_${q}_$page", forceRefresh) {
                safeApiCall {
                    val rawResponse = api.searchMulti(query = q, page = page)
                    val personItems = rawResponse.results.filter { it.isPerson }
                    rawResponse.copy(results = personItems)
                }
            }
        }

    suspend fun getMovieDetail(movieId: Long, forceRefresh: Boolean = false): Result<TmdbDetailResponse> =
        withContext(Dispatchers.IO) {
            getCachedOrFetch("movie_detail_$movieId", forceRefresh) {
                safeApiCall { api.getMovieDetail(movieId) }
            }
        }

    suspend fun getTvDetail(tvId: Long, forceRefresh: Boolean = false): Result<TmdbDetailResponse> =
        withContext(Dispatchers.IO) {
            getCachedOrFetch("tv_detail_$tvId", forceRefresh) {
                safeApiCall { api.getTvDetail(tvId) }
            }
        }

    private inline fun <T> safeApiCall(block: () -> T): Result<T> {
        return try {
            Result.success(block())
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            Result.failure(e)
        }
    }
}
