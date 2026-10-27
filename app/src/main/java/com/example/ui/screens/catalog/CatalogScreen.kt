package com.example.ui.screens.catalog

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import com.example.data.model.MovieItem
import com.example.ui.CatalogType
import com.example.ui.CatalogUiState
import com.example.util.RegionalCategoryConfig
import com.example.ui.components.GlassChip
import com.example.ui.components.MoviePosterCard
import com.example.ui.theme.LocalHighContrast
import com.example.ui.theme.selectableColors


private val GENRES = listOf(
    "All", "Action", "Drama", "Comedy", "Sci-Fi", "Horror",
    "Thriller", "Adventure", "Romance", "Fantasy", "Crime", "Mystery", "Animation"
)

private val SORTS = listOf(
    "foryou"  to "For You",
    "hottest" to "Trending",
    "latest"  to "Latest",
    "rating"  to "Top Rated"
)

private data class ActiveFilterChip(
    val key: String,
    val label: String,
    val onRemove: () -> Unit
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CatalogScreen(
    uiState: CatalogUiState,
    onTypeSelected: (CatalogType) -> Unit,
    onPresetSelected: (String) -> Unit,
    onYearSelected: (String?) -> Unit,
    onGenreSelected: (String) -> Unit,
    onAdultsOnlyChanged: (Boolean) -> Unit,
    onSortSelected: (String) -> Unit,
    onMovieClick: (MovieItem) -> Unit,
    onLoadMore: () -> Unit,
    onResetFilters: () -> Unit = {},
    onRetry: () -> Unit = {},
    isAdultEnabled: Boolean = false,
    modifier: Modifier = Modifier
) {
    val gridState = rememberLazyGridState()
    val coroutineScope = rememberCoroutineScope()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var showFilterSheet by rememberSaveable { mutableStateOf(false) }

    val selectedPreset = RegionalCategoryConfig.presetById(uiState.selectedPresetId)
    val isAnimation = uiState.type == CatalogType.ANIMATION
    val isHighContrast = LocalHighContrast.current

    fun applyFilter(block: () -> Unit) {
        block()
        coroutineScope.launch { gridState.scrollToItem(0) }
    }

    val sortLabel = SORTS.firstOrNull { it.first == uiState.selectedSort }?.second
        ?: uiState.selectedSort
    val activeFilters: List<ActiveFilterChip> = buildList {
        if (selectedPreset.id != RegionalCategoryConfig.PRESET_ALL) {
            add(
                ActiveFilterChip(key = "preset", label = selectedPreset.label) {
                    applyFilter { onPresetSelected(RegionalCategoryConfig.PRESET_ALL) }
                }
            )
        }
        if (uiState.adultsOnly) {
            add(
                ActiveFilterChip(key = "adult", label = "Adult 18+") {
                    applyFilter { onAdultsOnlyChanged(false) }
                }
            )
        }
        if (!isAnimation && uiState.selectedYear != null) {
            add(
                ActiveFilterChip(key = "year", label = uiState.selectedYear) {
                    applyFilter { onYearSelected(null) }
                }
            )
        }
        if (!uiState.adultsOnly && selectedPreset.genre == null && uiState.selectedGenre != "All") {
            add(
                ActiveFilterChip(key = "genre", label = uiState.selectedGenre) {
                    applyFilter { onGenreSelected("All") }
                }
            )
        }
        if (uiState.selectedSort != "foryou") {
            add(
                ActiveFilterChip(key = "sort", label = sortLabel) {
                    applyFilter { onSortSelected("foryou") }
                }
            )
        }
    }

    // Load more when reaching bottom of catalog grid
    val shouldLoadMore by remember {
        derivedStateOf {
            val totalItems = gridState.layoutInfo.totalItemsCount
            val lastVisible = gridState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            totalItems > 0 && lastVisible >= totalItems - 6 && !uiState.isLoading && uiState.hasMore
        }
    }

    LaunchedEffect(shouldLoadMore) {
        if (shouldLoadMore) {
            onLoadMore()
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {

        // ── Floating glass page header ─────────────────────────────────────────
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .clip(RoundedCornerShape(bottomStart = 0.dp, bottomEnd = 0.dp))
                .background(
                    Brush.verticalGradient(
                        listOf(
                            MaterialTheme.colorScheme.background,
                            MaterialTheme.colorScheme.background.copy(alpha = 0.92f)
                        )
                    )
                )
                .padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 10.dp)
        ) {
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = "Explore & Browse",
                    style = MaterialTheme.typography.headlineSmall.copy(
                        fontWeight = FontWeight.ExtraBold,
                        color = MaterialTheme.colorScheme.onBackground
                    )
                )

                Spacer(modifier = Modifier.height(12.dp))

                // ── Segmented Switcher: Movies | TV Series | Animation ──
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .background(MaterialTheme.colorScheme.surfaceContainer)
                        .border(
                            width = if (isHighContrast) 1.5.dp else 0.5.dp,
                            color = if (isHighContrast) MaterialTheme.colorScheme.outline else MaterialTheme.colorScheme.outlineVariant,
                            shape = RoundedCornerShape(14.dp)
                        )
                        .padding(3.dp),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    CatalogType.entries.forEach { catalogType ->
                        val isSelected = uiState.type == catalogType
                        val selColors = selectableColors(isSelected)
                        val shape = RoundedCornerShape(11.dp)
                        val containerColor = if (isSelected) selColors.container else Color.Transparent
                        val contentColor = selColors.content
                        val borderWidth = if (isHighContrast) 1.5.dp else if (isSelected) 0.5.dp else 0.dp
                        val border = if (borderWidth > 0.dp) BorderStroke(borderWidth, selColors.border) else null

                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .clip(shape)
                                .background(containerColor)
                                .then(if (border != null) Modifier.border(border, shape) else Modifier)
                                .clickable {
                                    onTypeSelected(catalogType)
                                    coroutineScope.launch { gridState.scrollToItem(0) }
                                }
                                .padding(vertical = 9.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.Center
                            ) {
                                if (isSelected && isHighContrast) {
                                    Icon(
                                        imageVector = Icons.Default.Check,
                                        contentDescription = null,
                                        tint = contentColor,
                                        modifier = Modifier.size(14.dp)
                                    )
                                    Spacer(Modifier.width(4.dp))
                                }
                                Text(
                                    text = catalogType.label,
                                    style = MaterialTheme.typography.bodyMedium.copy(
                                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                        color = contentColor,
                                        fontSize = 12.5.sp
                                    )
                                )
                            }
                        }
                    }
                }
            }
        }

        // ── Filter bar: open sheet + removable active filters ─────────────────
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                GlassChip(
                    label = "Filters",
                    selected = false,
                    icon = Icons.Default.FilterList,
                    onClick = { showFilterSheet = true },
                    modifier = Modifier.testTag("open_filters")
                )

                Spacer(modifier = Modifier.weight(1f))

                if (activeFilters.isNotEmpty()) {
                    TextButton(
                        onClick = { applyFilter(onResetFilters) },
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                    ) {
                        Text(
                            text = "Reset",
                            fontWeight = FontWeight.Bold,
                            fontSize = 12.sp
                        )
                    }
                }
            }

            if (activeFilters.isNotEmpty()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    activeFilters.forEach { filter ->
                        GlassChip(
                            label = filter.label,
                            selected = true,
                            onClick = filter.onRemove,
                            trailingIcon = Icons.Default.Close,
                            trailingIconContentDescription = "Remove ${filter.label} filter",
                            onTrailingIconClick = filter.onRemove,
                            modifier = Modifier.testTag("active_filter_${filter.key}")
                        )
                    }
                }
            }
        }

        // ── Movie Grid ────────────────────────────────────────────────────────
        if (uiState.items.isEmpty() && uiState.isLoading) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator(
                    color = MaterialTheme.colorScheme.primary,
                    strokeWidth = 2.5.dp,
                    modifier = Modifier.size(40.dp)
                )
            }
        } else if (uiState.items.isEmpty() && uiState.errorMessage != null) {
            // Error panel
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(32.dp),
                contentAlignment = Alignment.Center
            ) {
                Card(
                    shape = RoundedCornerShape(20.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)
                ) {
                    Column(
                        modifier = Modifier.padding(28.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = "Unable to Load",
                            style = MaterialTheme.typography.titleMedium.copy(
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = uiState.errorMessage,
                            style = MaterialTheme.typography.bodySmall.copy(
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            ),
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Button(
                            onClick = onRetry,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.primary,
                                contentColor = MaterialTheme.colorScheme.onPrimary
                            )
                        ) {
                            Text("Retry", fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        } else if (uiState.items.isEmpty() && !uiState.isLoading) {
            // Empty state
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(32.dp),
                contentAlignment = Alignment.Center
            ) {
                Card(
                    shape = RoundedCornerShape(20.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)
                ) {
                    Column(
                        modifier = Modifier.padding(28.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = if (uiState.adultsOnly) "No Adult Titles" else "Nothing Found",
                            style = MaterialTheme.typography.titleMedium.copy(
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        val adultSortMismatch =
                            uiState.adultsOnly && uiState.selectedSort != "foryou"
                        Text(
                            text = when {
                                adultSortMismatch ->
                                    "No adult titles for \"$sortLabel\" sorting. Adult titles only appear under For You."
                                uiState.adultsOnly ->
                                    "No adult titles match this country or year. Try another country or year."
                                else -> "Nothing found for this filter."
                            },
                            style = MaterialTheme.typography.bodySmall.copy(
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            ),
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        if (adultSortMismatch) {
                            Button(
                                onClick = { applyFilter { onSortSelected("foryou") } },
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = MaterialTheme.colorScheme.primary,
                                    contentColor = MaterialTheme.colorScheme.onPrimary
                                )
                            ) {
                                Text("Switch to For You", fontWeight = FontWeight.Bold)
                            }
                            TextButton(onClick = onResetFilters) {
                                Text("Reset Filters")
                            }
                        } else {
                            OutlinedButton(onClick = onResetFilters) {
                                Text("Reset Filters", fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            }
        } else {
            val uniqueItems = remember(uiState.items) {
                uiState.items.distinctBy { "${it.safeSlug}_${it.subjectId}" }
            }
            LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 110.dp),
                state = gridState,
                contentPadding = PaddingValues(
                    start = 16.dp,
                    end = 16.dp,
                    top = 8.dp,
                    bottom = 96.dp
                ),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
                modifier = Modifier
                    .fillMaxSize()
                    .testTag("catalog_grid")
            ) {
                items(
                    count = uniqueItems.size,
                    key = { index ->
                        "${uniqueItems[index].safeSlug}_${uniqueItems[index].subjectId}_$index"
                    }
                ) { index ->
                    val movie = uniqueItems[index]
                    MoviePosterCard(
                        movie = movie,
                        onClick = { onMovieClick(movie) },
                        cardWidth = 110.dp,
                        cardHeight = 165.dp,
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                // Bottom pagination spinner
                if (uiState.isLoading && uiState.items.isNotEmpty()) {
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            CircularProgressIndicator(
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(28.dp),
                                strokeWidth = 2.dp
                            )
                        }
                    }
                }
            }
        }
    }

    // ── Filters sheet ─────────────────────────────────────────────────────────
    if (showFilterSheet) {
        ModalBottomSheet(
            onDismissRequest = { showFilterSheet = false },
            sheetState = sheetState,
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
            contentColor = MaterialTheme.colorScheme.onSurface
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .navigationBarsPadding()
                    .padding(start = 16.dp, end = 16.dp, bottom = 24.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Filters",
                        style = MaterialTheme.typography.headlineSmall.copy(
                            fontWeight = FontWeight.ExtraBold
                        ),
                        modifier = Modifier.weight(1f)
                    )
                    if (activeFilters.isNotEmpty()) {
                        TextButton(onClick = { applyFilter(onResetFilters) }) {
                            Text("Reset all", fontWeight = FontWeight.Bold)
                        }
                    }
                }

                Text(
                    text = "Changes apply as you select them.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                if (uiState.adultsOnly) {
                    FilterSection(
                        title = "Sort",
                        description = "Adult titles only return results with For You ordering."
                    ) {
                        GlassChip(
                            label = "For You",
                            selected = true,
                            onClick = { applyFilter { onSortSelected("foryou") } }
                        )
                    }
                } else {
                    FilterSection(title = "Sort") {
                        FilterChipRow {
                            SORTS.forEach { (sortKey, label) ->
                                GlassChip(
                                    label = label,
                                    selected = uiState.selectedSort == sortKey,
                                    onClick = { applyFilter { onSortSelected(sortKey) } }
                                )
                            }
                        }
                    }
                }

                if (isAnimation) {
                    Text(
                        text = "Country and year filters are not available for Animation.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 20.dp)
                    )
                } else {
                    if (isAdultEnabled) {
                        FilterSection(
                            title = "Adult (18+)",
                            description = "Only titles tagged Adult. Country and year still apply; sort is fixed to For You."
                        ) {
                            GlassChip(
                                label = "Adult only",
                                selected = uiState.adultsOnly,
                                onClick = { applyFilter { onAdultsOnlyChanged(!uiState.adultsOnly) } }
                            )
                            if (uiState.adultsOnly) {
                                Spacer(modifier = Modifier.height(6.dp))
                                Text(
                                    text = "Adult is on. Pick a country below to narrow the list.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                    }

                    FilterSection(
                        title = "Country / Region",
                        description = if (uiState.adultsOnly) {
                            "Country of production. Adult titles can be narrowed by country here."
                        } else {
                            "Country of production. Not a language filter."
                        }
                    ) {
                        FilterChipRow {
                            RegionalCategoryConfig.REGION_PRESETS.forEach { preset ->
                                GlassChip(
                                    label = preset.label,
                                    selected = selectedPreset.id == preset.id,
                                    onClick = {
                                        // Changing country keeps the Adult lens active.
                                        applyFilter { onPresetSelected(preset.id) }
                                    }
                                )
                            }
                        }
                    }

                    if (!uiState.adultsOnly) {
                        FilterSection(title = "Genre") {
                            FilterChipRow {
                                GENRES.forEach { genre ->
                                    GlassChip(
                                        label = genre,
                                        selected = uiState.selectedGenre == genre,
                                        onClick = { applyFilter { onGenreSelected(genre) } }
                                    )
                                }
                            }
                        }
                    }

                    FilterSection(
                        title = "Year",
                        description = "Exact release year."
                    ) {
                        FilterChipRow {
                            GlassChip(
                                label = "Any year",
                                selected = uiState.selectedYear == null,
                                onClick = { applyFilter { onYearSelected(null) } }
                            )
                            RegionalCategoryConfig.YEAR_OPTIONS.forEach { year ->
                                GlassChip(
                                    label = year,
                                    selected = uiState.selectedYear == year,
                                    onClick = { applyFilter { onYearSelected(year) } }
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun FilterSection(
    title: String,
    description: String? = null,
    content: @Composable () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 20.dp)
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
            color = MaterialTheme.colorScheme.onSurface
        )
        if (description != null) {
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(modifier = Modifier.height(8.dp))
        content()
    }
}

@Composable
private fun FilterChipRow(content: @Composable RowScope.() -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        content = content
    )
}
