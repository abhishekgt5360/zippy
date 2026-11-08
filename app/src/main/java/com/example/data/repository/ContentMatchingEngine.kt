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
                .replace("\\[.*?\\]".toRegex(), "")
                .replace("\\((movie|dubbed|sub|subbed|hindi|tamil|telugu|kannada|malayalam|bengali|marathi|english|dual audio|complete|4k|1080p|720p|hd|uhd)\\)".toRegex(), "")
                .replace("\\((19|20)\\d{2}\\)".toRegex(), "")
                .replace("\\b(s|season)\\s*\\d+(\\s*-\\s*(s|season)?\\s*\\d+)?\\b".toRegex(), "")
                .replace("\\b(part|pt|vol|volume)\\s*\\d+\\b".toRegex(), "")

            // Remove apostrophes so possessives and contractions match consistently (e.g. "Grey's" -> "greys")
            val apostropheCleaned = noiseCleaned.replace("'", "")

            // Strip all non-alphanumeric characters except single whitespace
            return apostropheCleaned
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
            // 1. Media type verification
            val candType = candidate.type
            if (!candType.isNullOrBlank()) {
                val candIsTv = candidate.isTvSeries
                if (tmdbIsTv != candIsTv) {
                    return 0.0f // Explicit type mismatch -> reject!
                }
            } else {
                // If type is omitted by API, check if candidate contains explicit contradictory cues
                if (candidate.isTvSeries && !tmdbIsTv) {
                    return 0.0f // Candidate has season/series markers, but TMDB item is a movie -> reject
                }
                val n = candidate.safeName.lowercase(Locale.ROOT)
                val isExplicitMovie = n.contains(Regex("""\b(movie|film)\b"""))
                if (isExplicitMovie && tmdbIsTv) {
                    return 0.0f // Candidate explicitly named as a movie, but TMDB item is TV -> reject
                }
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
                    when {
                        diff == 0 -> 1.0f
                        diff == 1 -> if (tmdbIsTv) 0.90f else 0.60f
                        tmdbIsTv && candYearInt >= tmdbYearInt -> 0.85f // For TV series, candidate season aired in later year
                        else -> 0.0f // >1 year diff -> different film or remake -> reject!
                    }
                }
                tmdbIsTv -> 0.70f // TV series often lack release year in catalog
                else -> 0.25f // Missing release year is NOT strong positive evidence
            }

            if (yearScore == 0.0f) return 0.0f

            // 4. Language / Country bonus / penalty if available
            var metaAdjustment = 0.0f
            if (!candidate.language.isNullOrBlank() && !tmdbLanguage.isNullOrBlank()) {
                if (matchesLanguage(candidate.language, tmdbLanguage)) {
                    metaAdjustment += 0.05f
                } else {
                    metaAdjustment -= 0.20f // Language mismatch penalty
                }
            }
            if (!candidate.country.isNullOrBlank() && !tmdbCountry.isNullOrBlank()) {
                if (matchesCountry(candidate.country, tmdbCountry)) {
                    metaAdjustment += 0.05f
                } else {
                    metaAdjustment -= 0.15f
                }
            }
            // Title dub-tag penalty: if candidate name contains dub tag (e.g. [Hindi], (Hindi), [Tamil], [Dubbed])
            // and TMDB original language does not match that tag, penalize so original release is prioritized
            val candNameLower = candidate.safeName.lowercase(Locale.ROOT)
            val dubPattern = Regex("""(\[(hindi|tamil|telugu|kannada|malayalam|bengali|marathi|dubbed|dub|dual audio)\]|\((hindi|tamil|telugu|kannada|malayalam|bengali|marathi|dubbed|dub|dual audio)\)|\b(hindi|tamil|telugu|kannada|malayalam|bengali|marathi|dubbed|dual audio)\b)""")
            val isDubbedCand = candNameLower.contains(dubPattern)
            if (isDubbedCand) {
                val matchesLang = !tmdbLanguage.isNullOrBlank() && (
                    candNameLower.contains("[$tmdbLanguage]") ||
                    candNameLower.contains("($tmdbLanguage)") ||
                    matchesLanguage(candNameLower, tmdbLanguage)
                )
                if (!matchesLang) {
                    metaAdjustment -= 0.15f
                }
            }

            val baseScore = (effectiveTitleSim * 0.70f) + (yearScore * 0.30f)
            return (baseScore + metaAdjustment).coerceIn(0.0f, 1.0f)
        }

        fun matchesLanguage(candLang: String?, tmdbLang: String?): Boolean {
            if (candLang.isNullOrBlank() || tmdbLang.isNullOrBlank()) return false
            val c = candLang.trim().lowercase(Locale.ROOT)
            val t = tmdbLang.trim().lowercase(Locale.ROOT)
            if (c == t) return true
            val isoMap = mapOf(
                "en" to "english",
                "hi" to "hindi",
                "es" to "spanish",
                "fr" to "french",
                "de" to "german",
                "it" to "italian",
                "ja" to "japanese",
                "ko" to "korean",
                "zh" to "chinese",
                "ru" to "russian",
                "pt" to "portuguese",
                "ta" to "tamil",
                "te" to "telugu"
            )
            val normC = isoMap[c] ?: c
            val normT = isoMap[t] ?: t
            return normC == normT || normC.startsWith(normT) || normT.startsWith(normC)
        }

        fun matchesCountry(candCountry: String?, tmdbCountry: String?): Boolean {
            if (candCountry.isNullOrBlank() || tmdbCountry.isNullOrBlank()) return false
            val c = candCountry.trim().lowercase(Locale.ROOT)
            val t = tmdbCountry.trim().lowercase(Locale.ROOT)
            if (c == t) return true
            val countryMap = mapOf(
                "us" to "united states",
                "in" to "india",
                "gb" to "united kingdom",
                "uk" to "united kingdom",
                "ca" to "canada",
                "fr" to "france",
                "de" to "germany",
                "jp" to "japan",
                "kr" to "south korea",
                "cn" to "china",
                "au" to "australia"
            )
            val normC = countryMap[c] ?: c
            val normT = countryMap[t] ?: t
            return normC == normT || normC.startsWith(normT) || normT.startsWith(normC)
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

        // 1. Search MovieBox using planned queries: display title, original title (if distinct), unpunctuated title, and title+year
        val plannedQueries = mutableListOf<String>()
        plannedQueries.add(title)
        if (originalTitle.isNotBlank() && !originalTitle.equals(title, ignoreCase = true)) {
            plannedQueries.add(originalTitle)
        }
        val unpunctuatedTitle = title.replace("'", "").replace("&", "and").replace(":", " ").replace("-", " ")
            .replace("\\s+".toRegex(), " ").trim()
        if (unpunctuatedTitle.isNotBlank() && !unpunctuatedTitle.equals(title, ignoreCase = true)) {
            plannedQueries.add(unpunctuatedTitle)
        }
        if (!releaseYear.isNullOrBlank()) {
            plannedQueries.add("$title $releaseYear")
        }

        val searchResults = coroutineScope {
            plannedQueries.map { q ->
                async {
                    android.util.Log.d(
                        "ZippyPlayback",
                        "[MOVIEBOX_SEARCH_START] endpoint='/search', query='$q', tmdbId=$tmdbId, mediaType='$mediaType', page=1"
                    )
                    val res = movieRepository.search(q, page = 1)
                    if (res.isFailure) {
                        android.util.Log.w(
                            "ZippyPlayback",
                            "[MOVIEBOX_SEARCH_FAILURE] endpoint='/search', query='$q', error='${res.exceptionOrNull()?.message}'"
                        )
                    } else {
                        val items = res.getOrNull()?.items.orEmpty()
                        android.util.Log.d(
                            "ZippyPlayback",
                            "[MOVIEBOX_SEARCH_SUCCESS] endpoint='/search', query='$q', resultsCount=${items.size}"
                        )
                    }
                    res
                }
            }.awaitAll()
        }

        var anySearchFailed = false
        val allCandidates = mutableListOf<MovieItem>()

        for (res in searchResults) {
            if (res.isFailure) {
                anySearchFailed = true
            } else {
                allCandidates.addAll(res.getOrNull()?.items.orEmpty())
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
         .sortedWith(
             compareByDescending<Pair<MovieItem, Float>> { it.second }
                 .thenByDescending { pair ->
                     // Prefer non-dubbed releases unless the requested language matches the dub tag
                     val name = pair.first.safeName.lowercase(Locale.ROOT)
                     val isDubTag = name.contains(Regex("""(\[(hindi|tamil|telugu|kannada|malayalam|bengali|marathi|dubbed|dub)\]|\((hindi|tamil|telugu|kannada|malayalam|bengali|marathi|dubbed|dub)\)|\b(hindi|tamil|telugu|kannada|malayalam|bengali|marathi|dubbed)\b)"""))
                     val isTargetDub = !language.isNullOrBlank() && (name.contains("[$language]", ignoreCase = true) || name.contains("($language)", ignoreCase = true))
                     if (!isDubTag || isTargetDub) 1 else 0
                 }
                 .thenByDescending { pair ->
                     if (isTv) {
                         val name = pair.first.safeName.lowercase(Locale.ROOT)
                         when {
                             name.contains("s1-") || name.contains("season 1-") -> 3
                             name.contains("s1") || name.contains("season 1") -> 2
                             else -> 1
                         }
                     } else 0
                 }
         )

        android.util.Log.d(
            "ZippyPlayback",
            "Discovered ${distinctCandidates.size} distinct candidates, ${scoredCandidates.size} passed threshold (>=0.55):"
        )
        scoredCandidates.forEachIndexed { idx, (cand, score) ->
            android.util.Log.d(
                "ZippyPlayback",
                "  Candidate #$idx: sid='${cand.subjectId}', name='${cand.safeName}', year='${cand.year}', score=$score, slug='${cand.safeSlug}'"
            )
        }

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

        var bestItem = topCandidate.first
        var bestScore = topCandidate.second
        var bestPreProbed: StreamProbeResult? = null

        // 4. Ambiguity Check: If multiple distinct provider records have equally strong evidence,
        // resolve by verifying genuine stream resource availability rather than blindly assuming
        // hollow placeholder stubs constitute a conflict with an active playable release.
        if (scoredCandidates.size >= 2) {
            val secondScore = scoredCandidates[1].second
            val closeCandidates = scoredCandidates.filter { abs(it.second - bestScore) < 0.06f }
            if (closeCandidates.size >= 2) {
                if (isTv) {
                    val normBest = normalizeTitle(bestItem.safeName)
                    val normSecond = normalizeTitle(scoredCandidates[1].first.safeName)
                    val isAmbiguous = normBest.isEmpty() || normBest != normSecond
                    if (isAmbiguous) {
                        android.util.Log.d(
                            "ZippyPlayback",
                            "[MOVIEBOX_AMBIGUOUS_REJECT] bestScore=$bestScore ('${bestItem.safeName}'), secondScore=$secondScore ('${scoredCandidates[1].first.safeName}'), diff=${abs(bestScore - secondScore)}"
                        )
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
                } else {
                    // For movies: Check if only ONE of the competing candidates actually has available streams.
                    // This resolves cases where unstreamable placeholder records (e.g. UGC entries with 0 sources)
                    // create false ambiguity conflicts with genuine playable releases (e.g. The Odyssey 2026).
                    val probes = closeCandidates.take(3).map { candidate ->
                        candidate to probeStreamAvailability(candidate.first.subjectId, candidate.first.safeSlug, isTv = false)
                    }
                    val availableList = probes.filter { it.second is StreamProbeResult.Available }

                    if (availableList.size == 1) {
                        val winner = availableList.first()
                        bestItem = winner.first.first
                        bestScore = winner.first.second
                        bestPreProbed = winner.second
                        android.util.Log.d(
                            "ZippyPlayback",
                            "[MOVIEBOX_AMBIGUITY_RESOLVED] Single playable candidate selected among ${closeCandidates.size} close matches: sid='${bestItem.subjectId}', name='${bestItem.safeName}'"
                        )
                    } else {
                        android.util.Log.d(
                            "ZippyPlayback",
                            "[MOVIEBOX_AMBIGUOUS_REJECT] bestScore=$bestScore ('${bestItem.safeName}'), secondScore=$secondScore ('${scoredCandidates[1].first.safeName}'), availableCount=${availableList.size}, diff=${abs(bestScore - secondScore)}"
                        )
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
            android.util.Log.d(
                "ZippyPlayback",
                "[MOVIEBOX_TITLE_REJECT] normCandTitle='$normCandTitle' does not match normTmdbTitle='$normTmdbTitle' or normTmdbOrigTitle='$normTmdbOrigTitle'"
            )
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
        // Exact release year required for automatic verification of movies.
        // For TV series, candidate year can reflect subsequent season air years (>= tmdbYearInt - 1).
        val tmdbYearInt = releaseYear?.toIntOrNull()
        val candYearInt = bestItem.year?.toIntOrNull()

        if (!isTv) {
            if (tmdbYearInt == null || candYearInt == null) {
                // If candidate title is an exact normalized match to TMDB,
                // do not prematurely abort to NEEDS_REVIEW when search results omit the year.
                // Allow Step 7 to fetch detail and let Step 11 verify detail release year.
                if (!isExactCandTitle || tmdbYearInt == null) {
                    android.util.Log.d(
                        "ZippyPlayback",
                        "[MOVIEBOX_YEAR_REJECT] tmdbYearInt=$tmdbYearInt, candYearInt=$candYearInt, isExactCandTitle=$isExactCandTitle"
                    )
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
            } else {
                val candYearDiff = abs(tmdbYearInt - candYearInt)
                if (candYearDiff == 1) {
                    android.util.Log.d(
                        "ZippyPlayback",
                        "[MOVIEBOX_YEAR_DIFF_REJECT] tmdbYearInt=$tmdbYearInt, candYearInt=$candYearInt, diff=$candYearDiff"
                    )
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
                    android.util.Log.d(
                        "ZippyPlayback",
                        "[MOVIEBOX_YEAR_NOT_FOUND] tmdbYearInt=$tmdbYearInt, candYearInt=$candYearInt, diff=$candYearDiff"
                    )
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
            }
        } else {
            // For TV series: reject if candidate aired prior to the TV series premiere
            if (tmdbYearInt != null && candYearInt != null && candYearInt < tmdbYearInt - 1) {
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
        }

        // 7. Verify Candidate via MovieBox Detail API
        android.util.Log.d(
            "ZippyPlayback",
            "[MOVIEBOX_DETAIL_REQUEST] endpoint='/detail/${bestItem.safeSlug}', subjectId='${bestItem.subjectId}'"
        )
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
        if (subject != null) {
            android.util.Log.d(
                "ZippyPlayback",
                "[MOVIEBOX_DETAIL_SUCCESS] endpoint='/detail/${bestItem.safeSlug}', subjectId='${subject.subjectId}', title='${subject.title}', type=${subject.subjectType}, releaseDate='${subject.releaseDate}'"
            )
        }
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
        // For movies: detail release year must not be missing and must not contradict TMDB release year.
        // For TV series: detail releaseDate is frequently omitted in MovieBox; if present, must not precede series debut.
        val detailYearInt = subject.releaseDate?.take(4)?.toIntOrNull()
        val detailYearInvalid = if (!isTv) {
            detailYearInt == null || detailYearInt != tmdbYearInt
        } else {
            detailYearInt != null && tmdbYearInt != null && detailYearInt < tmdbYearInt - 1
        }

        if (detailYearInvalid) {
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
        val probeResult = bestPreProbed ?: probeStreamAvailability(searchSubjectId, bestItem.safeSlug, isTv)
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

        android.util.Log.d(
            "ZippyPlayback",
            "[MOVIEBOX_MAPPING_RESULT] tmdbId=$tmdbId, status=${finalMapping.matchStatus}, subjectId='${finalMapping.movieboxSubjectId}', slug='${finalMapping.movieboxDetailPath}', confidence=${finalMapping.matchConfidence}, hasStreams=${finalMapping.hasAvailableStreams}"
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
