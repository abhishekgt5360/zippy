package com.example.data.remote

import com.example.BuildConfig
import com.example.data.model.*
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import okhttp3.Cache
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import retrofit2.http.GET
import retrofit2.http.Path
import retrofit2.http.Query
import java.io.File
import java.util.concurrent.TimeUnit

interface TmdbApiService {

    @GET("trending/{media_type}/{time_window}")
    suspend fun getTrending(
        @Path("media_type") mediaType: String = "all",
        @Path("time_window") timeWindow: String = "day",
        @Query("page") page: Int = 1
    ): TmdbPagedResponse<TmdbMovieItem>

    @GET("movie/popular")
    suspend fun getPopularMovies(
        @Query("page") page: Int = 1,
        @Query("region") region: String? = null
    ): TmdbPagedResponse<TmdbMovieItem>

    @GET("tv/popular")
    suspend fun getPopularTv(
        @Query("page") page: Int = 1
    ): TmdbPagedResponse<TmdbMovieItem>

    @GET("movie/top_rated")
    suspend fun getTopRatedMovies(
        @Query("page") page: Int = 1
    ): TmdbPagedResponse<TmdbMovieItem>

    @GET("tv/top_rated")
    suspend fun getTopRatedTv(
        @Query("page") page: Int = 1
    ): TmdbPagedResponse<TmdbMovieItem>

    @GET("movie/now_playing")
    suspend fun getNowPlayingMovies(
        @Query("page") page: Int = 1
    ): TmdbPagedResponse<TmdbMovieItem>

    @GET("movie/upcoming")
    suspend fun getUpcomingMovies(
        @Query("page") page: Int = 1
    ): TmdbPagedResponse<TmdbMovieItem>

    @GET("discover/movie")
    suspend fun discoverMovies(
        @Query("page") page: Int = 1,
        @Query("sort_by") sortBy: String? = "popularity.desc",
        @Query("with_genres") withGenres: String? = null,
        @Query("with_original_language") withOriginalLanguage: String? = null,
        @Query("with_origin_country") withOriginCountry: String? = null,
        @Query("primary_release_year") primaryReleaseYear: Int? = null
    ): TmdbPagedResponse<TmdbMovieItem>

    @GET("discover/tv")
    suspend fun discoverTv(
        @Query("page") page: Int = 1,
        @Query("sort_by") sortBy: String? = "popularity.desc",
        @Query("with_genres") withGenres: String? = null,
        @Query("with_original_language") withOriginalLanguage: String? = null,
        @Query("with_origin_country") withOriginCountry: String? = null,
        @Query("first_air_date_year") firstAirDateYear: Int? = null
    ): TmdbPagedResponse<TmdbMovieItem>

    @GET("search/multi")
    suspend fun searchMulti(
        @Query("query") query: String,
        @Query("page") page: Int = 1
    ): TmdbPagedResponse<TmdbMovieItem>

    @GET("search/movie")
    suspend fun searchMovies(
        @Query("query") query: String,
        @Query("page") page: Int = 1,
        @Query("year") year: Int? = null
    ): TmdbPagedResponse<TmdbMovieItem>

    @GET("search/tv")
    suspend fun searchTv(
        @Query("query") query: String,
        @Query("page") page: Int = 1,
        @Query("first_air_date_year") year: Int? = null
    ): TmdbPagedResponse<TmdbMovieItem>

    @GET("genre/movie/list")
    suspend fun getMovieGenres(): TmdbGenreResponse

    @GET("genre/tv/list")
    suspend fun getTvGenres(): TmdbGenreResponse

    @GET("movie/{movie_id}")
    suspend fun getMovieDetail(
        @Path("movie_id") movieId: Long,
        @Query("append_to_response") append: String = "credits,recommendations,similar"
    ): TmdbDetailResponse

    @GET("tv/{tv_id}")
    suspend fun getTvDetail(
        @Path("tv_id") tvId: Long,
        @Query("append_to_response") append: String = "credits,recommendations,similar"
    ): TmdbDetailResponse
}

object TmdbNetworkClient {
    private const val BASE_URL = "https://api.themoviedb.org/3/"
    private var cacheDir: File? = null

    fun init(cacheDirectory: File) {
        cacheDir = cacheDirectory
    }

    val moshi: Moshi = Moshi.Builder()
        .add(KotlinJsonAdapterFactory())
        .build()

    fun buildPosterUrl(path: String?, size: String = "w500"): String? {
        if (path.isNullOrBlank()) return null
        return if (path.startsWith("http")) path else "https://image.tmdb.org/t/p/$size$path"
    }

    fun buildBackdropUrl(path: String?, size: String = "w1280"): String? {
        if (path.isNullOrBlank()) return null
        return if (path.startsWith("http")) path else "https://image.tmdb.org/t/p/$size$path"
    }

    fun isConfigured(): Boolean {
        val key = getApiKey()
        val token = getAccessToken()
        return key.isNotBlank() || token.isNotBlank()
    }

    private fun getApiKey(): String {
        return try {
            val key = BuildConfig.TMDB_API_KEY
            if (key.isNotBlank() && key != "NOT_SET") key else ""
        } catch (_: Throwable) {
            ""
        }
    }

    private fun getAccessToken(): String {
        return try {
            val token = BuildConfig.TMDB_ACCESS_TOKEN
            if (token.isNotBlank() && token != "NOT_SET") token else ""
        } catch (_: Throwable) {
            ""
        }
    }

    private val authInterceptor = Interceptor { chain ->
        val originalRequest = chain.request()
        val originalUrl = originalRequest.url

        val token = getAccessToken()
        val key = getApiKey()

        val newRequest = when {
            token.isNotBlank() -> {
                originalRequest.newBuilder()
                    .header("Authorization", "Bearer $token")
                    .header("Accept", "application/json")
                    .build()
            }
            key.isNotBlank() -> {
                val newUrl = originalUrl.newBuilder()
                    .addQueryParameter("api_key", key)
                    .build()
                originalRequest.newBuilder()
                    .url(newUrl)
                    .header("Accept", "application/json")
                    .build()
            }
            else -> {
                originalRequest.newBuilder()
                    .header("Accept", "application/json")
                    .build()
            }
        }
        chain.proceed(newRequest)
    }

    private val cachingInterceptor = Interceptor { chain ->
        val response = chain.proceed(chain.request())
        if (chain.request().method.equals("GET", ignoreCase = true) && response.isSuccessful) {
            response.newBuilder()
                .header("Cache-Control", "public, max-age=1800") // 30 minutes
                .removeHeader("Pragma")
                .build()
        } else {
            response
        }
    }

    private val okHttpClient: OkHttpClient by lazy {
        val builder = OkHttpClient.Builder()
            .connectTimeout(12, TimeUnit.SECONDS)
            .readTimeout(18, TimeUnit.SECONDS)
            .writeTimeout(18, TimeUnit.SECONDS)
            .addInterceptor(authInterceptor)
            .addNetworkInterceptor(cachingInterceptor)

        cacheDir?.let { dir ->
            try {
                val httpCacheDir = File(dir, "tmdb_http_cache")
                builder.cache(Cache(httpCacheDir, 40L * 1024 * 1024)) // 40 MB cache
            } catch (_: Exception) {}
        }

        builder.build()
    }

    val api: TmdbApiService by lazy {
        Retrofit.Builder()
            .baseUrl(BASE_URL)
            .client(okHttpClient)
            .addConverterFactory(MoshiConverterFactory.create(moshi))
            .build()
            .create(TmdbApiService::class.java)
    }
}
