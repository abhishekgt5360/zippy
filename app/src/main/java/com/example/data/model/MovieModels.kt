package com.example.data.model

import com.squareup.moshi.Json

data class HealthResponse(
    val status: String? = null,
    val time: String? = null
)

data class HomeResponse(
    val status: String? = null,
    val sections: List<HomeSection>? = null
)

data class HomeSection(
    val section: String,
    val count: Int? = null,
    val items: List<MovieItem> = emptyList()
)

data class MovieItem(
    val name: String? = "",
    @Json(name = "poster_url") val posterUrl: String? = null,
    val slug: String? = "",
    @Json(name = "subject_id") val rawSubjectId: Any? = null,
    val badge: String? = null,
    @Json(name = "rating") val rawRating: Any? = null,
    @Json(name = "year") val rawYear: Any? = null,
    val genre: String? = null,
    @Json(name = "type") val rawType: Any? = null,
    // Present on /search results; catalog rows omit them.
    val country: String? = null,
    val language: String? = null,
    // Dedicated TMDB identifiers to prevent ID confusion
    val tmdbId: Long? = null,
    val tmdbMediaType: String? = null,
    val tmdbOriginalTitle: String? = null,
    val tmdbOriginalLanguage: String? = null,
    val tmdbCountry: String? = null
) {
    constructor(
        name: String? = "",
        posterUrl: String? = null,
        slug: String? = "",
        subjectId: String? = null,
        badge: String? = null,
        rating: String? = null,
        year: String? = null,
        genre: String? = null,
        type: String? = null,
        tmdbId: Long? = null,
        tmdbMediaType: String? = null,
        tmdbOriginalTitle: String? = null,
        tmdbOriginalLanguage: String? = null,
        tmdbCountry: String? = null
    ) : this(
        name = name,
        posterUrl = posterUrl,
        slug = slug,
        rawSubjectId = subjectId,
        badge = badge,
        rawRating = rating,
        rawYear = year,
        genre = genre,
        rawType = type,
        country = tmdbCountry,
        language = tmdbOriginalLanguage,
        tmdbId = tmdbId,
        tmdbMediaType = tmdbMediaType,
        tmdbOriginalTitle = tmdbOriginalTitle,
        tmdbOriginalLanguage = tmdbOriginalLanguage,
        tmdbCountry = tmdbCountry
    )

    val subjectId: String
        get() = rawSubjectId?.toString() ?: ""

    val isFromTmdb: Boolean
        get() = tmdbId != null && tmdbId > 0L

    val hasMovieBoxSubjectId: Boolean
        get() = MovieBoxPlaybackGuard.isValidMovieBoxSubjectId(rawSubjectId?.toString(), tmdbId)

    val hasMovieBoxSlug: Boolean
        get() = MovieBoxPlaybackGuard.isValidMovieBoxSlug(slug)

    val canInitiatePlayback: Boolean
        get() = MovieBoxPlaybackGuard.canInitiatePlayback(subjectId, slug, tmdbId)

    val safeName: String
        get() = name ?: "Untitled"

    val safeSlug: String
        get() = slug ?: ""

    val uniqueItemKey: String
        get() = if (isFromTmdb) "tmdb_${tmdbMediaType ?: "item"}_$tmdbId" else "${safeSlug}_$subjectId"

    val rating: String?
        get() = rawRating?.toString()?.takeIf { it.isNotBlank() }

    val year: String?
        get() = when (rawYear) {
            is Number -> rawYear.toInt().toString()
            else -> rawYear?.toString()?.takeIf { it.isNotBlank() }
        }

    val type: String?
        get() = when (rawType) {
            is Number -> {
                val intVal = rawType.toInt()
                when (intVal) {
                    1 -> "movie"
                    2 -> "tv"
                    else -> intVal.toString()
                }
            }
            else -> rawType?.toString()
        }

    val isTvSeries: Boolean
        get() = type.equals("tv", ignoreCase = true) || type.equals("tv-series", ignoreCase = true)
}

/**
 * Production gatekeeper ensuring TMDB records and MovieBox streaming IDs never cross paths.
 * Protects MovieBox playback, detail endpoints, and streaming resources from unverified or invalid IDs.
 */
object MovieBoxPlaybackGuard {
    /**
     * Verifies that a given subject ID is a legitimate MovieBox subject ID.
     * Rejects null, blank, and TMDB-prefixed IDs ("tmdb_...").
     * Note: Cross-namespace numerical equality is not rejected because separate systems
     * can coincidentally share numeric IDs. The caller verifies verified mapping status.
     */
    fun isValidMovieBoxSubjectId(subjectId: String?, tmdbId: Long? = null): Boolean {
        if (subjectId.isNullOrBlank()) return false
        val trimmed = subjectId.trim()
        if (trimmed.isEmpty()) return false
        if (trimmed.startsWith("tmdb_", ignoreCase = true)) return false
        return true
    }

