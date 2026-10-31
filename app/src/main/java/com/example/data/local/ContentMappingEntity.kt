package com.example.data.local

import androidx.room.Entity
import androidx.room.Index
import com.example.data.model.ContentMatch
import com.example.data.model.MatchStatus

/**
 * Room persistence entity for caching TMDB to MovieBox content matches.
 * Compound primary key (tmdbId, mediaType) allows distinct mappings for same-ID items across movies and TV.
 */
@Entity(
    tableName = "content_mappings",
    primaryKeys = ["tmdbId", "mediaType"],
    indices = [Index(value = ["movieboxSubjectId"])]
)
data class ContentMappingEntity(
    val tmdbId: Long,
    val mediaType: String,
    val tmdbTitle: String,
    val tmdbOriginalTitle: String,
    val releaseYear: String?,
    val movieboxSubjectId: String?,
    val movieboxDetailPath: String?,
    val matchStatus: String,
    val matchConfidence: Float,
    val matchedAt: Long,
    val lastCheckedAt: Long,
    val expiresAt: Long,
    val hasAvailableStreams: Boolean = false,
    val availabilityCheckedAt: Long = 0L,
    val availabilityExpiresAt: Long = 0L
)

/**
 * Converts a database entity into the domain [ContentMatch] model.
 */
fun ContentMappingEntity.toContentMatch(): ContentMatch {
    val status = try {
        MatchStatus.valueOf(matchStatus)
    } catch (_: Exception) {
        MatchStatus.NEEDS_REVIEW
    }
    return ContentMatch(
        tmdbId = tmdbId,
        tmdbMediaType = mediaType,
        tmdbTitle = tmdbTitle,
        tmdbOriginalTitle = tmdbOriginalTitle,
        releaseYear = releaseYear,
        movieboxSubjectId = movieboxSubjectId,
        movieboxDetailPath = movieboxDetailPath,
        matchStatus = status,
        matchConfidence = matchConfidence,
        matchedAt = matchedAt,
        lastCheckedAt = lastCheckedAt,
        expiresAt = expiresAt,
        hasAvailableStreams = hasAvailableStreams,
        availabilityCheckedAt = availabilityCheckedAt,
        availabilityExpiresAt = availabilityExpiresAt
    )
}

/**
 * Converts a domain [ContentMatch] model into a Room database entity.
 */
fun ContentMatch.toEntity(): ContentMappingEntity {
    return ContentMappingEntity(
        tmdbId = tmdbId,
        mediaType = tmdbMediaType,
        tmdbTitle = tmdbTitle,
        tmdbOriginalTitle = tmdbOriginalTitle,
        releaseYear = releaseYear,
        movieboxSubjectId = movieboxSubjectId,
        movieboxDetailPath = movieboxDetailPath,
        matchStatus = matchStatus.name,
        matchConfidence = matchConfidence,
        matchedAt = matchedAt,
        lastCheckedAt = lastCheckedAt,
        expiresAt = expiresAt,
        hasAvailableStreams = hasAvailableStreams,
        availabilityCheckedAt = availabilityCheckedAt,
        availabilityExpiresAt = availabilityExpiresAt
    )
}
