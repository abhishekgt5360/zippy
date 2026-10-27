package com.example.util

import android.content.Context
import com.example.data.model.MovieItem
import com.example.data.model.SubjectDetail

/**
 * Hides sexual / 18+ titles while the 18+ setting is switched off.
 *
 * The list endpoints expose no `isPornographic` flag, and hentai titles are often
 * tagged with a plain `Anime` or `Animation` genre, so this filter combines:
 *  1. A bundled snapshot of detail-verified adult subject IDs, including every
 *     Adult/Erotic title found in the catalog and 18+ search crawl.
 *  2. IDs learned at runtime from detail responses and prior searches, persisted
 *     across launches.
 *  3. Explicit genre tokens (Adult, Erotic, Erotica, Hentai, XXX, 18+).
 *  4. Strong title markers used for suggestion chips, which carry no genre.
 *
 * Anime / animation rows are additionally detail-verified by the ViewModel before
 * search results are published, because genre alone cannot separate hentai from
 * mainstream animation.
 */
object AdultFilter {
    private const val PREFS_NAME = "adult_filter"
    private const val KEY_LEARNED_IDS = "learned_ids"
    private const val BUNDLED_ASSET = "adult_ids.txt"
    private const val MAX_LEARNED_IDS = 40_000

    private val ADULT_GENRE_TOKENS = setOf(
        "adult", "erotic", "erotica", "hentai", "porn", "pornography", "xxx", "18+"
    )
    private val ANIME_GENRE_TOKENS = setOf("anime", "animation")

    /**
     * Markers that are specific enough to identify adult anime without hiding
     * mainstream shows such as "Sex Education" or "The Adulting".
     */
    private val TITLE_MARKERS = listOf(
        "hentai", "haramase", "oppai", "ahegao", "futanari", "ero appli",
        "eroappli", "uncensored sex", "sex game", "doujin", "milf", "porn",
        "xxx", "cunt"
    )

    /** Queries that only make sense when the 18+ lens is enabled. */
    private val ADULT_QUERY_TERMS = setOf(
        "hentai", "porn", "pornography", "xxx", "nsfw", "erotic", "erotica",
        "adult", "18+", "milf", "futanari", "ahegao", "oppai", "doujin", "ecchi"
    )

    @Volatile
    private var initialized = false
    private var bundledIds: Set<String> = emptySet()
    private val learnedIds = linkedSetOf<String>()
    private var prefs: android.content.SharedPreferences? = null

    /**
     * Rows whose `/detail` response explicitly returned `isPornographic = false`.
     * Search re-verifies suspicious anime rows, and this cache keeps repeat searches
     * from paying for the same detail requests again. Process-lifetime only.
     */
    private val verifiedSafeIds = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    @Synchronized
    fun init(context: Context) {
        if (initialized) return
        val appContext = context.applicationContext
        prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

        bundledIds = try {
            appContext.assets.open(BUNDLED_ASSET).bufferedReader().useLines { lines ->
                lines.map { it.trim() }.filter { it.isNotEmpty() }.toHashSet()
            }
        } catch (_: Exception) {
            emptySet()
        }

        learnedIds.clear()
        prefs?.getString(KEY_LEARNED_IDS, null)
            ?.split(',')
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            ?.let { learnedIds.addAll(it) }

        initialized = true
    }

    private fun genreTokens(genre: String?): List<String> =
        genre.orEmpty()
            .lowercase()
            .split(',', '/', '|', ';')
            .map { it.trim() }
            .filter { it.isNotEmpty() }

    fun isAdultId(subjectId: String?): Boolean {
        val id = subjectId?.trim().orEmpty()
        if (id.isEmpty()) return false
        return id in bundledIds || id in learnedIds
    }

    fun isAdultGenre(genre: String?): Boolean =
        genreTokens(genre).any { it in ADULT_GENRE_TOKENS }

    fun isAdultTitle(title: String?): Boolean {
        val value = title.orEmpty().lowercase()
        return value.isNotBlank() && TITLE_MARKERS.any { value.contains(it) }
    }

