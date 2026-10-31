package com.example.data.repository

import com.example.data.local.AppDatabase
import com.example.data.local.toContentMatch
import com.example.data.local.toEntity
import com.example.data.model.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import java.text.Normalizer
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs

/**
 * Outcome of a stream resource availability probe.
 * Distinguishes genuine resource absence from network / server errors.
 */
sealed class StreamProbeResult {
    object Available : StreamProbeResult()
    object Unavailable : StreamProbeResult()
    data class NetworkError(val error: Throwable) : StreamProbeResult()
}

/**
 * Zippy Content Matching Engine:
 * Connects TMDB catalogue items to genuine MovieBox streaming provider records
 * using a conservative, multi-factor matching procedure.
 *
 * Matching Factors:
 * 1. Exact normalized title & original title match for automatic verification.
 * 2. Media type (Movie vs TV Series) - strict match.
 * 3. Release year - exact match required for automatic verification.
 *    1-year variance yields NEEDS_REVIEW. Missing year yields NEEDS_REVIEW.
 * 4. Candidate subject ID must match detail endpoint subject ID.
 * 5. Detail endpoint release year must not contradict TMDB release year.
 * 6. Provider Stream Availability verification in all formats (sources, HLS, DASH).
 *
 * Caching & Lifecycles:
 * - Identity Verification: 30 days retention before re-evaluation.
 * - Provider Stream Availability: 24 hours refresh.
 * - NOT_FOUND: 24 hours retry (only on genuine empty search results, never on API failure).
 * - NEEDS_REVIEW: 7 days retention.
 *
 * Concurrency & Lifecycle:
 * - Max 3 concurrent requests to MovieBox API via Semaphore(3).
 * - In-flight request deduplication via shared Deferred<ContentMatch>.
 * - Cancellable via [cancel] tied to ViewModel lifecycle.
 */
