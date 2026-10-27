package com.example.util

import android.content.Context
import com.example.data.model.MovieItem

/**
 * Hides Nollywood / Nigerian titles across every surface of the app.
 *
 * The public API exposes no exclusion parameters and catalog rows do not carry a
 * country field, so the filter combines three signals:
 *  1. A bundled snapshot of subject IDs collected from `genre=Nollywood` and
 *     `country=Nigeria` catalog pages.
 *  2. IDs learned at runtime from Nollywood sections, search results, and detail
 *     metadata, persisted so they survive restarts.
 *  3. Strong textual markers (Nollywood, Nigeria, Yoruba, Igbo, Hausa, Naija) in
 *     section names, genres, titles, countries, and languages.
 *
 * Titles that only look like ordinary dramas are caught by the ID lists, which is
 * why IDs from a Nollywood section must be learned before that section is dropped.
 */
object NollywoodFilter {
    private const val PREFS_NAME = "nollywood_filter"
    private const val KEY_LEARNED_IDS = "learned_ids"
    private const val BUNDLED_ASSET = "nollywood_ids.txt"
    private const val MAX_LEARNED_IDS = 40_000

    private val SECTION_MARKERS = listOf("nollywood", "nigeria", "naija")
    private val GENRE_MARKERS = listOf("nollywood", "yoruba", "igbo", "hausa", "naija")
    private val TITLE_MARKERS = listOf(
        "nollywood", "nigerian", "naija", "yoruba", "igbo", "hausa",
        // Yoruba words that show up in Nollywood titles without any country metadata.
        "olori", "ogun", "orisa", "osun", "sango", "oba nla", "orunmila", "ifa"
    )
    private val COUNTRY_MARKERS = setOf("nigeria", "nigerian", "ng", "nga")
    private val LANGUAGE_MARKERS = setOf("yoruba", "igbo", "hausa", "pidgin", "nigerian pidgin")

    /** Short markers like `ifa`/`ogun` must match whole words so "Cobra" and "Global" survive. */
    private fun containsMarker(text: String, marker: String): Boolean {
        if (marker.length >= 5 || marker.contains(' ')) return text.contains(marker)
        val regex = Regex("(?<![a-z0-9])${Regex.escape(marker)}(?![a-z0-9])")
        return regex.containsMatchIn(text)
    }

    @Volatile
    private var initialized = false
    private var bundledIds: Set<String> = emptySet()
    private val learnedIds = linkedSetOf<String>()
    private var prefs: android.content.SharedPreferences? = null

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

    fun isBlockedId(subjectId: String?): Boolean {
        val id = subjectId?.trim().orEmpty()
        if (id.isEmpty()) return false
        return id in bundledIds || id in learnedIds
    }

    fun isBlockedSection(sectionName: String?): Boolean {
        val name = sectionName.orEmpty().lowercase()
        return name.isNotBlank() && SECTION_MARKERS.any { name.contains(it) }
    }

    fun isBlockedGenre(genre: String?): Boolean {
        val value = genre.orEmpty().lowercase()
        return value.isNotBlank() && GENRE_MARKERS.any { value.contains(it) }
    }

    fun isBlockedTitle(title: String?): Boolean {
        val value = title.orEmpty().lowercase()
        return value.isNotBlank() && TITLE_MARKERS.any { containsMarker(value, it) }
    }

    fun isBlockedCountry(country: String?): Boolean {
        val value = country?.trim()?.lowercase().orEmpty()
        return value.isNotEmpty() && value in COUNTRY_MARKERS
    }

    fun isBlockedLanguage(language: String?): Boolean {
        val value = language?.trim()?.lowercase().orEmpty()
        return value.isNotEmpty() && value in LANGUAGE_MARKERS
    }

    fun isBlockedMetadata(
        title: String? = null,
        genre: String? = null,
        country: String? = null,
        language: String? = null
    ): Boolean = isBlockedTitle(title) ||
        isBlockedGenre(genre) ||
        isBlockedCountry(country) ||
        isBlockedLanguage(language)

    fun isBlocked(item: MovieItem): Boolean =
        isBlockedId(item.subjectId) ||
            isBlockedMetadata(
                title = item.name,
                genre = item.genre,
                country = item.country,
                language = item.language
            )

    fun isBlockedSuggestion(title: String?, subjectId: String?): Boolean =
        isBlockedId(subjectId) || isBlockedTitle(title)

    fun filter(items: List<MovieItem>): List<MovieItem> = items.filterNot { isBlocked(it) }

    /**
     * Filters [items] and remembers the IDs of anything caught by a strong textual
     * signal, so the same title stays blocked on surfaces that drop country/language.
     */
    fun filterAndLearn(items: List<MovieItem>): List<MovieItem> {
        val learnable = ArrayList<String>(items.size)
        val kept = items.filterNot { item ->
            val blocked = isBlocked(item)
            if (blocked && hasTextualMarker(item)) {
                item.subjectId?.takeIf { it.isNotBlank() }?.let(learnable::add)
            }
            blocked
        }
        if (learnable.isNotEmpty()) learnAll(learnable)
        return kept
    }

    private fun hasTextualMarker(item: MovieItem): Boolean =
        isBlockedTitle(item.name) ||
            isBlockedGenre(item.genre) ||
            isBlockedCountry(item.country) ||
            isBlockedLanguage(item.language)

    fun filterSectionItems(items: List<MovieItem>): List<MovieItem> = filterAndLearn(items)

    /** Learns every title in a section before that section is hidden from the feed. */
    fun learnFromSection(items: List<MovieItem>) {
        learnAll(items.mapNotNull { it.subjectId.takeIf { id -> id.isNotBlank() } })
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