    fun isAdultQuery(query: String?): Boolean {
        val value = query.orEmpty().trim().lowercase()
        if (value.isEmpty()) return false
        if (value in ADULT_QUERY_TERMS) return true
        return value.split(Regex("\\s+")).any { it in ADULT_QUERY_TERMS }
    }

    fun isAdult(item: MovieItem): Boolean =
        isAdultId(item.subjectId) ||
            isAdultGenre(item.genre) ||
            isAdultTitle(item.name)

    /**
     * Detail responses carry the API's own porn flag, but that flag is unreliable:
     * mainstream titles such as WandaVision and Witches of East End come back with
     * `isPornographic = true`. Trust the flag only when a genre/title/ID signal or an
     * anime/Japan origin corroborates it, so flagged mainstream titles stay playable.
     */
    fun isAdultDetail(subject: SubjectDetail?): Boolean {
        if (subject == null) return false
        if (isAdultId(subject.subjectId) ||
            isAdultGenre(subject.genre) ||
            isAdultTitle(subject.title)
        ) {
            return true
        }
        if (subject.isPornographic != true) return false
        return genreTokens(subject.genre).any { it in ANIME_GENRE_TOKENS } ||
            subject.countryName.equals("Japan", ignoreCase = true)
    }

    fun isAdultSuggestion(title: String?, subjectId: String?): Boolean =
        isAdultId(subjectId) || isAdultTitle(title)

    /**
     * True when a list row cannot be classified from its genre alone. These items
     * are checked against `/detail` before search results are shown with 18+ off.
     */
    fun isSuspiciousForVerification(item: MovieItem): Boolean {
        if (isAdult(item)) return false
        if (isVerifiedSafe(item.subjectId)) return false
        val tokens = genreTokens(item.genre)
        if (tokens.any { it in ANIME_GENRE_TOKENS }) return true
        return item.country.equals("Japan", ignoreCase = true)
    }

    fun isVerifiedSafe(subjectId: String?): Boolean {
        val id = subjectId?.trim().orEmpty()
        return id.isNotEmpty() && id in verifiedSafeIds
    }

    fun markVerifiedSafe(subjectId: String?) {
        val id = subjectId?.trim().orEmpty()
        if (id.isNotEmpty()) verifiedSafeIds.add(id)
    }

    fun filter(items: List<MovieItem>): List<MovieItem> = items.filterNot { isAdult(it) }

    /** Filters and remembers IDs caught by a genre/title signal for later surfaces. */
    fun filterAndLearn(items: List<MovieItem>): List<MovieItem> {
        val learnable = ArrayList<String>(items.size)
        val kept = items.filterNot { item ->
            val blocked = isAdult(item)
            if (blocked && item.subjectId.isNotBlank()) learnable.add(item.subjectId)
            blocked
        }
        if (learnable.isNotEmpty()) learnAll(learnable)
        return kept
    }

    fun learn(subjectId: String?) {
        val id = subjectId?.trim().orEmpty()
        if (id.isEmpty() || id in bundledIds) return
        synchronized(learnedIds) {
            if (!learnedIds.add(id)) return
            while (learnedIds.size > MAX_LEARNED_IDS) {
                val oldest = learnedIds.firstOrNull() ?: break
                learnedIds.remove(oldest)
            }
        }
        persist()
    }

    fun learnAll(subjectIds: Collection<String>) {
        var changed = false
        synchronized(learnedIds) {
            subjectIds.forEach { raw ->
                val id = raw.trim()
                if (id.isNotEmpty() && id !in bundledIds && learnedIds.add(id)) {
                    changed = true
                }
            }
            while (learnedIds.size > MAX_LEARNED_IDS) {
                val oldest = learnedIds.firstOrNull() ?: break
                learnedIds.remove(oldest)
                changed = true
            }
        }
        if (changed) persist()
    }

    private fun persist() {
        val snapshot = synchronized(learnedIds) { learnedIds.toList() }
        prefs?.edit()?.putString(KEY_LEARNED_IDS, snapshot.joinToString(","))?.apply()
    }
}
