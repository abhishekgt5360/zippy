package com.example.ui

import android.app.Application
import android.content.Context
import androidx.annotation.OptIn
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.mp4.FragmentedMp4Extractor
import androidx.media3.extractor.mp4.Mp4Extractor
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import com.example.data.local.AppDatabase
import com.example.data.local.WatchHistoryEntity
import com.example.data.local.WatchlistEntity
import com.example.data.model.*
import com.example.data.repository.MovieRepository
import com.example.util.AdultFilter
import com.example.util.NollywoodFilter
import com.example.util.PlaybackSession
import com.example.util.RegionalCategoryConfig
import com.example.util.ResumeManager
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

import com.example.ui.theme.ThemeMode

private const val HTTP_USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
private const val REFERER_HEADER = "https://moviebox.ph/"

enum class AppTab {
    HOME,
    CATALOG,
    SEARCH,
    LIBRARY
}

enum class CatalogType(val label: String) {
    MOVIES("Movies"),
    TV_SERIES("TV Series"),
    ANIMATION("Animation")
}

data class HomeUiState(
    val isLoading: Boolean = true,
    val bannerItems: List<MovieItem> = emptyList(),
    val sections: List<HomeSection> = emptyList(),
    val errorMessage: String? = null
)

data class CatalogUiState(
    val type: CatalogType = CatalogType.MOVIES,
    val selectedPresetId: String = RegionalCategoryConfig.PRESET_ALL,
    val adultsOnly: Boolean = false,
    val selectedYear: String? = null,
    val selectedGenre: String = "All",
    val selectedSort: String = "foryou",
    val items: List<MovieItem> = emptyList(),
    val isLoading: Boolean = false,
    val page: Int = 1,
    val hasMore: Boolean = true,
    val errorMessage: String? = null
)

data class SearchUiState(
    val query: String = "",
    val suggestions: List<String> = emptyList(),
    val results: List<MovieItem> = emptyList(),
    val isSearching: Boolean = false,
    val errorMessage: String? = null
)

