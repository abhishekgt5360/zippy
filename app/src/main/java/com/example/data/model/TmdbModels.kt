package com.example.data.model

import com.squareup.moshi.Json
import java.util.Locale

/**
 * Standard data models for The Movie Database (TMDB) API v3.
 * Used for rich catalogue browsing, search, metadata, and high-resolution artwork.
 */
data class TmdbPagedResponse<T>(
    @Json(name = "page") val page: Int = 1,
    @Json(name = "results") val results: List<T> = emptyList(),
    @Json(name = "total_pages") val totalPages: Int = 0,
    @Json(name = "total_results") val totalResults: Int = 0
)

data class TmdbMovieItem(
    @Json(name = "id") val id: Long,
    @Json(name = "title") val title: String? = null,
    @Json(name = "original_title") val originalTitle: String? = null,
    @Json(name = "name") val name: String? = null,
    @Json(name = "original_name") val originalName: String? = null,
    @Json(name = "overview") val overview: String? = null,
    @Json(name = "poster_path") val posterPath: String? = null,
    @Json(name = "backdrop_path") val backdropPath: String? = null,
    @Json(name = "media_type") val mediaType: String? = null,
    @Json(name = "release_date") val releaseDate: String? = null,
    @Json(name = "first_air_date") val firstAirDate: String? = null,
    @Json(name = "vote_average") val voteAverage: Double? = null,
    @Json(name = "vote_count") val voteCount: Int? = null,
    @Json(name = "genre_ids") val genreIds: List<Int>? = null,
    @Json(name = "original_language") val originalLanguage: String? = null,
    @Json(name = "popularity") val popularity: Double? = null,
    @Json(name = "origin_country") val originCountry: List<String>? = null,
    @Json(name = "known_for_department") val knownForDepartment: String? = null,
    @Json(name = "profile_path") val profilePath: String? = null
) {
    val safeTitle: String
        get() = title?.takeIf { it.isNotBlank() }
            ?: name?.takeIf { it.isNotBlank() }
            ?: originalTitle?.takeIf { it.isNotBlank() }
            ?: originalName?.takeIf { it.isNotBlank() }
            ?: "Untitled"

    val safeOriginalTitle: String
        get() = originalTitle?.takeIf { it.isNotBlank() }
            ?: originalName?.takeIf { it.isNotBlank() }
            ?: safeTitle

    val isPerson: Boolean
        get() = mediaType.equals("person", ignoreCase = true)

    val isTv: Boolean
        get() = !isPerson && (mediaType.equals("tv", ignoreCase = true) ||
                (!firstAirDate.isNullOrBlank() && releaseDate.isNullOrBlank()))

    val isMovie: Boolean
        get() = !isPerson && !isTv

    val isPlayableContent: Boolean
        get() = !isPerson && (mediaType.equals("movie", ignoreCase = true) ||
                mediaType.equals("tv", ignoreCase = true) || isTv || isMovie)

    val safeMediaType: String
        get() = when {
            isPerson -> "person"
            isTv -> "tv"
            else -> "movie"
        }

    val year: String?
        get() {
            val date = releaseDate?.takeIf { it.isNotBlank() }
                ?: firstAirDate?.takeIf { it.isNotBlank() }
            return date?.take(4)
        }

    val ratingString: String?
        get() = voteAverage?.takeIf { it > 0.0 }?.let {
            String.format(Locale.US, "%.1f", it)
        }

    fun posterUrl(size: String = "w500"): String? {
        return posterPath?.let { "https://image.tmdb.org/t/p/$size$it" }
    }

    fun backdropUrl(size: String = "w1280"): String? {
        return backdropPath?.let { "https://image.tmdb.org/t/p/$size$it" }
    }

    fun profileUrl(size: String = "w185"): String? {
        return profilePath?.let { "https://image.tmdb.org/t/p/$size$it" }
    }

    /**
     * Bridges this TMDB title directly into the existing UI components
     * ([MoviePosterCard], [HeroBanner]) without requiring layout redesigns.
     */
    fun toMovieItem(genreMap: Map<Int, String> = emptyMap()): MovieItem {
        val resolvedGenre = genreIds?.firstNotNullOfOrNull { genreMap[it] }
        return MovieItem(
            name = safeTitle,
            posterUrl = if (isPerson) profileUrl() else posterUrl(),
            slug = "tmdb_${safeMediaType}_$id",
            subjectId = null, // CRITICAL: NEVER store TMDB ID as MovieBox subjectId!
            badge = when {
                isPerson -> "PERSON"
                isTv -> "TV"
                else -> "MOVIE"
            },
            rating = ratingString,
            year = year,
            genre = resolvedGenre ?: if (isPerson) knownForDepartment else null,
            type = safeMediaType,
            tmdbId = id,
            tmdbMediaType = safeMediaType,
            tmdbOriginalTitle = safeOriginalTitle,
            tmdbOriginalLanguage = originalLanguage,
            tmdbCountry = originCountry?.firstOrNull()
        )
    }
}