    /**
     * Verifies that a given slug is a legitimate MovieBox detail slug.
     * Rejects null, blank, and TMDB-prefixed slugs ("tmdb_movie_...", "tmdb_tv_...").
     */
    fun isValidMovieBoxSlug(slug: String?): Boolean {
        if (slug.isNullOrBlank()) return false
        val trimmed = slug.trim()
        if (trimmed.isEmpty()) return false
        if (trimmed.startsWith("tmdb_", ignoreCase = true)) return false
        return true
    }

    /**
     * Determines whether playback can safely be initiated against MovieBox streaming.
     * Requires BOTH a valid MovieBox subject ID and a valid MovieBox detail slug.
     */
    fun canInitiatePlayback(subjectId: String?, slug: String?, tmdbId: Long? = null): Boolean {
        return isValidMovieBoxSubjectId(subjectId, tmdbId) && isValidMovieBoxSlug(slug)
    }

    /**
     * Extracts the TMDB ID from a TMDB-style slug (e.g., "tmdb_movie_550" -> 550L).
     */
    fun extractTmdbIdFromSlug(slug: String?): Long? {
        if (slug == null || !slug.startsWith("tmdb_", ignoreCase = true)) return null
        return slug.substringAfterLast('_').toLongOrNull()
    }
}

data class CatalogResponse(
    val page: Int? = 1,
    @Json(name = "per_page") val perPage: Int? = 24,
    @Json(name = "has_more") val hasMore: Boolean? = false,
    val total: Long? = 0,
    val items: List<MovieItem> = emptyList()
)

data class SearchResponse(
    val query: String? = null,
    val page: Int? = 1,
    val total: Long? = 0,
    val items: List<MovieItem> = emptyList()
)

data class SuggestResponse(
    val suggestions: List<SuggestionItem> = emptyList()
)

data class SuggestionItem(
    val title: String? = "",
    val slug: String? = null,
    @Json(name = "subject_id") val rawSubjectId: Any? = null
) {
    val subjectId: String?
        get() = rawSubjectId?.toString()
}

data class DetailResponse(
    val code: Int? = 0,
    val message: String? = null,
    val data: DetailData? = null
)

data class DetailData(
    val subject: SubjectDetail? = null,
    val stars: List<StarItem>? = null,
    val resource: ResourceDetail? = null
)

data class SubjectDetail(
    @Json(name = "subjectId") val rawSubjectId: Any? = null,
    @Json(name = "subjectType") val rawSubjectType: Any? = null,
    val title: String? = null,
    val description: String? = null,
    val releaseDate: String? = null,
    @Json(name = "duration") val rawDuration: Any? = null,
    val genre: String? = null,
    val countryName: String? = null,
    @Json(name = "imdbRatingValue") val rawRating: Any? = null,
    val subtitles: String? = null,
    val hasResource: Boolean? = true,
    val cover: CoverDetail? = null,
    val trailer: TrailerDetail? = null,
    @Json(name = "isPornographic") val isPornographic: Boolean? = null
) {
    val subjectId: String?
        get() = rawSubjectId?.toString()

    val subjectType: Int?
        get() = when (rawSubjectType) {
            is Number -> rawSubjectType.toInt()
            is String -> rawSubjectType.toIntOrNull()
            else -> null
        }

    val duration: Long?
        get() = when (rawDuration) {
            is Number -> rawDuration.toLong()
            is String -> rawDuration.toLongOrNull()
            else -> null
        }

    val imdbRatingValue: String?
        get() = rawRating?.toString()?.takeIf { it.isNotBlank() }

    val isSeries: Boolean
        get() = subjectType == 2
}

data class StarItem(
    val name: String? = null,
    @Json(name = "character") val character: String? = null,
    @Json(name = "avatarUrl") val avatarUrl: String? = null,
    @Json(name = "staffType") val staffType: Int? = null
)

data class CoverDetail(
    val url: String? = null,
    val avgHueLight: String? = null,
    val avgHueDark: String? = null
)

data class TrailerDetail(
    val videoAddress: TrailerVideo? = null,
    val cover: CoverDetail? = null
)

data class TrailerVideo(
    val url: String? = null,
    val duration: Long? = null
)

data class ResourceDetail(
    val seasons: List<SeasonItem>? = null
)

data class SeasonItem(
    val se: Int = 1,
    val maxEp: Int = 1,
    val allEp: String? = null,
    val resolutions: List<SeasonResolution>? = null
)

data class SeasonResolution(
    val resolution: Int? = null,
    val epNum: Int? = null
)

