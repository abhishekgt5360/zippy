package com.example.data.repository

import com.example.data.local.AppDatabase
import com.example.data.local.EpisodeProgressEntity
import com.example.data.local.WatchHistoryEntity
import com.example.data.local.WatchlistEntity
import com.example.data.model.*
import com.example.data.remote.MovieApiService
import com.example.data.remote.NetworkClient
import com.example.util.CatalogPreset
import com.example.util.RegionalCategoryConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext

class MovieRepository(
    private val api: MovieApiService = NetworkClient.api,
    private val database: AppDatabase,
    context: android.content.Context? = null
) {
    private val cacheDir = context?.applicationContext?.cacheDir ?: context?.cacheDir
    private val dao = database.watchDao()
    private val homeJsonAdapter by lazy { NetworkClient.moshi.adapter(HomeResponse::class.java) }
    private val catalogJsonAdapter by lazy { NetworkClient.moshi.adapter(CatalogResponse::class.java) }
    private val cacheFile by lazy {
        cacheDir?.resolve("cached_home_feed.json")
    }
    private val bollywoodCacheFile by lazy {
        cacheDir?.resolve("cached_bollywood_feed.json")
    }

    private fun writeCacheAtomically(file: java.io.File?, content: String) {
        if (file == null) return
        try {
            val tmpFile = java.io.File(file.parentFile, "${file.name}.tmp")
            tmpFile.writeText(content)
            if (tmpFile.exists()) {
                if (file.exists()) {
                    file.delete()
                }
                tmpFile.renameTo(file)
            }
        } catch (_: Exception) {}
    }

    companion object {
        @Volatile private var inMemoryHome: HomeResponse? = null
        private val catalogCache = java.util.concurrent.ConcurrentHashMap<String, CatalogResponse>()

        /**
         * Single source of truth for catalog cache keys so the ViewModel can look up
         * exactly what [getRegionCatalog] stored.
         */
        fun catalogCacheKey(
            type: com.example.ui.CatalogType,
            page: Int,
            sort: String?,
            genre: String?,
            country: String?,
            year: String?
        ): String = buildString {
            append(type.name.lowercase())
            append("_page=").append(page)
            append("_sort=").append(sort ?: "-")
            append("_genre=").append(genre ?: "-")
            append("_country=").append(country ?: "-")
            append("_year=").append(year ?: "-")
        }

        /**
         * Cache key for the final, user-visible preset result. Single-country presets
         * delegate to [catalogCacheKey] so they line up with [getRegionCatalog]'s cache,
         * while multi-country presets cache their merged (interleaved) result.
         */
        fun presetCacheKey(
            preset: CatalogPreset,
            type: com.example.ui.CatalogType,
            page: Int,
            sort: String?,
            genre: String?,
            year: String?
        ): String {
            val effectiveGenre = preset.genre ?: genre
            val effectiveYear = if (type == com.example.ui.CatalogType.ANIMATION) null else year
            val countryToken = if (preset.countries.isEmpty()) {
                preset.country
            } else {
                "preset=" + preset.id
            }
            return catalogCacheKey(type, page, sort, effectiveGenre, countryToken, effectiveYear)
        }
    }

    fun getCachedHome(): HomeResponse? {
        inMemoryHome?.let { return it }
        try {
            val file = cacheFile
            if (file != null && file.exists()) {
                val json = file.readText()
                val parsed = homeJsonAdapter.fromJson(json)
                if (parsed != null) {
                    inMemoryHome = parsed
                    return parsed
                }
            }
        } catch (_: Exception) {}
        return null
    }

    suspend fun getHome(): Result<HomeResponse> = withContext(Dispatchers.IO) {
        try {
            val response = api.getHome()
            if (!response.sections.isNullOrEmpty()) {
                inMemoryHome = response
                try {
                    cacheFile?.writeText(homeJsonAdapter.toJson(response))
                } catch (_: Exception) {}
            }
            Result.success(response)
        } catch (e: Exception) {
            val cached = getCachedHome()
            if (cached != null) {
                Result.success(cached)
            } else {
                Result.failure(e)
            }
        }
    }

    fun getCachedCatalog(key: String): CatalogResponse? {
        return catalogCache[key]
    }

    fun getCachedBollywood(): CatalogResponse? {
        try {
            val file = bollywoodCacheFile
            if (file != null && file.exists() && file.length() > 0) {
                val json = file.readText()
                val parsed = catalogJsonAdapter.fromJson(json)
                if (parsed != null && !parsed.items.isNullOrEmpty()) {
                    return parsed
                }
            }
        } catch (_: Exception) {}
        return null
    }

    suspend fun getRegionCatalog(
        type: com.example.ui.CatalogType = com.example.ui.CatalogType.MOVIES,
        country: String? = null,
        genre: String? = null,
        sort: String? = null,
        year: String? = null,
        page: Int = 1
    ): Result<CatalogResponse> = withContext(Dispatchers.IO) {
        // Animation has no country/year parameters on the API.
        val effectiveCountry = if (type == com.example.ui.CatalogType.ANIMATION) null else country
        val effectiveYear = if (type == com.example.ui.CatalogType.ANIMATION) null else year
        val cacheKey = catalogCacheKey(type, page, sort, genre, effectiveCountry, effectiveYear)
        try {
            val response = when (type) {
                com.example.ui.CatalogType.MOVIES -> api.getMovies(page = page, sort = sort, genre = genre, country = effectiveCountry, year = effectiveYear)
                com.example.ui.CatalogType.TV_SERIES -> api.getTvSeries(page = page, sort = sort, genre = genre, country = effectiveCountry, year = effectiveYear)
                com.example.ui.CatalogType.ANIMATION -> api.getAnimation(page = page, sort = sort, genre = genre)
            }

            if (response.items.isNotEmpty()) {
                catalogCache[cacheKey] = response
                // Write cached_bollywood_feed.json ONLY for the unfiltered India movies feed.
                if (page == 1 && effectiveCountry != null && genre == null && sort == null &&
                    effectiveYear == null && type == com.example.ui.CatalogType.MOVIES
                ) {
                    writeCacheAtomically(bollywoodCacheFile, catalogJsonAdapter.toJson(response))
                }
            }
            Result.success(response)
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            catalogCache[cacheKey]?.let { return@withContext Result.success(it) }
            if (page == 1 && effectiveCountry != null && genre == null && sort == null &&
                effectiveYear == null && type == com.example.ui.CatalogType.MOVIES
            ) {
                val cached = getCachedBollywood()
                if (cached != null) {
                    return@withContext Result.success(cached)
                }
            }
            Result.failure(e)
        }
    }

    suspend fun getMovies(
        page: Int,
        sort: String?,
        genre: String?,
        country: String? = null,
        year: String? = null
    ): Result<CatalogResponse> =
        getRegionCatalog(type = com.example.ui.CatalogType.MOVIES, country = country, genre = genre, sort = sort, year = year, page = page)

    suspend fun getTvSeries(
        page: Int,
        sort: String?,
        genre: String?,
        country: String? = null,
        year: String? = null
    ): Result<CatalogResponse> =
        getRegionCatalog(type = com.example.ui.CatalogType.TV_SERIES, country = country, genre = genre, sort = sort, year = year, page = page)

    suspend fun getAnimation(page: Int, sort: String?, genre: String?): Result<CatalogResponse> =
        getRegionCatalog(type = com.example.ui.CatalogType.ANIMATION, genre = genre, sort = sort, page = page)

    suspend fun getBollywoodMovies(page: Int = 1): Result<CatalogResponse> =
        getRegionCatalog(type = com.example.ui.CatalogType.MOVIES, country = "India", page = page)

    suspend fun getPresetCatalog(
        preset: CatalogPreset,
        type: com.example.ui.CatalogType = com.example.ui.CatalogType.MOVIES,
        genre: String? = null,
        sort: String? = null,
        year: String? = null,
        page: Int = 1
    ): Result<CatalogResponse> = withContext(Dispatchers.IO) {
        val presetKey = presetCacheKey(preset, type, page, sort, genre, year)
        if (preset.countries.isNotEmpty()) {
            try {
                val countryResponses = coroutineScope {
                    preset.countries.map { countryName ->
                        async {
                            getRegionCatalog(
                                type = type,
                                country = countryName,
                                genre = preset.genre ?: genre,
                                sort = sort,
                                year = year,
                                page = page
                            ).getOrNull()
                        }
                    }.awaitAll().filterNotNull()
                }

                if (countryResponses.isEmpty()) {
                    catalogCache[presetKey]?.let { return@withContext Result.success(it) }
                    return@withContext Result.failure(java.io.IOException("Failed to load multi-country catalog"))
                }

                val lists = countryResponses.map { it.items }
                val maxListLen = lists.maxOfOrNull { it.size } ?: 0
                val interleaved = mutableListOf<MovieItem>()
                val seenIds = mutableSetOf<String>()

                for (i in 0 until maxListLen) {
                    for (list in lists) {
                        if (i < list.size) {
                            val item = list[i]
                            val id = item.subjectId.ifBlank { item.safeSlug }
                            if (id.isNotBlank() && seenIds.add(id)) {
                                interleaved.add(item)
                            }
                        }
                    }
                }

                val hasMore = countryResponses.any { it.hasMore == true }
                val merged = CatalogResponse(page = page, hasMore = hasMore, items = interleaved)
                if (interleaved.isNotEmpty()) {
                    catalogCache[presetKey] = merged
                }
                Result.success(merged)
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                catalogCache[presetKey]?.let { return@withContext Result.success(it) }
                Result.failure(e)
            }
        } else {
            getRegionCatalog(
                type = type,
                country = preset.country,
                genre = preset.genre ?: genre,
                sort = sort,
                year = year,
                page = page
            )
        }
    }

    suspend fun search(query: String, page: Int = 1): Result<SearchResponse> =
        withContext(Dispatchers.IO) {
            try {
                val response = api.search(query = query, page = page)
                Result.success(response)
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                Result.failure(e)
            }
        }

    suspend fun getSuggestions(query: String): Result<SuggestResponse> =
        withContext(Dispatchers.IO) {
            try {
                val response = api.getSuggestions(query)
                Result.success(response)
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                Result.failure(e)
            }
        }

    suspend fun getDetail(slug: String): Result<DetailResponse> =
        withContext(Dispatchers.IO) {
            if (!MovieBoxPlaybackGuard.isValidMovieBoxSlug(slug)) {
                return@withContext Result.failure(
                    IllegalArgumentException("Cannot request MovieBox detail: Invalid or TMDB-generated slug '$slug'.")
                )
            }
            try {
                val response = api.getDetail(slug)
                Result.success(response)
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                Result.failure(e)
            }
        }

    suspend fun getStream(
        subjectId: String,
        slug: String,
        season: Int,
        episode: Int,
        isSeries: Boolean = false
    ): Result<StreamResponse> = withContext(Dispatchers.IO) {
        val tmdbIdFromSlug = MovieBoxPlaybackGuard.extractTmdbIdFromSlug(slug)
        if (!MovieBoxPlaybackGuard.canInitiatePlayback(subjectId, slug, tmdbIdFromSlug)) {
            return@withContext Result.failure(
                IllegalArgumentException("Cannot request stream: Invalid or unverified MovieBox subjectId '$subjectId' or slug '$slug'.")
            )
        }
        try {
            // For movies, Zar MovieBox API explicitly expects se=0, ep=0!
            val initialSe = if (isSeries) season.coerceAtLeast(1) else 0
            val initialEp = if (isSeries) episode.coerceAtLeast(1) else 0

            var usableResponse: StreamResponse? = null
            var anyNetworkFailure = false
            var lastNetworkException: Exception? = null
            var lastEmptyResponse: StreamResponse? = null

            try {
                val initial = api.getStream(subjectId = subjectId, detailPath = slug, season = initialSe, episode = initialEp)
                if (initial.hasUsableStreams) {
                    usableResponse = initial
                } else {
                    lastEmptyResponse = initial
                }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                anyNetworkFailure = true
                lastNetworkException = e
            }

            if (usableResponse == null) {
                // For TV series, alternative (0,0)/(1,0)/(0,1) indexing is only valid for Season 1 Episode 1
                // where providers may use 0-based vs 1-based indexing for the pilot episode.
                // For later episodes (e.g. S2E5), falling back to (1,1) would wrongly substitute S1E1.
                val fallbacks = if (isSeries) {
                    if (initialSe <= 1 && initialEp <= 1) {
                        listOf(Pair(0, 0), Pair(1, 1), Pair(0, 1), Pair(1, 0))
                    } else {
                        emptyList()
                    }
                } else {
                    listOf(Pair(0, 0), Pair(1, 1), Pair(0, 1), Pair(1, 0))
                }.filter { it.first != initialSe || it.second != initialEp }

                for ((s, e) in fallbacks) {
                    try {
                        val alt = api.getStream(subjectId = subjectId, detailPath = slug, season = s, episode = e)
                        if (alt.hasUsableStreams) {
                            usableResponse = alt
                            break
                        } else {
                            lastEmptyResponse = alt
                        }
                    } catch (e: Exception) {
                        if (e is kotlinx.coroutines.CancellationException) throw e
                        anyNetworkFailure = true
                        lastNetworkException = e
                    }
                }
            }

            // C. At least one request returned usable streams:
            if (usableResponse != null) {
                // TV Episode Identity Protection:
                // If this is a TV series request beyond the pilot episode, verify that provider metadata
                // did not substitute a different season or episode (e.g., silently serving S1E1 for S2E5).
                val isEpisodeMismatch = isSeries && (initialSe > 1 || initialEp > 1) &&
                    usableResponse.se != null && usableResponse.ep != null &&
                    (usableResponse.se != initialSe || usableResponse.ep != initialEp)

                if (isEpisodeMismatch) {
                    val error = IllegalStateException(
                        "Episode identity mismatch: requested S${initialSe}E${initialEp} but provider returned S${usableResponse.se}E${usableResponse.ep}"
                    )
                    Result.failure(error)
                } else {
                    val cleanSources = usableResponse.effectiveSources.filter { it.url.isNotBlank() }
                    val finalResponse = if (cleanSources.isNotEmpty()) {
                        usableResponse.copy(sources = cleanSources)
                    } else {
                        usableResponse.copy(sources = emptyList(), hasResource = false)
                    }
                    Result.success(finalResponse)
                }
            } else if (anyNetworkFailure) {
                // B. Requests failed due to network errors and no usable stream was found:
                // Return Result.failure(exception). Do not silently return an empty response from another attempt.
                val error = lastNetworkException ?: java.io.IOException("Stream fallback encountered network failures with no usable streams")
                Result.failure(error)
            } else {
                // A. All attempted requests completed successfully and returned no usable streams
                val emptyResult = lastEmptyResponse ?: StreamResponse(
                    rawSubjectId = subjectId,
                    hasResource = false,
                    sources = emptyList()
                )
                Result.success(emptyResult)
            }
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            Result.failure(e)
        }
    }

    suspend fun getCaptions(
        subjectId: String,
        slug: String,
        season: Int,
        episode: Int,
        isSeries: Boolean = false
    ): Result<CaptionsResponse> = withContext(Dispatchers.IO) {
        val tmdbIdFromSlug = MovieBoxPlaybackGuard.extractTmdbIdFromSlug(slug)
        if (!MovieBoxPlaybackGuard.canInitiatePlayback(subjectId, slug, tmdbIdFromSlug)) {
            return@withContext Result.failure(
                IllegalArgumentException("Cannot request captions: Invalid or unverified MovieBox subjectId '$subjectId' or slug '$slug'.")
            )
        }
        try {
            val initialSe = if (isSeries) season.coerceAtLeast(1) else 0
            val initialEp = if (isSeries) episode.coerceAtLeast(1) else 0

            var response = try {
                api.getCaptions(subjectId = subjectId, detailPath = slug, season = initialSe, episode = initialEp)
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                CaptionsResponse(subjectId = subjectId, captions = emptyList())
            }

            if (response.captions.isEmpty()) {
                val fallbacks = if (isSeries) {
                    if (initialSe <= 1 && initialEp <= 1) {
                        listOf(Pair(0, 0), Pair(1, 1), Pair(0, 1), Pair(1, 0))
                    } else {
                        emptyList()
                    }
                } else {
                    listOf(Pair(0, 0), Pair(1, 1), Pair(0, 1), Pair(1, 0))
                }.filter { it.first != initialSe || it.second != initialEp }

                for ((s, e) in fallbacks) {
                    try {
                        val alt = api.getCaptions(subjectId = subjectId, detailPath = slug, season = s, episode = e)
                        if (alt.captions.isNotEmpty()) {
                            response = alt
                            break
                        }
                    } catch (e: Exception) {
                        if (e is kotlinx.coroutines.CancellationException) throw e
                    }
                }
            }
            Result.success(response)
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            Result.failure(e)
        }
    }

    // Local Watch History
    val watchHistory: Flow<List<WatchHistoryEntity>> = dao.getAllHistory()

    suspend fun getHistoryItem(subjectId: String): WatchHistoryEntity? = withContext(Dispatchers.IO) {
        try {
            dao.getHistory(subjectId)
        } catch (e: Exception) {
            null
        }
    }

    suspend fun getEpisodeProgress(
        subjectId: String,
        season: Int,
        episode: Int,
        isSeries: Boolean
    ): Long = withContext(Dispatchers.IO) {
        if (!MovieBoxPlaybackGuard.isValidMovieBoxSubjectId(subjectId)) return@withContext 0L
        val se = if (isSeries) season.coerceAtLeast(1) else 0
        val ep = if (isSeries) episode.coerceAtLeast(1) else 0
        try {
            val epProgress = dao.getEpisodeProgress(subjectId, se, ep)
            if (epProgress != null) {
                return@withContext com.example.util.ResumeManager.calculateStartPosition(
                    positionMs = epProgress.positionMs,
                    durationMs = epProgress.durationMs,
                    finished = epProgress.finished
                )
            }

            // Legacy Fallback: check watch_history
            val history = dao.getHistory(subjectId)
            if (history != null && history.season == se && history.episode == ep) {
                return@withContext com.example.util.ResumeManager.calculateStartPosition(
                    positionMs = history.positionMs,
                    durationMs = history.durationMs,
                    finished = false
                )
            }
        } catch (_: Exception) {}
        return@withContext 0L
    }

    suspend fun resetEpisodeProgress(
        subjectId: String,
        season: Int,
        episode: Int,
        isSeries: Boolean
    ) = withContext(Dispatchers.IO) {
        if (!MovieBoxPlaybackGuard.isValidMovieBoxSubjectId(subjectId)) return@withContext
        val se = if (isSeries) season.coerceAtLeast(1) else 0
        val ep = if (isSeries) episode.coerceAtLeast(1) else 0
        try {
            dao.upsertEpisodeProgress(
                EpisodeProgressEntity(
                    subjectId = subjectId,
                    season = se,
                    episode = ep,
                    positionMs = 0L,
                    durationMs = 0L,
                    updatedAt = System.currentTimeMillis(),
                    finished = true
                )
            )
        } catch (_: Exception) {}
    }

    suspend fun saveWatchProgress(
        subjectId: String,
        slug: String,
        title: String,
        posterUrl: String?,
        season: Int,
        episode: Int,
        positionMs: Long,
        durationMs: Long,
        type: String?,
        tmdbId: Long? = null,
        tmdbMediaType: String? = null
    ) = withContext(Dispatchers.IO) {
        if (!MovieBoxPlaybackGuard.canInitiatePlayback(subjectId, slug)) return@withContext
        val isSeries = type.equals("tv", ignoreCase = true) || type.equals("tv-series", ignoreCase = true)
        val se = if (isSeries) season.coerceAtLeast(1) else 0
        val ep = if (isSeries) episode.coerceAtLeast(1) else 0

        val finished = com.example.util.ResumeManager.isFinished(positionMs, durationMs)
        val shouldSave = com.example.util.ResumeManager.shouldSavePosition(positionMs, durationMs) || finished

        try {
            if (shouldSave) {
                val savePos = if (finished) 0L else positionMs
                dao.upsertEpisodeProgress(
                    EpisodeProgressEntity(
                        subjectId = subjectId,
                        season = se,
                        episode = ep,
                        positionMs = savePos,
                        durationMs = durationMs,
                        updatedAt = System.currentTimeMillis(),
                        finished = finished
                    )
                )

                val existing = dao.getHistory(subjectId)
                val finalTmdbId = tmdbId ?: existing?.tmdbId
                val finalTmdbMediaType = tmdbMediaType ?: existing?.tmdbMediaType

                dao.upsertHistory(
                    WatchHistoryEntity(
                        subjectId = subjectId,
                        slug = slug,
                        title = title,
                        posterUrl = posterUrl,
                        season = se,
                        episode = ep,
                        positionMs = savePos,
                        durationMs = durationMs,
                        lastWatchedTimestamp = System.currentTimeMillis(),
                        type = type,
                        tmdbId = finalTmdbId,
                        tmdbMediaType = finalTmdbMediaType
                    )
                )
            }
        } catch (_: Exception) {}
    }

    suspend fun deleteHistory(subjectId: String) = withContext(Dispatchers.IO) {
        try {
            dao.deleteHistory(subjectId)
            dao.deleteEpisodeProgressForSubject(subjectId)
        } catch (_: Exception) {}
    }

    suspend fun clearHistory() = withContext(Dispatchers.IO) {
        try {
            dao.clearHistory()
            dao.clearEpisodeProgress()
        } catch (_: Exception) {}
    }

    suspend fun updateWatchlistPosterIfMissing(subjectId: String, posterUrl: String) = withContext(Dispatchers.IO) {
        if (!MovieBoxPlaybackGuard.isValidMovieBoxSubjectId(subjectId) || posterUrl.isBlank()) return@withContext
        try {
            val existing = dao.getWatchlistItem(subjectId)
            if (existing != null && existing.posterUrl.isNullOrBlank()) {
                dao.addToWatchlist(existing.copy(posterUrl = posterUrl))
            }
        } catch (_: Exception) {}
    }

    suspend fun updateHistoryPosterIfMissing(subjectId: String, posterUrl: String) = withContext(Dispatchers.IO) {
        if (!MovieBoxPlaybackGuard.isValidMovieBoxSubjectId(subjectId) || posterUrl.isBlank()) return@withContext
        try {
            val existing = dao.getHistory(subjectId)
            if (existing != null && existing.posterUrl.isNullOrBlank()) {
                dao.upsertHistory(existing.copy(posterUrl = posterUrl))
            }
        } catch (_: Exception) {}
    }

    // Local Watchlist
    val watchlist: Flow<List<WatchlistEntity>> = dao.getAllWatchlist()

    fun isWatchlisted(subjectId: String): Flow<Boolean> {
        if (!MovieBoxPlaybackGuard.isValidMovieBoxSubjectId(subjectId)) {
            return kotlinx.coroutines.flow.flowOf(false)
        }
        return dao.isWatchlisted(subjectId)
    }

    fun isTmdbWatchlisted(tmdbId: Long, mediaType: String): Flow<Boolean> {
        return dao.isTmdbWatchlisted(tmdbId, mediaType)
    }

    suspend fun toggleWatchlist(
        subjectId: String?,
        slug: String,
        title: String,
        posterUrl: String?,
        rating: String?,
        year: String?,
        genre: String?,
        type: String?,
        currentlyWatchlisted: Boolean,
        tmdbId: Long? = null,
        tmdbMediaType: String? = null,
        tmdbOriginalTitle: String? = null,
        tmdbCountry: String? = null,
        tmdbOriginalLanguage: String? = null
    ) = withContext(Dispatchers.IO) {
        val isTmdb = tmdbId != null && tmdbId > 0L
        if (!isTmdb && !MovieBoxPlaybackGuard.canInitiatePlayback(subjectId, slug)) return@withContext

        val id = if (isTmdb) "tmdb_${tmdbMediaType ?: "movie"}_$tmdbId" else (subjectId ?: slug)
        try {
            if (currentlyWatchlisted) {
                if (isTmdb) {
                    dao.removeTmdbFromWatchlist(tmdbId, tmdbMediaType ?: "movie")
                    dao.removeFromWatchlist(id)
                } else if (!subjectId.isNullOrBlank()) {
                    dao.removeFromWatchlist(subjectId)
                }
            } else {
                dao.addToWatchlist(
                    WatchlistEntity(
                        id = id,
                        subjectId = if (isTmdb) null else subjectId,
                        slug = slug,
                        title = title,
                        posterUrl = posterUrl,
                        rating = rating,
                        year = year,
                        genre = genre,
                        type = type,
                        addedTimestamp = System.currentTimeMillis(),
                        tmdbId = tmdbId,
                        tmdbMediaType = tmdbMediaType,
                        tmdbOriginalTitle = tmdbOriginalTitle,
                        tmdbCountry = tmdbCountry,
                        tmdbOriginalLanguage = tmdbOriginalLanguage
                    )
                )
            }
        } catch (_: Exception) {}
    }
}