data class TmdbGenreResponse(
    @Json(name = "genres") val genres: List<TmdbGenre> = emptyList()
)

data class TmdbGenre(
    @Json(name = "id") val id: Int,
    @Json(name = "name") val name: String
)

data class TmdbDetailResponse(
    @Json(name = "id") val id: Long,
    @Json(name = "title") val title: String? = null,
    @Json(name = "original_title") val originalTitle: String? = null,
    @Json(name = "name") val name: String? = null,
    @Json(name = "original_name") val originalName: String? = null,
    @Json(name = "overview") val overview: String? = null,
    @Json(name = "poster_path") val posterPath: String? = null,
    @Json(name = "backdrop_path") val backdropPath: String? = null,
    @Json(name = "release_date") val releaseDate: String? = null,
    @Json(name = "first_air_date") val firstAirDate: String? = null,
    @Json(name = "vote_average") val voteAverage: Double? = null,
    @Json(name = "vote_count") val voteCount: Int? = null,
    @Json(name = "genres") val genres: List<TmdbGenre>? = null,
    @Json(name = "runtime") val runtime: Int? = null,
    @Json(name = "episode_run_time") val episodeRunTime: List<Int>? = null,
    @Json(name = "number_of_seasons") val numberOfSeasons: Int? = null,
    @Json(name = "number_of_episodes") val numberOfEpisodes: Int? = null,
    @Json(name = "seasons") val seasons: List<TmdbSeasonSummary>? = null,
    @Json(name = "original_language") val originalLanguage: String? = null,
    @Json(name = "origin_country") val originCountry: List<String>? = null,
    @Json(name = "tagline") val tagline: String? = null,
    @Json(name = "status") val status: String? = null,
    @Json(name = "credits") val credits: TmdbCreditsResponse? = null,
    @Json(name = "recommendations") val recommendations: TmdbPagedResponse<TmdbMovieItem>? = null,
    @Json(name = "similar") val similar: TmdbPagedResponse<TmdbMovieItem>? = null
) {
    val safeTitle: String
        get() = title?.takeIf { it.isNotBlank() }
            ?: name?.takeIf { it.isNotBlank() }
            ?: originalTitle?.takeIf { it.isNotBlank() }
            ?: originalName?.takeIf { it.isNotBlank() }
            ?: "Untitled"

    val safeOriginalTitle: String
        get() = originalTitle?.takeIf { it.isNotBlank() }
            ?: originalName?.takeIf { it.isNotBlank() }
            ?: safeTitle

    val isTv: Boolean
        get() = numberOfSeasons != null || (!firstAirDate.isNullOrBlank() && releaseDate.isNullOrBlank())

    val safeMediaType: String
        get() = if (isTv) "tv" else "movie"

    val year: String?
        get() {
            val date = releaseDate?.takeIf { it.isNotBlank() }
                ?: firstAirDate?.takeIf { it.isNotBlank() }
            return date?.take(4)
        }

    val ratingString: String?
        get() = voteAverage?.takeIf { it > 0.0 }?.let {
            String.format(Locale.US, "%.1f", it)
        }

    val durationMinutes: Int?
        get() = runtime?.takeIf { it > 0 } ?: episodeRunTime?.firstOrNull { it > 0 }

    fun posterUrl(size: String = "w500"): String? {
        return posterPath?.let { "https://image.tmdb.org/t/p/$size$it" }
    }

    fun backdropUrl(size: String = "w1280"): String? {
        return backdropPath?.let { "https://image.tmdb.org/t/p/$size$it" }
    }
}

data class TmdbSeasonSummary(
    @Json(name = "id") val id: Long,
    @Json(name = "season_number") val seasonNumber: Int = 1,
    @Json(name = "name") val name: String? = null,
    @Json(name = "episode_count") val episodeCount: Int = 1,
    @Json(name = "poster_path") val posterPath: String? = null
)

data class TmdbCreditsResponse(
    @Json(name = "cast") val cast: List<TmdbCastItem> = emptyList(),
    @Json(name = "crew") val crew: List<TmdbCrewItem> = emptyList()
)

data class TmdbCastItem(
    @Json(name = "id") val id: Long,
    @Json(name = "name") val name: String,
    @Json(name = "character") val character: String? = null,
    @Json(name = "profile_path") val profilePath: String? = null,
    @Json(name = "order") val order: Int? = null
) {
    val profileUrl: String?
        get() = profilePath?.let { "https://image.tmdb.org/t/p/w185$it" }
}

data class TmdbCrewItem(
    @Json(name = "id") val id: Long,
    @Json(name = "name") val name: String,
    @Json(name = "job") val job: String? = null,
    @Json(name = "department") val department: String? = null
)