data class DetailPlayerUiState(
    val slug: String? = null,
    val subjectId: String? = null,
    val title: String = "",
    val posterUrl: String? = null,
    val detail: DetailResponse? = null,
    val isSeries: Boolean = false,
    val isLoadingDetail: Boolean = false,
    val selectedSeason: Int = 0,
    val selectedEpisode: Int = 0,
    val availableSeasons: List<SeasonItem> = emptyList(),
    val isPlaying: Boolean = false,
    val streamResponse: StreamResponse? = null,
    val captions: List<CaptionItem> = emptyList(),
    val isLoadingStream: Boolean = false,
    val streamError: String? = null,
    val session: PlaybackSession? = null,
    val isWatchlisted: Boolean = false,
    /** Shown once as a toast when a blocked title was tapped from a surface we cannot pre-filter. */
    val blockedNotice: String? = null
)

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val prefs = application.getSharedPreferences("cinepulse_prefs", Context.MODE_PRIVATE)

    private val _themeMode = MutableStateFlow(
        try {
            ThemeMode.valueOf(prefs.getString("theme_mode", ThemeMode.CRIMSON.name) ?: ThemeMode.CRIMSON.name)
        } catch (e: Exception) {
            ThemeMode.CRIMSON
        }
    )
    val themeMode: StateFlow<ThemeMode> = _themeMode.asStateFlow()

    fun setThemeMode(mode: ThemeMode) {
        prefs.edit().putString("theme_mode", mode.name).apply()
        _themeMode.value = mode
    }

    private val _isAdultEnabled = MutableStateFlow(prefs.getBoolean("adult_enabled", false))
    val isAdultEnabled: StateFlow<Boolean> = _isAdultEnabled.asStateFlow()

    fun setAdultEnabled(enabled: Boolean) {
        if (_isAdultEnabled.value == enabled) return
        prefs.edit().putBoolean("adult_enabled", enabled).apply()
        _isAdultEnabled.value = enabled

        if (!enabled) {
            // An adult detail may be open from when the lens was still allowed.
            val openSubject = _detailPlayerState.value.detail?.data?.subject
            val openId = _detailPlayerState.value.subjectId
            val openTitle = _detailPlayerState.value.title
            if (AdultFilter.isAdultDetail(openSubject) ||
                AdultFilter.isAdultId(openId) ||
                AdultFilter.isAdultTitle(openTitle)
            ) {
                AdultFilter.learn(openId)
                closeBlockedDetail(openTitle)
            }
        }

        if (!enabled && _catalogState.value.adultsOnly) {
            // Drop the 18+ lens immediately when the setting is switched off.
            setCatalogAdultsOnly(false)
        } else {
            loadCatalog(reset = true)
        }
        loadHomeFeed()
        val activeQuery = _searchState.value.query
        if (activeQuery.isNotBlank()) performSearch(activeQuery)
    }

    /**
     * Every list surface funnels through here: Nollywood titles are always removed,
     * and sexual titles are removed while the 18+ setting is off.
     */
    private fun filterContent(
        items: List<MovieItem>,
        allowAdult: Boolean = _isAdultEnabled.value
    ): List<MovieItem> {
        val cleaned = NollywoodFilter.filterAndLearn(items)
        return if (allowAdult) cleaned else AdultFilter.filterAndLearn(cleaned)
    }

    private val database = AppDatabase.getInstance(application)
    private val repository = MovieRepository(database = database, context = application)

    // Navigation state
    private val _currentTab = MutableStateFlow(AppTab.HOME)
    val currentTab: StateFlow<AppTab> = _currentTab.asStateFlow()

    // Home state primed with disk/memory cache for instant 0ms load
    private val _homeState = MutableStateFlow(
        run {
            val cachedHome = repository.getCachedHome()
            val cachedBolly = repository.getCachedBollywood()
            if (cachedHome == null && cachedBolly == null) {
                HomeUiState()
            } else {
                val (banner, sections) = cleanHomeSections(
                    rawSections = cachedHome?.sections.orEmpty(),
                    bollywoodItems = cachedBolly?.items.orEmpty(),
                    allowAdult = _isAdultEnabled.value
                )
                HomeUiState(
                    isLoading = false,
                    bannerItems = banner,
                    sections = sections,
                    errorMessage = null
                )
            }
        }
    )
    val homeState: StateFlow<HomeUiState> = _homeState.asStateFlow()

    // Catalog state
    private val _catalogState = MutableStateFlow(CatalogUiState())
    val catalogState: StateFlow<CatalogUiState> = _catalogState.asStateFlow()

    // Search state
    private val _searchState = MutableStateFlow(SearchUiState())
    val searchState: StateFlow<SearchUiState> = _searchState.asStateFlow()
    private var searchJob: Job? = null
    private var catalogJob: Job? = null
    private var catalogJobIsReset = false

    // Detail & Player state
    private val _detailPlayerState = MutableStateFlow(DetailPlayerUiState())
    val detailPlayerState: StateFlow<DetailPlayerUiState> = _detailPlayerState.asStateFlow()

    // Room flows. Combining with the 18+ switch makes blocked entries disappear
    // immediately when the setting is turned off, without waiting for a relaunch.
    val watchHistory: StateFlow<List<WatchHistoryEntity>> = combine(
        repository.watchHistory,
        _isAdultEnabled
    ) { history, allowAdult ->
        history.filterNot { isBlockedLibraryEntry(it.subjectId, it.title, null, allowAdult) }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val watchlist: StateFlow<List<WatchlistEntity>> = combine(
        repository.watchlist,
        _isAdultEnabled
    ) { list, allowAdult ->
        list.filterNot { isBlockedLibraryEntry(it.subjectId, it.title, it.genre, allowAdult) }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // Single persistent ExoPlayer instance surviving screen rotation
    private var playerInstance: ExoPlayer? = null

    init {
        loadHomeFeed()
        loadCatalog(reset = true)
    }

    @OptIn(UnstableApi::class)
    fun getPlayer(context: Context): ExoPlayer {
        if (playerInstance == null) {
            val httpDataSourceFactory = DefaultHttpDataSource.Factory()
                .setUserAgent(HTTP_USER_AGENT)
                .setDefaultRequestProperties(
                    mapOf(
                        "Referer" to REFERER_HEADER,
                        "Origin" to "https://moviebox.ph",
                        "Accept" to "*/*",
                        "Accept-Encoding" to "identity" // CRITICAL: Prevents CDN proxy from gzip-compressing MP4 containers which causes parsing container malformed
                    )
                )
                .setAllowCrossProtocolRedirects(true)
                .setConnectTimeoutMs(25000)
                .setReadTimeoutMs(35000)

            val dataSourceFactory = DefaultDataSource.Factory(context.applicationContext, httpDataSourceFactory)

            // Robust MP4 and Fragmented MP4 extractors with tolerant sample table parsing and edit list workarounds
            val extractorsFactory = DefaultExtractorsFactory().apply {
                setConstantBitrateSeekingEnabled(true)
                setMp4ExtractorFlags(Mp4Extractor.FLAG_WORKAROUND_IGNORE_EDIT_LISTS)
                setFragmentedMp4ExtractorFlags(
                    FragmentedMp4Extractor.FLAG_WORKAROUND_IGNORE_EDIT_LISTS or
                    FragmentedMp4Extractor.FLAG_WORKAROUND_EVERY_VIDEO_FRAME_IS_SYNC_FRAME
                )
            }

            val loadErrorHandlingPolicy = DefaultLoadErrorHandlingPolicy(6)

            val mediaSourceFactory = DefaultMediaSourceFactory(dataSourceFactory, extractorsFactory)
                .setLoadErrorHandlingPolicy(loadErrorHandlingPolicy)

            // Resilient buffer control preventing buffer underruns and mid-stream chunk drops
            val loadControl = DefaultLoadControl.Builder()
                .setBufferDurationsMs(
                    /* minBufferMs = */ 30_000,
                    /* maxBufferMs = */ 60_000,
                    /* bufferForPlaybackMs = */ 2_500,
                    /* bufferForPlaybackAfterRebufferMs = */ 5_000
                )
                .setPrioritizeTimeOverSizeThresholds(true)
                .build()

            playerInstance = ExoPlayer.Builder(context.applicationContext)
                .setMediaSourceFactory(mediaSourceFactory)
                .setLoadControl(loadControl)
                .setSeekForwardIncrementMs(10000)
                .setSeekBackIncrementMs(10000)
                .build().apply {
                    playWhenReady = true
                }
        }
        return playerInstance!!
    }

    fun selectTab(tab: AppTab) {
        _currentTab.value = tab
    }

    private fun filterHomeItems(items: List<MovieItem>, allowAdult: Boolean): List<MovieItem> {
        return filterContent(items, allowAdult)
    }

    private fun isBlockedLibraryEntry(
        subjectId: String?,
        title: String?,
        genre: String?,
        allowAdult: Boolean
    ): Boolean {
        val nollywood = NollywoodFilter.isBlockedId(subjectId) ||
            NollywoodFilter.isBlockedTitle(title) ||
            NollywoodFilter.isBlockedGenre(genre)
        if (nollywood) NollywoodFilter.learn(subjectId)

        val adult = !allowAdult && (
            AdultFilter.isAdultId(subjectId) ||
                AdultFilter.isAdultTitle(title) ||
                AdultFilter.isAdultGenre(genre)
            )
        if (adult) AdultFilter.learn(subjectId)
        return nollywood || adult
    }

    private fun blockedMessage(title: String?): String {
        val label = title?.takeIf { it.isNotBlank() } ?: "This title"
        return "\u201C$label\u201D isn't available in this app."
    }

    private fun noticeBlocked(title: String?) {
        _detailPlayerState.update { it.copy(blockedNotice = blockedMessage(title)) }
    }

    /**
     * A surface we could not pre-filter handed us a blocked title, so tear the detail
     * view down entirely and leave only the one-shot notice behind.
     */
    private fun closeBlockedDetail(title: String?) {
        playerInstance?.stop()
        playerInstance?.clearMediaItems()
        _detailPlayerState.value = DetailPlayerUiState(blockedNotice = blockedMessage(title))
    }

    fun clearBlockedNotice() {
        _detailPlayerState.update { it.copy(blockedNotice = null) }
    }

    /**
     * Single home-feed cleanup used by the cache primer, the fresh response, and the
     * offline fallback. IDs are learned from a Nollywood row before that row is
     * dropped, so the same titles cannot reappear in ordinary rows or the banner.
     */
    private fun cleanHomeSections(
        rawSections: List<HomeSection>,
        bollywoodItems: List<MovieItem>,
        allowAdult: Boolean
    ): Pair<List<MovieItem>, List<HomeSection>> {
        val banner = filterHomeItems(
            rawSections.firstOrNull { it.section.equals("Banner", ignoreCase = true) }?.items.orEmpty(),
            allowAdult
        )

        val sections = rawSections.filter {
            !it.section.equals("Banner", ignoreCase = true) && it.items.isNotEmpty()
        }
        sections.filter { NollywoodFilter.isBlockedSection(it.section) }
            .forEach { NollywoodFilter.learnFromSection(it.items) }

        val cleanedSections = sections
            .filterNot { NollywoodFilter.isBlockedSection(it.section) }
            .map { it.copy(items = filterHomeItems(it.items, allowAdult)) }
            .filter { it.items.isNotEmpty() }
            .toMutableList()

        val bollywood = filterHomeItems(bollywoodItems, allowAdult)
        if (bollywood.isNotEmpty()) {
            cleanedSections.add(
                0,
                HomeSection(
                    section = RegionalCategoryConfig.ROW_TITLE,
                    count = bollywood.size,
                    items = bollywood
                )
            )
        }
        return banner to cleanedSections
    }

    fun loadHomeFeed() {
        viewModelScope.launch {
            // Only show full loading indicator if we don't already have cached content
            if (_homeState.value.sections.isEmpty()) {
                val cachedHome = repository.getCachedHome()
                val cachedBolly = repository.getCachedBollywood()
                if (cachedHome != null || cachedBolly != null) {
                    val (bannerItems, otherSections) = cleanHomeSections(
                        rawSections = cachedHome?.sections.orEmpty(),
                        bollywoodItems = cachedBolly?.items.orEmpty(),
                        allowAdult = _isAdultEnabled.value
                    )
                    _homeState.update {
                        it.copy(
                            isLoading = false,
                            bannerItems = bannerItems,
                            sections = otherSections,
                            errorMessage = null
                        )
                    }
                } else {
                    _homeState.update { it.copy(isLoading = true, errorMessage = null) }
                }
            }

            val homeDeferred = async { repository.getHome() }
            val bollyDeferred = async { repository.getBollywoodMovies() }

            val homeResult = homeDeferred.await()
            val bollyResult = bollyDeferred.await()

            homeResult.onSuccess { response ->
                val (bannerItems, otherSections) = cleanHomeSections(
                    rawSections = response.sections.orEmpty(),
                    bollywoodItems = bollyResult.getOrNull()?.items.orEmpty(),
                    allowAdult = _isAdultEnabled.value
                )
                _homeState.update {
                    it.copy(
                        isLoading = false,
                        bannerItems = bannerItems,
                        sections = otherSections,
                        errorMessage = null
                    )
                }
            }.onFailure { error ->
                val cachedHome = repository.getCachedHome()
                val cachedBolly = repository.getCachedBollywood()
                if (cachedHome != null || cachedBolly != null) {
                    val (bannerItems, otherSections) = cleanHomeSections(
                        rawSections = cachedHome?.sections.orEmpty(),
                        bollywoodItems = cachedBolly?.items.orEmpty(),
                        allowAdult = _isAdultEnabled.value
                    )
                    _homeState.update {
                        it.copy(
                            isLoading = false,
                            bannerItems = bannerItems,
                            sections = otherSections,
                            errorMessage = null
                        )
                    }
                } else {
                    _homeState.update {
                        it.copy(
                            isLoading = false,
                            errorMessage = error.localizedMessage ?: "Failed to load home feed. Please check your connection."
                        )
                    }
                }
            }
        }
    }

    fun setCatalogType(type: CatalogType) {
        if (_catalogState.value.type != type) {
            val isAnimation = type == CatalogType.ANIMATION
            _catalogState.update {
                it.copy(
                    type = type,
                    // Animation has no country/year controls, so never carry them in.
                    selectedPresetId = if (isAnimation) RegionalCategoryConfig.PRESET_ALL else it.selectedPresetId,
                    selectedYear = if (isAnimation) null else it.selectedYear,
                    page = 1
                )
            }
            loadCatalog(reset = true)
        }
    }

    fun setCatalogPreset(presetId: String) {
        val preset = RegionalCategoryConfig.PRESETS.firstOrNull { it.id == presetId } ?: return
        if (_catalogState.value.selectedPresetId != presetId) {
            _catalogState.update { it.copy(selectedPresetId = presetId, page = 1) }
            loadCatalog(reset = true)
        }
    }

    /**
     * Toggles the 18+ lens without replacing the selected country, year, or sort.
     * The Adult genre is sent alongside those params, so "Adult + India" works.
     */
    fun setCatalogAdultsOnly(enabled: Boolean) {
        if (enabled && !_isAdultEnabled.value) return
        if (_catalogState.value.adultsOnly == enabled) return
        _catalogState.update {
            it.copy(
                adultsOnly = enabled,
                // The Adult token owns the genre slot; don't leave a hidden genre applied.
                selectedGenre = if (enabled) "All" else it.selectedGenre,
                // The catalog only returns Adult titles under For You ordering, so the
                // other sorts would strand the user on a guaranteed-empty list.
                selectedSort = if (enabled) "foryou" else it.selectedSort,
                page = 1
            )
        }
        loadCatalog(reset = true)
    }

    fun setCatalogYear(year: String?) {
        if (_catalogState.value.selectedYear != year) {
            _catalogState.update { it.copy(selectedYear = year, page = 1) }
            loadCatalog(reset = true)
        }
    }

    fun setCatalogGenre(genre: String) {
        if (_catalogState.value.selectedGenre != genre) {
            _catalogState.update { it.copy(selectedGenre = genre, page = 1) }
            loadCatalog(reset = true)
        }
    }

    fun setCatalogSort(sort: String) {
        // Adult titles only exist under For You ordering; coerce rather than send a
        // sort that the catalog answers with an empty page.
        val effectiveSort = if (_catalogState.value.adultsOnly && sort != "foryou") "foryou" else sort
        if (_catalogState.value.selectedSort != effectiveSort) {
            _catalogState.update { it.copy(selectedSort = effectiveSort, page = 1) }
            loadCatalog(reset = true)
        }
    }

    fun resetCatalogFilters() {
        _catalogState.update {
            it.copy(
                selectedPresetId = RegionalCategoryConfig.PRESET_ALL,
                adultsOnly = false,
                selectedYear = null,
                selectedGenre = "All",
                selectedSort = "foryou",
                page = 1
            )
        }
        loadCatalog(reset = true)
    }

    /**
     * Opens the catalog on [presetId] with a clean filter state. Used by Home "See all"
     * rows so a previous year/genre/sort selection never leaks into the destination list.
     */
    fun openCatalogPreset(presetId: String, type: CatalogType = CatalogType.MOVIES) {
        if (RegionalCategoryConfig.PRESETS.none { it.id == presetId }) return

        val isAnimation = type == CatalogType.ANIMATION
        _catalogState.update {
            it.copy(
                type = type,
                selectedPresetId = if (isAnimation) RegionalCategoryConfig.PRESET_ALL else presetId,
                adultsOnly = false,
                selectedYear = null,
                selectedGenre = "All",
                selectedSort = "foryou",
                page = 1
            )
        }
        loadCatalog(reset = true)
    }

    private fun catalogRequestSignature(state: CatalogUiState): String {
        val preset = RegionalCategoryConfig.presetById(state.selectedPresetId)
        val genre = if (state.adultsOnly) {
            RegionalCategoryConfig.ADULT_GENRE
        } else {
            preset.genre ?: state.selectedGenre.takeUnless { it == "All" }
        }
        val year = if (state.type == CatalogType.ANIMATION) null else state.selectedYear
        return "${state.type.name}|${preset.id}|${state.adultsOnly}|$genre|${state.selectedSort}|$year"
    }

    fun loadCatalog(reset: Boolean = false) {
        val snapshot = _catalogState.value
        if (!reset && (snapshot.isLoading || !snapshot.hasMore)) return
        // Never let auto-pagination cancel a reset that is still in flight.
        if (!reset && catalogJob?.isActive == true && catalogJobIsReset) return

        catalogJob?.cancel()

        val targetPage = if (reset) 1 else snapshot.page + 1
        val preset = RegionalCategoryConfig.presetById(snapshot.selectedPresetId)
        val isAnimation = snapshot.type == CatalogType.ANIMATION
        val yearParam = if (isAnimation) null else snapshot.selectedYear
        val genreParam = if (snapshot.adultsOnly) {
            RegionalCategoryConfig.ADULT_GENRE
        } else {
            preset.genre ?: snapshot.selectedGenre.takeUnless { it == "All" }
        }
        val sortParam = snapshot.selectedSort
        val requestSignature = catalogRequestSignature(snapshot)
        val cacheKey = MovieRepository.presetCacheKey(
            preset = preset,
            type = snapshot.type,
            page = targetPage,
            sort = sortParam,
            genre = genreParam,
            year = yearParam
        )

        // Immediately display cached catalog items if available on reset.
        if (reset) {
            val cached = repository.getCachedCatalog(cacheKey)
            val cachedItems = cached?.items?.let { filterContent(it) }.orEmpty()
            if (cachedItems.isNotEmpty()) {
                _catalogState.update {
                    it.copy(
                        items = cachedItems,
                        isLoading = false,
                        page = 1,
                        hasMore = cached?.hasMore ?: true,
                        errorMessage = null
                    )
                }
            } else {
                // Clear the previous filter's results rather than showing stale titles.
                _catalogState.update {
                    it.copy(
                        items = emptyList(),
                        isLoading = true,
                        page = 1,
                        hasMore = true,
                        errorMessage = null
                    )
                }
            }
        } else {
            _catalogState.update { it.copy(isLoading = true, errorMessage = null) }
        }

        catalogJobIsReset = reset
        catalogJob = viewModelScope.launch {
            var pageToLoad = targetPage
            var accumulated = if (reset) emptyList() else _catalogState.value.items
            var lastError: Throwable? = null
            var hasMore = true
            var hops = 0
            val maxHops = if (reset) 5 else 3

            // A whole page can be Nollywood-only, so keep fetching until something
            // survives the filter. Bounded so a fully blocked catalog cannot spin forever.
            while (hops < maxHops) {
                hops++
                val result = repository.getPresetCatalog(
                    preset = preset,
                    type = snapshot.type,
                    genre = genreParam,
                    sort = sortParam,
                    year = yearParam,
                    page = pageToLoad
                )

                // A newer filter state owns the screen now; drop this response.
                if (catalogRequestSignature(_catalogState.value) != requestSignature) return@launch

                val response = result.getOrNull()
                if (response == null) {
                    lastError = result.exceptionOrNull()
                    break
                }

                val filtered = filterContent(response.items)
                accumulated = if (reset && hops == 1) {
                    filtered
                } else {
                    (accumulated + filtered).distinctBy { "${it.safeSlug}_${it.subjectId}" }
                }
                hasMore = response.hasMore ?: response.items.isNotEmpty()

                _catalogState.update {
                    it.copy(
                        isLoading = false,
                        items = accumulated,
                        page = pageToLoad,
                        hasMore = hasMore,
                        errorMessage = null
                    )
                }

                if (filtered.isNotEmpty() || !hasMore) break
                pageToLoad++
            }

            if (lastError != null) {
                _catalogState.update {
                    it.copy(
                        isLoading = false,
                        errorMessage = if (accumulated.isEmpty()) {
                            lastError.localizedMessage ?: "Failed to load catalog."
                        } else {
                            null
                        }
                    )
                }
            }
        }
    }

    fun onSearchQueryChanged(newQuery: String) {
        _searchState.update { it.copy(query = newQuery) }
        searchJob?.cancel()

        if (newQuery.isBlank()) {
            _searchState.update { it.copy(suggestions = emptyList(), results = emptyList(), isSearching = false) }
            return
        }

        searchJob = viewModelScope.launch {
            delay(350)
            if (!_isAdultEnabled.value && AdultFilter.isAdultQuery(newQuery)) {
                // An 18+-only query has nothing to suggest while the gate is closed.
                _searchState.update { it.copy(suggestions = emptyList()) }
            } else {
                repository.getSuggestions(newQuery).onSuccess { res ->
                    val allowAdult = _isAdultEnabled.value
                    val titles = res.suggestions
                        .filterNot { suggestion ->
                            val nollywood = NollywoodFilter.isBlockedSuggestion(
                                suggestion.title,
                                suggestion.subjectId
                            )
                            if (nollywood) NollywoodFilter.learn(suggestion.subjectId)

                            val adult = !allowAdult && AdultFilter.isAdultSuggestion(
                                suggestion.title,
                                suggestion.subjectId
                            )
                            if (adult) AdultFilter.learn(suggestion.subjectId)
                            nollywood || adult
                        }
                        .mapNotNull { it.title }
                        .filter { it.isNotBlank() }
                        .distinct()
                    _searchState.update { it.copy(suggestions = titles) }
                }
            }
            performSearch(newQuery)
        }
    }

    fun performSearch(queryToSearch: String) {
        val q = queryToSearch.trim()
        if (q.isBlank()) return
        searchJob?.cancel()
        searchJob = viewModelScope.launch {
            // Suggestion chips are title-only (the suggest endpoint returns no IDs or
            // country), so drop them as soon as a real search starts. Results carry
            // country/ID metadata and are the trustworthy surface.
            _searchState.update {
                it.copy(query = q, isSearching = true, errorMessage = null, suggestions = emptyList())
            }

            val allowAdult = _isAdultEnabled.value
            if (!allowAdult && AdultFilter.isAdultQuery(q)) {
                _searchState.update {
                    it.copy(isSearching = false, results = emptyList(), errorMessage = null)
                }
                return@launch
            }

            val result = repository.search(query = q, page = 1)
            val response = result.getOrNull()
            if (response == null) {
                if (_searchState.value.query != q) return@launch
                _searchState.update {
                    it.copy(
                        isSearching = false,
                        errorMessage = result.exceptionOrNull()?.localizedMessage ?: "Search failed."
                    )
                }
                return@launch
            }

            val cleaned = filterContent(response.items, allowAdult)
            // Hentai is tagged as plain Anime/Animation, so those rows are verified
            // against /detail before they are allowed on screen.
            val verified = if (allowAdult) cleaned else verifySuspiciousRows(cleaned)
            if (_searchState.value.query != q) return@launch
            _searchState.update {
                it.copy(isSearching = false, results = verified, errorMessage = null)
            }
        }
    }

    /**
     * `/detail` is the only payload that carries `isPornographic`, and hentai rows
     * arrive tagged as plain `Anime`/`Animation`. Suspicious search rows are therefore
     * checked before publication. Rows that cannot be verified after a retry are
     * hidden rather than risk a leak, and explicitly safe IDs are cached in memory.
     */
    private suspend fun verifySuspiciousRows(items: List<MovieItem>): List<MovieItem> {
        val suspects = items.filter { AdultFilter.isSuspiciousForVerification(it) }
        if (suspects.isEmpty()) return items

        fun keyOf(item: MovieItem) = "${item.subjectId}|${item.safeSlug}"
        val blockedKeys = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

        coroutineScope {
            // Bounded concurrency: the API 404s under mass parallel detail requests.
            suspects.chunked(4).forEach { chunk ->
                val checks = chunk.map { item ->
                    async {
                        val slug = item.safeSlug
                        if (slug.isBlank()) {
                            blockedKeys.add(keyOf(item))
                            return@async
                        }
                        var detail = repository.getDetail(slug)
                        if (detail.isFailure) detail = repository.getDetail(slug)
                        val subject = detail.getOrNull()?.data?.subject
                        when {
                            subject == null -> blockedKeys.add(keyOf(item))
                            AdultFilter.isAdultDetail(subject) ->
                                blockedKeys.add(keyOf(item))
                            subject.isPornographic == false ->
                                AdultFilter.markVerifiedSafe(item.subjectId)
                        }
                    }
                }
                checks.awaitAll()
            }
        }

        val learned = suspects
            .filter { keyOf(it) in blockedKeys }
            .map { it.subjectId }
            .filter { it.isNotBlank() }
        if (learned.isNotEmpty()) AdultFilter.learnAll(learned)

        return items.filterNot { keyOf(it) in blockedKeys }
    }

    fun openDetail(
        slug: String,
        subjectId: String,
        title: String = "",
        initialType: String? = null,
        posterUrl: String? = null
    ) {
        // Library rows, deep links, and search hits can all reach a blocked title, so
        // gate before any detail state is published.
        if (NollywoodFilter.isBlockedId(subjectId) || NollywoodFilter.isBlockedTitle(title)) {
            NollywoodFilter.learn(subjectId)
            noticeBlocked(title)
            return
        }
        if (!_isAdultEnabled.value &&
            (AdultFilter.isAdultId(subjectId) || AdultFilter.isAdultTitle(title))
        ) {
            AdultFilter.learn(subjectId)
            noticeBlocked(title)
            return
        }

        val guessedSeries = initialType.equals("tv", ignoreCase = true) || initialType.equals("tv-series", ignoreCase = true)
        _detailPlayerState.value = DetailPlayerUiState(
            slug = slug,
            subjectId = subjectId,
            title = title,
            posterUrl = posterUrl,
            isSeries = guessedSeries,
            selectedSeason = if (guessedSeries) 1 else 0,
            selectedEpisode = if (guessedSeries) 1 else 0,
            isLoadingDetail = true
        )

        // Observe watchlist
        viewModelScope.launch {
            repository.isWatchlisted(subjectId).collectLatest { isWatchlisted ->
                _detailPlayerState.update { it.copy(isWatchlisted = isWatchlisted) }
            }
        }

        // Fetch detail
        viewModelScope.launch {
            val savedHistory = repository.getHistoryItem(subjectId)
            val prevPos = savedHistory?.positionMs ?: 0L
            val prevSe = savedHistory?.season ?: 1
            val prevEp = savedHistory?.episode ?: 1

            repository.getDetail(slug)
                .onSuccess { detailRes ->
                    val fetchedSubject = detailRes.data?.subject
                    val fetchedTitle = fetchedSubject?.title
                    // The list payloads omit country/language, so the detail response is
                    // the first point where a Nollywood title can prove itself.
                    val blocked = NollywoodFilter.isBlockedId(fetchedSubject?.subjectId) ||
                        NollywoodFilter.isBlockedTitle(fetchedTitle) ||
                        NollywoodFilter.isBlockedGenre(fetchedSubject?.genre) ||
                        NollywoodFilter.isBlockedCountry(fetchedSubject?.countryName)
                    if (blocked) {
                        NollywoodFilter.learn(fetchedSubject?.subjectId ?: subjectId)
                        closeBlockedDetail(fetchedTitle ?: title)
                        return@onSuccess
                    }
                    // Adult titles can look ordinary in list payloads (hentai is often
                    // just "Anime"), so the detail flag is the authoritative backstop.
                    if (!_isAdultEnabled.value && AdultFilter.isAdultDetail(fetchedSubject)) {
                        AdultFilter.learn(fetchedSubject?.subjectId ?: subjectId)
                        closeBlockedDetail(fetchedTitle ?: title)
                        return@onSuccess
                    }

                    val subjectType = detailRes.data?.subject?.subjectType
                    val rawSeasons = detailRes.data?.resource?.seasons.orEmpty()
                    // CRITICAL: A title is a series ONLY if subjectType == 2 OR (subjectType != 1 && rawSeasons has se > 0 and maxEp > 0)
                    val isMovie = subjectType == 1 || (!guessedSeries && subjectType != 2 && rawSeasons.all { it.se == 0 })
                    val isSeries = !isMovie && (subjectType == 2 || guessedSeries || rawSeasons.any { it.se > 0 && it.maxEp > 0 })

                    val validSeasons = if (isSeries) rawSeasons.filter { it.se > 0 && it.maxEp > 0 } else emptyList()
                    val actualTitle = detailRes.data?.subject?.title ?: title

                    val actualSe = if (isSeries) {
                        if (validSeasons.any { it.se == prevSe }) prevSe else (validSeasons.firstOrNull()?.se ?: 1)
                    } else 0
                    val actualEp = if (isSeries) prevEp.coerceAtLeast(1) else 0

                    val fetchedCover = detailRes.data?.subject?.cover?.url
                    val effectivePoster = posterUrl?.takeIf { it.isNotBlank() } ?: fetchedCover
                    if (!effectivePoster.isNullOrBlank()) {
                        viewModelScope.launch {
                            repository.updateWatchlistPosterIfMissing(subjectId, effectivePoster)
                            repository.updateHistoryPosterIfMissing(subjectId, effectivePoster)
                        }
                    }

                    val startPos = repository.getEpisodeProgress(subjectId, actualSe, actualEp, isSeries)
                    val session = PlaybackSession(
                        subjectId = subjectId,
                        season = actualSe,
                        episode = actualEp,
                        startPositionMs = startPos,
                        isSeries = isSeries
                    )

                    _detailPlayerState.update {
                        it.copy(
                            detail = detailRes,
                            title = actualTitle,
                            posterUrl = effectivePoster,
                            isSeries = isSeries,
                            availableSeasons = validSeasons,
                            selectedSeason = actualSe,
                            selectedEpisode = actualEp,
                            isLoadingDetail = false,
                            session = session
                        )
                    }

                    // Pre-fetch captions so available subtitle languages (e.g. Hindi, English) are known immediately
                    viewModelScope.launch {
                        repository.getCaptions(subjectId, slug, season = actualSe, episode = actualEp, isSeries = isSeries)
                            .onSuccess { capRes ->
                                val caps = capRes.captions.orEmpty()
                                _detailPlayerState.update { it.copy(captions = caps) }
                            }
                    }
                }
                .onFailure { error ->
                    _detailPlayerState.update {
                        it.copy(
                            isLoadingDetail = false,
                            streamError = error.localizedMessage
                        )
                    }
                }
        }
    }

    fun playItem(
        slug: String,
        subjectId: String,
        season: Int = 0,
        episode: Int = 0,
        resumePositionMs: Long? = null
    ) {
        viewModelScope.launch {
            val current = _detailPlayerState.value
            val isSeries = current.isSeries

            if (NollywoodFilter.isBlockedId(subjectId) || NollywoodFilter.isBlockedTitle(current.title)) {
                NollywoodFilter.learn(subjectId)
                closeBlockedDetail(current.title)
                return@launch
            }
            if (!_isAdultEnabled.value &&
                (AdultFilter.isAdultDetail(current.detail?.data?.subject) ||
                    AdultFilter.isAdultId(subjectId) ||
                    AdultFilter.isAdultTitle(current.title))
            ) {
                AdultFilter.learn(subjectId)
                closeBlockedDetail(current.title)
                return@launch
            }

            // Flush old session position under its own key before starting new session!
            val oldSession = current.session
            val player = playerInstance
            if (oldSession != null && player != null && player.duration > 0 && player.currentPosition > 0) {
                repository.saveWatchProgress(
                    subjectId = oldSession.subjectId,
                    slug = current.slug ?: slug,
                    title = current.title,
                    posterUrl = current.posterUrl,
                    season = oldSession.season,
                    episode = oldSession.episode,
                    positionMs = player.currentPosition,
                    durationMs = player.duration,
                    type = if (oldSession.isSeries) "tv" else "movie"
                )
            }

            // Stop player & clear media items to isolate sessions
            playerInstance?.stop()
            playerInstance?.clearMediaItems()

            val actualSe = if (isSeries) season.coerceAtLeast(1) else 0
            val actualEp = if (isSeries) episode.coerceAtLeast(1) else 0

            val initialPos = resumePositionMs ?: repository.getEpisodeProgress(subjectId, actualSe, actualEp, isSeries)
            val newSession = PlaybackSession(
                subjectId = subjectId,
                season = actualSe,
                episode = actualEp,
                startPositionMs = initialPos,
                isSeries = isSeries
            )

            _detailPlayerState.update {
                it.copy(
                    slug = slug,
                    subjectId = subjectId,
                    selectedSeason = actualSe,
                    selectedEpisode = actualEp,
                    isPlaying = true,
                    isLoadingStream = true,
                    streamError = null,
                    session = newSession
                )
            }

            // Fetch stream and captions in parallel
            val streamResult = repository.getStream(subjectId, slug, actualSe, actualEp, isSeries = isSeries)
            val captionsResult = repository.getCaptions(subjectId, slug, actualSe, actualEp, isSeries = isSeries)

            streamResult.onSuccess { streamRes ->
                val caps = captionsResult.getOrNull()?.captions.orEmpty()
                if (streamRes.sources.isNotEmpty()) {
                    _detailPlayerState.update {
                        it.copy(
                            streamResponse = streamRes,
                            captions = caps,
                            isLoadingStream = false,
                            streamError = null
                        )
                    }
                } else {
                    _detailPlayerState.update {
                        it.copy(
                            streamResponse = streamRes,
                            captions = caps,
                            isLoadingStream = false,
                            streamError = streamRes.note ?: "No stream sources available for this title."
                        )
                    }
                }
            }.onFailure { err ->
                _detailPlayerState.update {
                    it.copy(
                        isLoadingStream = false,
                        streamError = err.localizedMessage ?: "Failed to fetch stream link."
                    )
                }
            }
        }
    }

    suspend fun getStreamForDownload(subjectId: String, slug: String, season: Int, episode: Int): List<StreamSource> {
        if (NollywoodFilter.isBlockedId(subjectId) || NollywoodFilter.isBlockedTitle(_detailPlayerState.value.title)) {
            NollywoodFilter.learn(subjectId)
            noticeBlocked(_detailPlayerState.value.title)
            return emptyList()
        }
        if (!_isAdultEnabled.value &&
            (AdultFilter.isAdultDetail(_detailPlayerState.value.detail?.data?.subject) ||
                AdultFilter.isAdultId(subjectId) ||
                AdultFilter.isAdultTitle(_detailPlayerState.value.title))
        ) {
            AdultFilter.learn(subjectId)
            noticeBlocked(_detailPlayerState.value.title)
            return emptyList()
        }
        val isSeries = _detailPlayerState.value.isSeries
        val s = if (isSeries) season.coerceAtLeast(1) else 0
        val e = if (isSeries) episode.coerceAtLeast(1) else 0
        return repository.getStream(subjectId, slug, s, e, isSeries = isSeries).getOrNull()?.sources.orEmpty()
    }

    fun selectEpisode(season: Int, episode: Int) {
        val s = _detailPlayerState.value
        val slug = s.slug ?: return
        val subjectId = s.subjectId ?: return
        playItem(slug, subjectId, season, episode, resumePositionMs = 0L)
    }

    fun resetEpisodeProgress(subjectId: String, season: Int, episode: Int, isSeries: Boolean) {
        viewModelScope.launch {
            repository.resetEpisodeProgress(subjectId, season, episode, isSeries)
        }
    }

    fun saveProgress(posMs: Long, durMs: Long) {
        val s = _detailPlayerState.value
        val subId = s.session?.subjectId ?: s.subjectId ?: return
        val slug = s.slug ?: return
        val title = s.title.ifBlank { s.detail?.data?.subject?.title ?: "Movie" }
        val poster = s.posterUrl?.takeIf { it.isNotBlank() } ?: s.detail?.data?.subject?.cover?.url
        val type = if (s.isSeries) "tv" else "movie"
        val season = s.session?.season ?: s.selectedSeason
        val episode = s.session?.episode ?: s.selectedEpisode

        viewModelScope.launch {
            repository.saveWatchProgress(
                subjectId = subId,
                slug = slug,
                title = title,
                posterUrl = poster,
                season = season,
                episode = episode,
                positionMs = posMs,
                durationMs = durMs,
                type = type
            )
        }
    }

    fun toggleWatchlist() {
        val s = _detailPlayerState.value
        val subId = s.subjectId ?: return
        val slug = s.slug ?: return
        val subject = s.detail?.data?.subject
        val title = s.title.ifBlank { subject?.title ?: "Movie" }
        val poster = s.posterUrl?.takeIf { it.isNotBlank() } ?: subject?.cover?.url
        val rating = subject?.imdbRatingValue
        val year = subject?.releaseDate?.take(4)
        val genre = subject?.genre
        val type = if (s.isSeries) "tv" else "movie"

        viewModelScope.launch {
            repository.toggleWatchlist(
                subjectId = subId,
                slug = slug,
                title = title,
                posterUrl = poster,
                rating = rating,
                year = year,
                genre = genre,
                type = type,
                currentlyWatchlisted = s.isWatchlisted
            )
        }
    }

    fun deleteHistory(subjectId: String) {
        viewModelScope.launch {
            repository.deleteHistory(subjectId)
        }
    }

    fun clearAllHistory() {
        viewModelScope.launch {
            repository.clearHistory()
        }
    }

    fun closePlayer() {
        playerInstance?.pause()
        playerInstance?.stop()
        _detailPlayerState.update { it.copy(isPlaying = false) }
    }

    fun closeDetail() {
        playerInstance?.pause()
        playerInstance?.stop()
        _detailPlayerState.value = DetailPlayerUiState()
    }

    override fun onCleared() {
        super.onCleared()
        playerInstance?.release()
        playerInstance = null
    }
}