class ContentMatchingEngine(
    private val movieRepository: MovieRepository,
    private val database: AppDatabase,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
) {
    private val dao = database.watchDao()
    private val inFlightMutex = Mutex()
    private val inFlightJobs = ConcurrentHashMap<String, Deferred<ContentMatch>>()
    private val refreshBackoffMap = ConcurrentHashMap<String, Long>()

    private val registrationLock = Any()

    @Volatile
    private var isCancelled = false

    /** Number of active in-flight matching operations (used for testing and diagnostics) */
    internal val inFlightJobCount: Int
        get() = inFlightJobs.size

    /** Throttles simultaneous MovieBox matching requests to maximum 3 concurrent calls */
    private val matchSemaphore = Semaphore(3)

    /**
     * Cancels all running matching coroutines, clears in-flight jobs,
     * and sets isCancelled to reject subsequent execution.
     * Uses registrationLock to guarantee no newly created Deferred can be inserted
     * after cancellation has cleared the inFlightJobs map.
     * Called when the owning ViewModel is cleared.
     */
    fun cancel() {
        isCancelled = true
        scope.cancel()
        synchronized(registrationLock) {
            inFlightJobs.values.forEach { it.cancel() }
            inFlightJobs.clear()
            refreshBackoffMap.clear()
        }
    }

    companion object {
        const val RETRY_BACKOFF_MS = 30_000L // 30 seconds transient retry backoff
        /**
         * Normalizes titles for robust comparison:
         * - Lowercases text
         * - Decomposes diacritics / accents (e.g. "Amélie" -> "amelie")
         * - Converts Roman numerals at word boundaries ("Part II" -> "Part 2")
         * - Strips packaging tags like "(Movie)", "[Hindi]", "4K", "1080p"
         * - Strips non-alphanumeric characters while preserving word separations
         */
        fun normalizeTitle(raw: String?): String {
            if (raw.isNullOrBlank()) return ""

            // Decompose accented characters (e.g., é -> e)
            val decomposed = Normalizer.normalize(raw, Normalizer.Form.NFD)
                .replace("\\p{InCombiningDiacriticalMarks}+".toRegex(), "")
                .lowercase(Locale.ROOT)

            // Replace Roman numerals at word boundaries
            val romanReplaced = decomposed
                .replace("\\bviii\\b".toRegex(), "8")
                .replace("\\bvii\\b".toRegex(), "7")
                .replace("\\bvi\\b".toRegex(), "6")
                .replace("\\biv\\b".toRegex(), "4")
                .replace("\\bix\\b".toRegex(), "9")
                .replace("\\biii\\b".toRegex(), "3")
                .replace("\\bii\\b".toRegex(), "2")
                .replace("\\bv\\b".toRegex(), "5")
                .replace("\\bx\\b".toRegex(), "10")

            // Remove noise tags like (Movie), [Hindi], 4K, 1080p, Season 1, etc.
            val noiseCleaned = romanReplaced
                .replace("\\((movie|dubbed|sub|subbed|hindi|dual audio)\\)".toRegex(), "")
                .replace("\\[(hindi|english|4k|1080p|720p)\\]".toRegex(), "")

            // Strip all non-alphanumeric characters except single whitespace
            return noiseCleaned
                .replace("[^a-z0-9\\s]".toRegex(), " ")
                .replace("\\s+".toRegex(), " ")
                .trim()
        }

        /**
         * Calculates similarity between two titles using a combination of:
         * 1. Word token Jaccard similarity (order-independent overlap)
         * 2. Bigram Dice coefficient (typo tolerance, spelling variants)
         */
        fun calculateSimilarity(s1: String, s2: String): Float {
            val n1 = normalizeTitle(s1)
            val n2 = normalizeTitle(s2)

            if (n1 == n2 && n1.isNotEmpty()) return 1.0f
            if (n1.isEmpty() || n2.isEmpty()) return 0.0f

            // 1. Token overlap (Jaccard similarity)
            val tokens1 = n1.split(" ").filter { it.isNotBlank() }.toSet()
            val tokens2 = n2.split(" ").filter { it.isNotBlank() }.toSet()
            val intersection = tokens1.intersect(tokens2).size
            val union = tokens1.union(tokens2).size
            val tokenSim = if (union > 0) intersection.toFloat() / union.toFloat() else 0f

            // 2. Bigram Dice coefficient for typo and formatting tolerance
            val bigrams1 = (0 until n1.length - 1).map { n1.substring(it, it + 2) }.toSet()
            val bigrams2 = (0 until n2.length - 1).map { n2.substring(it, it + 2) }.toSet()
            val bigramIntersection = bigrams1.intersect(bigrams2).size
            val bigramTotal = bigrams1.size + bigrams2.size
            val bigramSim = if (bigramTotal > 0) (2f * bigramIntersection) / bigramTotal else 0f

            return maxOf(tokenSim, bigramSim)
        }

        /**
         * Multi-factor scoring of a MovieBox candidate against a TMDB item:
         * - Media type must match exactly (Movie vs TV Series).
         * - Title similarity compared against both display title and original title.
         * - Release year evaluated (same year = 1.0, 1-year diff = 0.60, >1 year diff = 0.0, missing = 0.25).
         * - Language and country checked if present.
         */
        fun scoreCandidate(
            tmdbTitle: String,
            tmdbOriginalTitle: String,
            tmdbYear: String?,
            tmdbIsTv: Boolean,
            candidate: MovieItem,
            tmdbLanguage: String? = null,
            tmdbCountry: String? = null
        ): Float {
            // 1. Media type verification: strict check
            val candIsTv = candidate.isTvSeries
            if (tmdbIsTv != candIsTv) {
                return 0.0f // Mismatch between movie and TV series -> reject!
            }

            // 2. Title similarity
            val candTitle = candidate.safeName
            val simPrimary = calculateSimilarity(tmdbTitle, candTitle)
            val simOriginal = if (tmdbOriginalTitle.isNotBlank() && tmdbOriginalTitle != tmdbTitle) {
                calculateSimilarity(tmdbOriginalTitle, candTitle)
            } else 0f
            val effectiveTitleSim = maxOf(simPrimary, simOriginal)

            if (effectiveTitleSim < 0.55f) return 0.0f // Title is too dissimilar -> reject

            // 3. Year verification
            val tmdbYearInt = tmdbYear?.toIntOrNull()
            val candYearInt = candidate.year?.toIntOrNull()
            val yearScore: Float = when {
                tmdbYearInt != null && candYearInt != null -> {
                    val diff = abs(tmdbYearInt - candYearInt)
                    when (diff) {
                        0 -> 1.0f
                        1 -> 0.60f // 1-year diff permitted for candidate selection, but constrained
                        else -> 0.0f // >1 year diff -> different film or remake -> reject!
                    }
                }
                else -> 0.25f // Missing release year is NOT strong positive evidence
            }

            if (yearScore == 0.0f) return 0.0f

            // 4. Language / Country bonus / penalty if available
            var metaAdjustment = 0.0f
            if (!candidate.language.isNullOrBlank() && !tmdbLanguage.isNullOrBlank()) {
                if (candidate.language.equals(tmdbLanguage, ignoreCase = true)) {
                    metaAdjustment += 0.05f
                } else {
                    metaAdjustment -= 0.20f // Language mismatch penalty
                }
            }
            if (!candidate.country.isNullOrBlank() && !tmdbCountry.isNullOrBlank()) {
                if (candidate.country.equals(tmdbCountry, ignoreCase = true)) {
                    metaAdjustment += 0.05f
                } else {
                    metaAdjustment -= 0.15f
                }
            }

            val baseScore = (effectiveTitleSim * 0.70f) + (yearScore * 0.30f)
            return (baseScore + metaAdjustment).coerceIn(0.0f, 1.0f)
        }
    }

    /**
     * Resolves the MovieBox provider mapping for a given TMDB record.
     * Concurrent calls for the same TMDB item share one in-flight Deferred matching operation.
     */
    suspend fun getMapping(
        tmdbId: Long,
        mediaType: String,
        title: String,
        originalTitle: String = title,
        releaseYear: String? = null,
        language: String? = null,
        country: String? = null,
        forceRefresh: Boolean = false
    ): ContentMatch = withContext(Dispatchers.IO) {
        if (isCancelled) {
            throw CancellationException("ContentMatchingEngine has been cancelled")
        }
        val cacheKey = "${mediaType.lowercase(Locale.ROOT)}_$tmdbId"

        // 1. Check local Room database cache
        if (!forceRefresh) {
            try {
                val cached = dao.getMapping(tmdbId, mediaType.lowercase(Locale.ROOT))
                if (cached != null) {
                    val mapping = cached.toContentMatch()
                    val now = System.currentTimeMillis()

                    if (mapping.matchStatus == MatchStatus.VERIFIED) {
                        if (!mapping.isIdentityExpired) {
                            if (!mapping.isAvailabilityExpired) {
                                return@withContext mapping
                            }
                            // Availability is expired! Check transient retry backoff
                            val backoffUntil = refreshBackoffMap[cacheKey] ?: 0L
                            if (now < backoffUntil) {
                                return@withContext mapping
                            }
                            // Route through shared Deferred and Semaphore for guarded refresh
                            return@withContext executeGuardedRefresh(mapping, cacheKey)
                        }
                    } else if (mapping.matchStatus == MatchStatus.NOT_FOUND || mapping.matchStatus == MatchStatus.NEEDS_REVIEW) {
                        if (now <= mapping.expiresAt) {
                            return@withContext mapping
                        }
                    }
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
            }
        }

        // 2. Thread-safe in-flight job deduplication using Deferred
        val deferred = inFlightMutex.withLock {
            if (isCancelled) {
                throw CancellationException("ContentMatchingEngine has been cancelled")
            }
            inFlightJobs[cacheKey] ?: run {
                val newDeferred = scope.async {
                    try {
                        matchSemaphore.withPermit {
                            executeMatchingProcedure(
                                tmdbId = tmdbId,
                                mediaType = mediaType,
                                title = title,
                                originalTitle = originalTitle,
                                releaseYear = releaseYear,
                                language = language,
                                country = country
                            )
                        }
                    } finally {
                        withContext(NonCancellable) {
                            inFlightMutex.withLock {
                                synchronized(registrationLock) {
                                    inFlightJobs.remove(cacheKey)
                                }
                            }
                        }
                    }
                }
                synchronized(registrationLock) {
                    if (isCancelled) {
                        newDeferred.cancel()
                        throw CancellationException("ContentMatchingEngine has been cancelled")
                    }
                    inFlightJobs[cacheKey] = newDeferred
                }
                newDeferred
            }
        }

        deferred.await()
    }

    /**
     * Public refresh availability method that routes through the exact same concurrency limit
     * (Semaphore(3)) and shared Deferred deduplication as initial matching.
     * Prevents duplicate simultaneous network probes for the same title.
     */
    suspend fun refreshAvailability(mapping: ContentMatch): ContentMatch {
        val cacheKey = "${mapping.tmdbMediaType.lowercase(Locale.ROOT)}_${mapping.tmdbId}"
        return executeGuardedRefresh(mapping, cacheKey)
    }

    /**
     * Executes availability refresh with concurrency control and deduplication.
     */
    private suspend fun executeGuardedRefresh(
        mapping: ContentMatch,
        cacheKey: String
    ): ContentMatch = withContext(Dispatchers.IO) {
        if (isCancelled) {
            throw CancellationException("ContentMatchingEngine has been cancelled")
        }
        val deferred = inFlightMutex.withLock {
            if (isCancelled) {
                throw CancellationException("ContentMatchingEngine has been cancelled")
            }
            inFlightJobs[cacheKey] ?: run {
                val newDeferred = scope.async {
                    try {
                        matchSemaphore.withPermit {
                            val now = System.currentTimeMillis()
                            val backoffUntil = refreshBackoffMap[cacheKey] ?: 0L
                            if (now < backoffUntil) {
                                return@withPermit mapping
                            }
                            performAvailabilityRefresh(mapping, cacheKey)
                        }
                    } finally {
                        withContext(NonCancellable) {
                            inFlightMutex.withLock {
                                synchronized(registrationLock) {
                                    inFlightJobs.remove(cacheKey)
                                }
                            }
                        }
                    }
                }
                synchronized(registrationLock) {
                    if (isCancelled) {
                        newDeferred.cancel()
                        throw CancellationException("ContentMatchingEngine has been cancelled")
                    }
                    inFlightJobs[cacheKey] = newDeferred
                }
                newDeferred
            }
        }
        deferred.await()
    }

    private suspend fun performAvailabilityRefresh(
        mapping: ContentMatch,
        cacheKey: String
    ): ContentMatch {
        val subjectId = mapping.movieboxSubjectId ?: return mapping
        val slug = mapping.movieboxDetailPath ?: return mapping
        val isTv = mapping.tmdbMediaType.equals("tv", ignoreCase = true)

        val probeResult = probeStreamAvailability(subjectId, slug, isTv)
        val now = System.currentTimeMillis()

        return when (probeResult) {
            is StreamProbeResult.Available -> {
                refreshBackoffMap.remove(cacheKey)
                val availabilityExpiresAt = now + ContentMatch.REFRESH_AVAILABILITY_MS
                val updated = mapping.copy(
                    hasAvailableStreams = true,
                    availabilityCheckedAt = now,
                    availabilityExpiresAt = availabilityExpiresAt,
                    lastCheckedAt = now
                )
                try {
                    dao.updateAvailability(
                        tmdbId = mapping.tmdbId,
                        mediaType = mapping.tmdbMediaType,
                        hasStreams = true,
                        checkedAt = now,
                        expiresAt = availabilityExpiresAt
                    )
                } catch (_: Exception) {}
                updated
            }
            is StreamProbeResult.Unavailable -> {
                refreshBackoffMap.remove(cacheKey)
                val availabilityExpiresAt = now + ContentMatch.REFRESH_AVAILABILITY_MS
                val updated = mapping.copy(
                    hasAvailableStreams = false,
                    availabilityCheckedAt = now,
                    availabilityExpiresAt = availabilityExpiresAt,
                    lastCheckedAt = now
                )
                try {
                    dao.updateAvailability(
                        tmdbId = mapping.tmdbId,
                        mediaType = mapping.tmdbMediaType,
                        hasStreams = false,
                        checkedAt = now,
                        expiresAt = availabilityExpiresAt
                    )
                } catch (_: Exception) {}
                updated
            }
            is StreamProbeResult.NetworkError -> {
                // Transient network error:
                // 1. Set short retry backoff to avoid hammering MovieBox on subsequent calls
                refreshBackoffMap[cacheKey] = now + RETRY_BACKOFF_MS
                // 2. Preserve verified identity mapping
                // 3. A failed stream request must NOT automatically be stored as confirmed unavailable for 24 hours.
                mapping
            }
        }
    }

    /**
     * Executes the comprehensive matching procedure with strict identity and availability gates.
     */
    private suspend fun executeMatchingProcedure(
        tmdbId: Long,
        mediaType: String,
        title: String,
        originalTitle: String,
        releaseYear: String?,
        language: String?,
        country: String?
    ): ContentMatch {
        val isTv = mediaType.equals("tv", ignoreCase = true)
        val now = System.currentTimeMillis()

        // 1. Search MovieBox using planned queries: display title, original title (if distinct), and title+year
        val plannedQueries = mutableListOf<String>()
        plannedQueries.add(title)
        if (originalTitle.isNotBlank() && !originalTitle.equals(title, ignoreCase = true)) {
            plannedQueries.add(originalTitle)
        }
        if (!releaseYear.isNullOrBlank()) {
            plannedQueries.add("$title $releaseYear")
        }

        var anySearchFailed = false
        val allCandidates = mutableListOf<MovieItem>()

        for (q in plannedQueries) {
            val res = movieRepository.search(q, page = 1)
            if (res.isFailure) {
                anySearchFailed = true
            } else {
                val items = res.getOrNull()?.items.orEmpty()
                allCandidates.addAll(items)
            }
        }

        // FIX 1: If one or more planned searches fail, do NOT classify an inconclusive result as NOT_FOUND,
        // and do NOT allow a partial search failure to create an automatically VERIFIED mapping
        // when the failed searches could affect candidate discovery or ambiguity assessment.
        if (anySearchFailed) {
            return ContentMatch(
                tmdbId = tmdbId,
                tmdbMediaType = mediaType,
                tmdbTitle = title,
                tmdbOriginalTitle = originalTitle,
                releaseYear = releaseYear,
                matchStatus = MatchStatus.CHECKING,
                matchConfidence = 0.0f,
                matchedAt = now,
                lastCheckedAt = now,
                expiresAt = now // Temporary state: allow immediate retry when connectivity returns. Do NOT save to Room.
            )
        }

        // All planned searches succeeded!
        // Filter and deduplicate candidates that refer to the same actual MovieBox subject ID
        val distinctCandidates = allCandidates
            .filter { MovieBoxPlaybackGuard.isValidMovieBoxSubjectId(it.subjectId) }
            .distinctBy { it.subjectId }

        // Case A: All planned searches succeeded and collectively returned zero candidates
        // -> Cache as NOT_FOUND for 24 hours in Room
        if (distinctCandidates.isEmpty()) {
            val notFound = ContentMatch(
                tmdbId = tmdbId,
                tmdbMediaType = mediaType,
                tmdbTitle = title,
                tmdbOriginalTitle = originalTitle,
                releaseYear = releaseYear,
                matchStatus = MatchStatus.NOT_FOUND,
                matchConfidence = 0.0f,
                matchedAt = now,
                lastCheckedAt = now,
                expiresAt = now + ContentMatch.EXPIRY_NOT_FOUND_MS
            )
            dao.upsertMapping(notFound.toEntity())
            return notFound
        }

        // 3. Score all distinct candidates for discovery
        val scoredCandidates = distinctCandidates.map { candidate ->
            val score = scoreCandidate(
                tmdbTitle = title,
                tmdbOriginalTitle = originalTitle,
                tmdbYear = releaseYear,
                tmdbIsTv = isTv,
                candidate = candidate,
                tmdbLanguage = language,
                tmdbCountry = country
            )
            candidate to score
        }.filter { it.second >= 0.55f }
         .sortedByDescending { it.second }

        val topCandidate = scoredCandidates.firstOrNull()

        // Case B: API success with results, but no sufficiently strong candidate
        if (topCandidate == null) {
            val notFound = ContentMatch(
                tmdbId = tmdbId,
                tmdbMediaType = mediaType,
                tmdbTitle = title,
                tmdbOriginalTitle = originalTitle,
                releaseYear = releaseYear,
                matchStatus = MatchStatus.NOT_FOUND,
                matchConfidence = 0.0f,
                matchedAt = now,
                lastCheckedAt = now,
                expiresAt = now + ContentMatch.EXPIRY_NOT_FOUND_MS
            )
            dao.upsertMapping(notFound.toEntity())
            return notFound
        }

        val bestItem = topCandidate.first
        val bestScore = topCandidate.second

        // 4. Ambiguity Check: If multiple distinct provider records have equally strong evidence, return NEEDS_REVIEW.
        // Never select arbitrarily among candidates with close scores (margin < 0.06).
        if (scoredCandidates.size >= 2) {
            val secondScore = scoredCandidates[1].second
            if (abs(bestScore - secondScore) < 0.06f) {
                val needsReview = ContentMatch(
                    tmdbId = tmdbId,
                    tmdbMediaType = mediaType,
                    tmdbTitle = title,
                    tmdbOriginalTitle = originalTitle,
                    releaseYear = releaseYear,
                    movieboxSubjectId = bestItem.subjectId,
                    movieboxDetailPath = bestItem.safeSlug,
                    matchStatus = MatchStatus.NEEDS_REVIEW,
                    matchConfidence = bestScore,
                    matchedAt = now,
                    lastCheckedAt = now,
                    expiresAt = now + ContentMatch.EXPIRY_NEEDS_REVIEW_MS,
                    hasAvailableStreams = false
                )
                dao.upsertMapping(needsReview.toEntity())
                return needsReview
            }
        }

        // 5. Stricter Automatic Title Matching Check:
        // Automatic VERIFIED requires exact normalized title match.
        // Fuzzy title similarity is useful for discovering candidates, but cannot automatically establish identity.
        val normTmdbTitle = normalizeTitle(title)
        val normTmdbOrigTitle = normalizeTitle(originalTitle)
        val normCandTitle = normalizeTitle(bestItem.safeName)
        val isExactCandTitle = normCandTitle.isNotEmpty() &&
            (normCandTitle == normTmdbTitle || (normTmdbOrigTitle.isNotEmpty() && normCandTitle == normTmdbOrigTitle))

        if (!isExactCandTitle) {
            val needsReview = ContentMatch(
                tmdbId = tmdbId,
                tmdbMediaType = mediaType,
                tmdbTitle = title,
                tmdbOriginalTitle = originalTitle,
                releaseYear = releaseYear,
                movieboxSubjectId = bestItem.subjectId,
                movieboxDetailPath = bestItem.safeSlug,
                matchStatus = MatchStatus.NEEDS_REVIEW,
                matchConfidence = bestScore,
                matchedAt = now,
                lastCheckedAt = now,
                expiresAt = now + ContentMatch.EXPIRY_NEEDS_REVIEW_MS,
                hasAvailableStreams = false
            )
            dao.upsertMapping(needsReview.toEntity())
            return needsReview
        }

        // 6. Release Year Verification on Candidate:
        // Exact release year required for automatic verification.
        // One-year release differences must remain NEEDS_REVIEW (language/country alone are insufficient).
        // Missing release year must yield NEEDS_REVIEW.
        val tmdbYearInt = releaseYear?.toIntOrNull()
        val candYearInt = bestItem.year?.toIntOrNull()

        if (tmdbYearInt == null || candYearInt == null) {
            val needsReview = ContentMatch(
                tmdbId = tmdbId,
                tmdbMediaType = mediaType,
                tmdbTitle = title,
                tmdbOriginalTitle = originalTitle,
                releaseYear = releaseYear,
                movieboxSubjectId = bestItem.subjectId,
                movieboxDetailPath = bestItem.safeSlug,
                matchStatus = MatchStatus.NEEDS_REVIEW,
                matchConfidence = bestScore,
                matchedAt = now,
                lastCheckedAt = now,
                expiresAt = now + ContentMatch.EXPIRY_NEEDS_REVIEW_MS,
                hasAvailableStreams = false
            )
            dao.upsertMapping(needsReview.toEntity())
            return needsReview
        }

        val candYearDiff = abs(tmdbYearInt - candYearInt)
        if (candYearDiff == 1) {
            val needsReview = ContentMatch(
                tmdbId = tmdbId,
                tmdbMediaType = mediaType,
                tmdbTitle = title,
                tmdbOriginalTitle = originalTitle,
                releaseYear = releaseYear,
                movieboxSubjectId = bestItem.subjectId,
                movieboxDetailPath = bestItem.safeSlug,
                matchStatus = MatchStatus.NEEDS_REVIEW,
                matchConfidence = bestScore,
                matchedAt = now,
                lastCheckedAt = now,
                expiresAt = now + ContentMatch.EXPIRY_NEEDS_REVIEW_MS,
                hasAvailableStreams = false
            )
            dao.upsertMapping(needsReview.toEntity())
            return needsReview
        } else if (candYearDiff > 1) {
            val notFound = ContentMatch(
                tmdbId = tmdbId,
                tmdbMediaType = mediaType,
                tmdbTitle = title,
                tmdbOriginalTitle = originalTitle,
                releaseYear = releaseYear,
                matchStatus = MatchStatus.NOT_FOUND,
                matchConfidence = 0.0f,
                matchedAt = now,
                lastCheckedAt = now,
                expiresAt = now + ContentMatch.EXPIRY_NOT_FOUND_MS
            )
            dao.upsertMapping(notFound.toEntity())
            return notFound
        }

        // 7. Verify Candidate via MovieBox Detail API
        val detailRes = movieRepository.getDetail(bestItem.safeSlug)
        if (detailRes.isFailure) {
            // Network timeout / HTTP error on detail -> temporary state, do not cache as NOT_FOUND
            return ContentMatch(
                tmdbId = tmdbId,
                tmdbMediaType = mediaType,
                tmdbTitle = title,
                tmdbOriginalTitle = originalTitle,
                releaseYear = releaseYear,
                matchStatus = MatchStatus.CHECKING,
                matchConfidence = 0.0f,
                matchedAt = now,
                lastCheckedAt = now,
                expiresAt = now
            )
        }

        val detailResponse = detailRes.getOrNull()
        val subject = detailResponse?.data?.subject
        if (subject == null) {
            val needsReview = ContentMatch(
                tmdbId = tmdbId,
                tmdbMediaType = mediaType,
                tmdbTitle = title,
                tmdbOriginalTitle = originalTitle,
                releaseYear = releaseYear,
                movieboxSubjectId = null,
                movieboxDetailPath = null,
                matchStatus = MatchStatus.NEEDS_REVIEW,
                matchConfidence = bestScore,
                matchedAt = now,
                lastCheckedAt = now,
                expiresAt = now + ContentMatch.EXPIRY_NEEDS_REVIEW_MS,
                hasAvailableStreams = false
            )
            dao.upsertMapping(needsReview.toEntity())
            return needsReview
        }

        // 8. TASK 1: Verify Search Candidate Subject ID Against Detail Subject ID
        // Both values must be present, valid, and exactly equal.
        // If they differ: do not mark VERIFIED, do not combine detail ID with slug, mark NEEDS_REVIEW.
        val searchSubjectId = bestItem.subjectId
        val detailSubjectId = subject.subjectId

        if (!MovieBoxPlaybackGuard.isValidMovieBoxSubjectId(searchSubjectId) ||
            !MovieBoxPlaybackGuard.isValidMovieBoxSubjectId(detailSubjectId) ||
            searchSubjectId != detailSubjectId
        ) {
            val needsReview = ContentMatch(
                tmdbId = tmdbId,
                tmdbMediaType = mediaType,
                tmdbTitle = title,
                tmdbOriginalTitle = originalTitle,
                releaseYear = releaseYear,
                movieboxSubjectId = null, // Do NOT combine mismatched detail ID with slug!
                movieboxDetailPath = null,
                matchStatus = MatchStatus.NEEDS_REVIEW,
                matchConfidence = bestScore,
                matchedAt = now,
                lastCheckedAt = now,
                expiresAt = now + ContentMatch.EXPIRY_NEEDS_REVIEW_MS,
                hasAvailableStreams = false
            )
            dao.upsertMapping(needsReview.toEntity())
            return needsReview
        }

        // 9. Verify Detail Title against TMDB title (must be exact normalized match for automatic VERIFIED)
        val normDetailTitle = normalizeTitle(subject.title)
        val isExactDetailTitle = normDetailTitle.isNotEmpty() &&
            (normDetailTitle == normTmdbTitle || (normTmdbOrigTitle.isNotEmpty() && normDetailTitle == normTmdbOrigTitle))

        if (!isExactDetailTitle) {
            val needsReview = ContentMatch(
                tmdbId = tmdbId,
                tmdbMediaType = mediaType,
                tmdbTitle = title,
                tmdbOriginalTitle = originalTitle,
                releaseYear = releaseYear,
                movieboxSubjectId = searchSubjectId,
                movieboxDetailPath = bestItem.safeSlug,
                matchStatus = MatchStatus.NEEDS_REVIEW,
                matchConfidence = bestScore,
                matchedAt = now,
                lastCheckedAt = now,
                expiresAt = now + ContentMatch.EXPIRY_NEEDS_REVIEW_MS,
                hasAvailableStreams = false
            )
            dao.upsertMapping(needsReview.toEntity())
            return needsReview
        }

        // 10. Verify Media Type from Detail Endpoint
        val detailIsTv = subject.subjectType == 2 || subject.isSeries ||
                detailResponse.data?.resource?.seasons.orEmpty().any { it.se > 0 }
        if (isTv != detailIsTv) {
            val needsReview = ContentMatch(
                tmdbId = tmdbId,
                tmdbMediaType = mediaType,
                tmdbTitle = title,
                tmdbOriginalTitle = originalTitle,
                releaseYear = releaseYear,
                movieboxSubjectId = searchSubjectId,
                movieboxDetailPath = bestItem.safeSlug,
                matchStatus = MatchStatus.NEEDS_REVIEW,
                matchConfidence = bestScore,
                matchedAt = now,
                lastCheckedAt = now,
                expiresAt = now + ContentMatch.EXPIRY_NEEDS_REVIEW_MS,
                hasAvailableStreams = false
            )
            dao.upsertMapping(needsReview.toEntity())
            return needsReview
        }

        // 11. TASK 5: Verify Release Year Using Detail Data
        // Detail release year must not be missing and must not contradict TMDB release year.
        val detailYearInt = subject.releaseDate?.take(4)?.toIntOrNull()
        if (detailYearInt == null || detailYearInt != tmdbYearInt) {
            val needsReview = ContentMatch(
                tmdbId = tmdbId,
                tmdbMediaType = mediaType,
                tmdbTitle = title,
                tmdbOriginalTitle = originalTitle,
                releaseYear = releaseYear,
                movieboxSubjectId = searchSubjectId,
                movieboxDetailPath = bestItem.safeSlug,
                matchStatus = MatchStatus.NEEDS_REVIEW,
                matchConfidence = bestScore,
                matchedAt = now,
                lastCheckedAt = now,
                expiresAt = now + ContentMatch.EXPIRY_NEEDS_REVIEW_MS,
                hasAvailableStreams = false
            )
            dao.upsertMapping(needsReview.toEntity())
            return needsReview
        }

        // 12. Identity Verification Passed! Probe Provider Stream Availability (All formats: sources, HLS, DASH)
        val probeResult = probeStreamAvailability(searchSubjectId, bestItem.safeSlug, isTv)
        val matchCacheKey = "${mediaType.lowercase(Locale.ROOT)}_$tmdbId"
        val (hasStreams, availabilityCheckedAt, availabilityExpiresAt) = when (probeResult) {
            is StreamProbeResult.Available -> {
                refreshBackoffMap.remove(matchCacheKey)
                Triple(true, now, now + ContentMatch.REFRESH_AVAILABILITY_MS)
            }
            is StreamProbeResult.Unavailable -> {
                refreshBackoffMap.remove(matchCacheKey)
                Triple(false, now, now + ContentMatch.REFRESH_AVAILABILITY_MS)
            }
            is StreamProbeResult.NetworkError -> {
                // Network error: do not store as confirmed unavailable for 24 hours.
                refreshBackoffMap[matchCacheKey] = now + RETRY_BACKOFF_MS
                Triple(false, 0L, now)
            }
        }

        val finalMapping = ContentMatch(
            tmdbId = tmdbId,
            tmdbMediaType = mediaType,
            tmdbTitle = title,
            tmdbOriginalTitle = originalTitle,
            releaseYear = releaseYear,
            movieboxSubjectId = searchSubjectId,
            movieboxDetailPath = bestItem.safeSlug,
            matchStatus = MatchStatus.VERIFIED,
            matchConfidence = bestScore, // Store actual calculated score without artificial boosting
            matchedAt = now,
            lastCheckedAt = now,
            expiresAt = now + ContentMatch.EXPIRY_IDENTITY_MS,
            hasAvailableStreams = hasStreams,
            availabilityCheckedAt = availabilityCheckedAt,
            availabilityExpiresAt = availabilityExpiresAt
        )

        dao.upsertMapping(finalMapping.toEntity())
        return finalMapping
    }

    /**
     * Performs a lightweight stream resource availability check across all supported formats:
     * MP4 sources, HLS (.m3u8), and DASH (.mpd).
     * Uses season=0, episode=0 for movies; season=1, episode=1 for TV series.
     * Does not store or persist temporary stream URLs.
     */
    suspend fun probeStreamAvailability(subjectId: String, detailPath: String, isTv: Boolean): StreamProbeResult {
        return try {
            val se = if (isTv) 1 else 0
            val ep = if (isTv) 1 else 0
            val res = movieRepository.getStream(subjectId, detailPath, season = se, episode = ep, isSeries = isTv)
            if (res.isFailure) {
                val ex = res.exceptionOrNull() ?: java.io.IOException("Stream probe network error")
                if (ex is CancellationException) throw ex
                StreamProbeResult.NetworkError(ex)
            } else {
                val stream = res.getOrNull()
                if (stream == null) {
                    StreamProbeResult.Unavailable
                } else {
                    if (stream.hasUsableStreams) {
                        StreamProbeResult.Available
                    } else {
                        StreamProbeResult.Unavailable
                    }
                }
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            StreamProbeResult.NetworkError(e)
        }
    }

    /**
     * Convenience method checking whether stream resources are available.
     */
    suspend fun isStreamAvailable(subjectId: String, detailPath: String, isTv: Boolean): Boolean {
        return probeStreamAvailability(subjectId, detailPath, isTv) is StreamProbeResult.Available
    }

    /**
     * Convenience method to execute the matching procedure directly on a [TmdbMovieItem].
     * Implements Steps A and B by extracting media type, release year, language, and country.
     */
    suspend fun matchTmdbItem(
        item: TmdbMovieItem,
        forceRefresh: Boolean = false
    ): ContentMatch {
        return getMapping(
            tmdbId = item.id,
            mediaType = item.safeMediaType,
            title = item.safeTitle,
            originalTitle = item.safeOriginalTitle,
            releaseYear = item.year,
            language = item.originalLanguage,
            country = item.originCountry?.firstOrNull(),
            forceRefresh = forceRefresh
        )
    }

    /**
     * Inspects the local database cache directly without executing network queries.
     */
    suspend fun getCachedMapping(
        tmdbId: Long,
        mediaType: String
    ): ContentMatch? = withContext(Dispatchers.IO) {
        dao.getMapping(tmdbId, mediaType)?.toContentMatch()
    }
}