data class StreamResponse(
    @Json(name = "subject_id") val rawSubjectId: Any? = null,
    val se: Int? = null,
    val ep: Int? = null,
    @Json(name = "has_resource") val hasResource: Boolean = false,
    val sources: List<StreamSource> = emptyList(),
    val hls: List<HlsStream> = emptyList(),
    val dash: List<DashStream> = emptyList(),
    @Json(name = "free_episodes") val freeEpisodes: Int? = null,
    val limited: Boolean? = false,
    val note: String? = null
) {
    constructor(
        subjectId: String?,
        se: Int? = null,
        ep: Int? = null,
        hasResource: Boolean = false,
        sources: List<StreamSource> = emptyList(),
        hls: List<HlsStream> = emptyList(),
        dash: List<DashStream> = emptyList(),
        freeEpisodes: Int? = null,
        limited: Boolean? = false,
        note: String? = null
    ) : this(
        rawSubjectId = subjectId,
        se = se,
        ep = ep,
        hasResource = hasResource,
        sources = sources,
        hls = hls,
        dash = dash,
        freeEpisodes = freeEpisodes,
        limited = limited,
        note = note
    )

    val subjectId: String?
        get() = rawSubjectId?.toString()

    val hasUsableStreams: Boolean
        get() = hasResource && (
            sources.any { it.url.isNotBlank() } ||
            hls.any { !it.url.isNullOrBlank() } ||
            dash.any { !it.url.isNullOrBlank() }
        )

    val effectiveSources: List<StreamSource>
        get() {
            val validSources = sources.filter { it.url.isNotBlank() }
            if (validSources.isNotEmpty()) return validSources
            val hlsConverted = hls.mapNotNull { h ->
                h.url?.takeIf { it.isNotBlank() }?.let { u ->
                    StreamSource(resolution = h.resolution ?: "HLS", format = "HLS", url = u)
                }
            }
            if (hlsConverted.isNotEmpty()) return hlsConverted
            val dashConverted = dash.mapNotNull { d ->
                d.url?.takeIf { it.isNotBlank() }?.let { u ->
                    StreamSource(resolution = d.resolution ?: "DASH", format = "DASH", url = u)
                }
            }
            return dashConverted
        }
}

data class StreamSource(
    val resolution: String = "Auto",
    val format: String? = "MP4",
    val url: String = "",
    @Json(name = "size") val rawSize: Any? = null,
    @Json(name = "duration") val rawDuration: Any? = null,
    val codec: String? = null
) {
    val size: String?
        get() = rawSize?.toString()

    val duration: Long?
        get() = when (rawDuration) {
            is Number -> rawDuration.toLong()
            is String -> rawDuration.toLongOrNull()
            else -> null
        }

    val formattedSize: String?
        get() {
            val bytes = size?.toLongOrNull() ?: return null
            val mb = bytes / (1024.0 * 1024.0)
            return if (mb >= 1024.0) {
                String.format(java.util.Locale.US, "%.1f GB", mb / 1024.0)
            } else {
                String.format(java.util.Locale.US, "%.0f MB", mb)
            }
        }

    /**
     * Resolves the stream MIME type based on explicit format metadata first,
     * followed by URL path/extension inspection (excluding query parameters).
     */
    fun resolveMimeType(): String? {
        val fmt = format?.trim()?.uppercase()
        if (fmt == "HLS" || fmt == "M3U8") return "application/x-mpegURL"
        if (fmt == "DASH" || fmt == "MPD") return "application/dash+xml"
        if (fmt == "MP4") return "video/mp4"
        if (fmt == "MKV") return "video/x-matroska"

        val cleanPath = try {
            val idx = url.indexOf('?')
            val path = if (idx >= 0) url.substring(0, idx) else url
            path.lowercase()
        } catch (_: Exception) {
            url.lowercase()
        }

        return when {
            cleanPath.endsWith(".m3u8") -> "application/x-mpegURL"
            cleanPath.endsWith(".mpd") -> "application/dash+xml"
            cleanPath.endsWith(".mp4") -> "video/mp4"
            cleanPath.endsWith(".mkv") -> "video/x-matroska"
            else -> null
        }
    }

    val isDownloadable: Boolean
        get() {
            val fmt = format?.trim()?.uppercase()
            if (fmt == "HLS" || fmt == "M3U8" || fmt == "DASH" || fmt == "MPD") return false
            val cleanPath = try {
                val idx = url.indexOf('?')
                val path = if (idx >= 0) url.substring(0, idx) else url
                path.lowercase()
            } catch (_: Exception) {
                url.lowercase()
            }
            if (cleanPath.endsWith(".m3u8") || cleanPath.endsWith(".mpd")) return false
            return url.isNotBlank()
        }
}

data class HlsStream(
    val resolution: String? = null,
    val url: String? = null
)

data class DashStream(
    val resolution: String? = null,
    val url: String? = null
)

data class CaptionsResponse(
    @Json(name = "subject_id") val rawSubjectId: Any? = null,
    val se: Int? = 1,
    val ep: Int? = 1,
    val count: Int? = 0,
    val captions: List<CaptionItem> = emptyList()
) {
    constructor(
        subjectId: String?,
        se: Int? = 1,
        ep: Int? = 1,
        count: Int? = 0,
        captions: List<CaptionItem> = emptyList()
    ) : this(
        rawSubjectId = subjectId,
        se = se,
        ep = ep,
        count = count,
        captions = captions
    )

    val subjectId: String?
        get() = rawSubjectId?.toString()
}

data class CaptionItem(
    val id: String? = null,
    val lan: String? = null,
    val lanName: String? = null,
    val url: String = "",
    val size: String? = null,
    val delay: Long? = 0
)
