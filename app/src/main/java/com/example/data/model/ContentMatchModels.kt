package com.example.data.model

/**
 * Clearly defined status values for TMDB-to-MovieBox content matching.
 * Only [VERIFIED] matches are ever permitted to initiate video streaming.
 */
enum class MatchStatus {
    /**
     * Confirmed, verified identity match on MovieBox.
     * Playback requires valid provider stream availability.
     */
    VERIFIED,

    /**
     * A search or metadata verification is currently in progress.
     * Playback is blocked while checking.
     */
    CHECKING,

    /**
     * Search completed on MovieBox, but no suitable candidate exists.
     * Playback is strictly disabled.
     */
    NOT_FOUND,

    /**
     * Multiple plausible candidates exist or important metadata conflicts
     * (e.g. year/title discrepancy, 1-year variance without corroboration).
     * Playback is strictly disabled until review.
     */
    NEEDS_REVIEW,

    /**
     * A previously verified or cached mapping has expired and requires revalidation.
     * Playback is temporarily blocked until revalidated.
     */
    EXPIRED
}

/**
 * Data model representing the mapping between a TMDB catalogue entry and a genuine
 * MovieBox streaming provider record.
 *
 * TMDB IDs and MovieBox IDs are strictly separated in dedicated fields.
 * Distinguishes between Identity Verification (30-day lifecycle) and
 * Provider Stream Availability (24-hour lifecycle).
 */
data class ContentMatch(
    val tmdbId: Long,
    val tmdbMediaType: String,
    val tmdbTitle: String,
    val tmdbOriginalTitle: String = tmdbTitle,
    val releaseYear: String? = null,
    val movieboxSubjectId: String? = null,
    val movieboxDetailPath: String? = null,
    val matchStatus: MatchStatus = MatchStatus.CHECKING,
    val matchConfidence: Float = 0.0f,
    val matchedAt: Long = System.currentTimeMillis(),
    val lastCheckedAt: Long = System.currentTimeMillis(),
    val expiresAt: Long = System.currentTimeMillis() + EXPIRY_IDENTITY_MS,
    val hasAvailableStreams: Boolean = false,
    val availabilityCheckedAt: Long = 0L,
    val availabilityExpiresAt: Long = 0L
) {
    /**
     * A mapping is only considered verified when status is [MatchStatus.VERIFIED]
     * and both MovieBox subject ID and detail path are non-blank.
     */
    val isVerified: Boolean
        get() = matchStatus == MatchStatus.VERIFIED &&
                !movieboxSubjectId.isNullOrBlank() &&
                !movieboxDetailPath.isNullOrBlank()

    val isChecking: Boolean
        get() = matchStatus == MatchStatus.CHECKING

    val isNotFound: Boolean
        get() = matchStatus == MatchStatus.NOT_FOUND

    val needsReview: Boolean
        get() = matchStatus == MatchStatus.NEEDS_REVIEW

    /** True if identity mapping is expired and needs full re-verification */
    val isIdentityExpired: Boolean
        get() = matchStatus == MatchStatus.EXPIRED || System.currentTimeMillis() > expiresAt

    /** Legacy alias for identity expiration */
    val isExpired: Boolean
        get() = isIdentityExpired

    /** True if 24-hour stream availability cache has expired and needs a refresh probe */
    val isAvailabilityExpired: Boolean
        get() = availabilityCheckedAt == 0L || System.currentTimeMillis() > availabilityExpiresAt

    /**
     * Playback is permitted ONLY if identity is verified, not identity-expired,
     * provider streams are confirmed available, and availability cache is valid.
     */
    val isPlayable: Boolean
        get() = isVerified && !isIdentityExpired && hasAvailableStreams && !isAvailabilityExpired

    companion object {
        /** 30 days retention for confirmed identity mappings */
        const val EXPIRY_IDENTITY_MS = 30L * 24 * 60 * 60 * 1000

        /** Legacy alias */
        const val EXPIRY_VERIFIED_MS = EXPIRY_IDENTITY_MS

        /** 24 hours refresh for checking provider stream availability */
        const val REFRESH_AVAILABILITY_MS = 24L * 60 * 60 * 1000

        /** 24 hours retry for titles not found (new content is added regularly) */
        const val EXPIRY_NOT_FOUND_MS = 24L * 60 * 60 * 1000

        /** 7 days before re-evaluating ambiguous candidates */
        const val EXPIRY_NEEDS_REVIEW_MS = 7L * 24 * 60 * 60 * 1000
    }
}
