package com.example.ui.screens.detail

import android.content.Intent
import android.content.res.Configuration
import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.os.Build
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.exoplayer.ExoPlayer
import coil.compose.AsyncImage
import coil.size.Scale
import coil.request.ImageRequest
import com.example.data.model.StreamSource
import com.example.ui.DetailPlayerUiState
import com.example.ui.components.*
import com.example.ui.theme.LocalHighContrast
import com.example.ui.theme.selectableColors
import com.example.ui.player.VideoPlayer
import com.example.ui.theme.*
import com.example.util.DownloadHelper
import kotlinx.coroutines.launch

@Composable
fun DetailScreen(
    uiState: DetailPlayerUiState,
    exoPlayer: ExoPlayer,
    onBackClick: () -> Unit,
    onPlayClick: (season: Int, episode: Int) -> Unit,
    onSelectEpisode: (season: Int, episode: Int) -> Unit,
    onToggleWatchlist: () -> Unit,
    onProgressUpdate: (pos: Long, dur: Long) -> Unit,
    onStartOver: (subjectId: String, season: Int, episode: Int, isSeries: Boolean) -> Unit = { _, _, _, _ -> },
    onFetchStreamForDownload: suspend (season: Int, episode: Int) -> List<StreamSource>,
    onRefreshStream: (resumePos: Long) -> Unit = {},
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val activity = remember(context) { context.findActivity() }
    val configuration = LocalConfiguration.current
    val isLandscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
    val subject = uiState.detail?.data?.subject
    val scope = rememberCoroutineScope()

    val isInPip = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
        activity?.isInPictureInPictureMode == true
    } else false

    // When running in Floating Screen (Picture-in-Picture), render pure video only
    if (isInPip && uiState.isPlaying) {
        VideoPlayer(
            exoPlayer = exoPlayer,
            title = uiState.title,
            subtitleInfo = null,
            sources = uiState.streamResponse?.sources.orEmpty(),
            captions = uiState.captions,
            session = uiState.session,
            isSeries = uiState.isSeries,
            onProgressUpdate = onProgressUpdate,
            onBackClick = onBackClick,
            onRefreshStream = onRefreshStream,
            modifier = Modifier.fillMaxSize()
        )
        return
    }

    var showDownloadDialog by remember { mutableStateOf(false) }
    var downloadSources by remember { mutableStateOf<List<StreamSource>>(emptyList()) }
    var isFetchingDownload by remember { mutableStateOf(false) }
    var downloadTargetEpisode by remember { mutableStateOf<Pair<Int, Int>?>(null) }
    var showJumpDialog by remember { mutableStateOf(false) }
    var jumpInputText by remember { mutableStateOf("") }

    val currentSeason = uiState.availableSeasons.firstOrNull { it.se == uiState.selectedSeason }
        ?: uiState.availableSeasons.firstOrNull()
    val episodeCount = currentSeason?.maxEp?.coerceAtLeast(1) ?: 1
    val chunkSize = 30
    val numChunks = (episodeCount + chunkSize - 1) / chunkSize
    var selectedChunkIndex by remember(uiState.selectedSeason, episodeCount) {
        val initChunk = (uiState.selectedEpisode - 1).coerceAtLeast(0) / chunkSize
        mutableIntStateOf(initChunk.coerceIn(0, (numChunks - 1).coerceAtLeast(0)))
    }

    LaunchedEffect(uiState.selectedEpisode, episodeCount) {
        val epChunk = (uiState.selectedEpisode - 1).coerceAtLeast(0) / chunkSize
        if (epChunk in 0 until numChunks && epChunk != selectedChunkIndex) {
            selectedChunkIndex = epChunk
        }
    }

    val activeEpisodes = remember(selectedChunkIndex, episodeCount) {
        val startEp = selectedChunkIndex * chunkSize + 1
        val endEp = minOf((selectedChunkIndex + 1) * chunkSize, episodeCount)
        (startEp..endEp).toList()
    }

    fun triggerDownload(season: Int, episode: Int) {
        isFetchingDownload = true
        scope.launch {
            val fetched = onFetchStreamForDownload(season, episode)
            isFetchingDownload = false
            if (fetched.isNotEmpty()) {
                downloadSources = fetched
                downloadTargetEpisode = Pair(season, episode)
                showDownloadDialog = true
            } else {
                val currentSources = uiState.streamResponse?.sources
                if (!currentSources.isNullOrEmpty() &&
                    uiState.selectedSeason == season &&
                    uiState.selectedEpisode == episode
                ) {
                    downloadSources = currentSources
                    downloadTargetEpisode = Pair(season, episode)
                    showDownloadDialog = true
                } else {
                    android.widget.Toast.makeText(context, "No download streams found.", android.widget.Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // ── Video Player ─────────────────────────────────────────────────
            if (uiState.isPlaying) {
                val playerModifier = if (isLandscape) Modifier.fillMaxSize()
                else Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .aspectRatio(16f / 9f)

                Box(modifier = playerModifier.background(Color.Black)) { // legibility-ok: player container
                    when {
                        uiState.isLoadingStream -> {
                            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    CircularProgressIndicator(
                                        color = MaterialTheme.colorScheme.primary,
                                        strokeWidth = 2.5.dp,
                                        modifier = Modifier.size(36.dp)
                                    )
                                    Spacer(Modifier.height(10.dp))
                                    Text("Loading stream...", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
                                }
                            }
                        }
                        uiState.streamError != null && uiState.streamResponse?.sources.isNullOrEmpty() -> {
                            Box(
                                Modifier.fillMaxSize().padding(20.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                LiquidGlassCard(cornerRadius = 20.dp) {
                                    Column(
                                        Modifier.padding(24.dp),
                                        horizontalAlignment = Alignment.CenterHorizontally
                                    ) {
                                        Icon(Icons.Default.ErrorOutline, null, tint = MaterialTheme.colorScheme.tertiary, modifier = Modifier.size(32.dp))
                                        Spacer(Modifier.height(8.dp))
                                        Text("Stream Unavailable", color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.Bold)
                                        Spacer(Modifier.height(6.dp))
                                        Text(uiState.streamError ?: "No stream found.", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                                        Spacer(Modifier.height(14.dp))
                                        LiquidGlassButton(
                                            text = "Retry",
                                            onClick = { onPlayClick(uiState.selectedSeason, uiState.selectedEpisode) },
                                            icon = Icons.Default.Refresh,
                                            modifier = Modifier.width(120.dp)
                                        )
                                    }
                                }
                            }
                        }
                        else -> {
                            val currentSeasonForPlayer = uiState.availableSeasons.firstOrNull { it.se == uiState.selectedSeason }
                            val maxEp = currentSeasonForPlayer?.maxEp ?: 1
                            val subtitleText = if (uiState.isSeries) {
                                "Season ${uiState.selectedSeason} • Ep ${uiState.selectedEpisode}"
                            } else {
                                val year = subject?.releaseDate?.take(4)
                                val dur = subject?.duration?.let { "${it / 60}m" }
                                listOfNotNull(year, dur, "Movie").joinToString(" • ")
                            }
                            VideoPlayer(
                                exoPlayer = exoPlayer,
                                title = uiState.title,
                                subtitleInfo = subtitleText,
                                sources = uiState.streamResponse?.sources.orEmpty(),
                                captions = uiState.captions,
                                session = uiState.session,
                                isSeries = uiState.isSeries,
                                onProgressUpdate = onProgressUpdate,
                                onStartOver = onStartOver,
                                onBackClick = onBackClick,
                                onRefreshStream = onRefreshStream,
                                hasPreviousEpisode = uiState.isSeries && uiState.selectedEpisode > 1,
                                hasNextEpisode = uiState.isSeries && uiState.selectedEpisode < maxEp,
                                onPreviousEpisode = { onSelectEpisode(uiState.selectedSeason, uiState.selectedEpisode - 1) },
                                onNextEpisode = { onSelectEpisode(uiState.selectedSeason, uiState.selectedEpisode + 1) },
                                modifier = Modifier.fillMaxSize()
                            )
                        }
                    }
                }
            } else {
                // ── Backdrop Header ──────────────────────────────────────────
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(if (isLandscape) 180.dp else 280.dp)
                        .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                ) {
                    // Dimmed artwork fills the header while the full poster stays visible
                    AsyncImage(
                        model = ImageRequest.Builder(context)
                            .data(subject?.cover?.url)
                            .size(32, 32)
                            .scale(Scale.FILL)
                            .crossfade(400)
                            .build(),
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(Color.Black.copy(alpha = 0.55f)) // legibility-ok: backdrop ambient
                    )
                    AsyncImage(
                        model = ImageRequest.Builder(context)
                            .data(subject?.cover?.url)
                            .scale(Scale.FIT)
                            .crossfade(400)
                            .build(),
                        contentDescription = uiState.title,
                        contentScale = ContentScale.Fit,
                        alignment = Alignment.Center,
                        modifier = Modifier.fillMaxSize()
                    )

                    // Gradient overlays
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(
                                Brush.verticalGradient(
                                    colorStops = arrayOf(
                                        0f    to Color(0x60000000), // legibility-ok: backdrop image scrim
                                        0.5f  to Color.Transparent,
                                        0.88f to MaterialTheme.colorScheme.background.copy(0.92f),
                                        1f    to MaterialTheme.colorScheme.background
                                    )
                                )
                            )
                    )

                    // Top action bar
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .statusBarsPadding()
                            .padding(horizontal = 14.dp, vertical = 8.dp)
                            .align(Alignment.TopCenter),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        GlassIconButton(
                            icon = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                            onClick = onBackClick,
                            modifier = Modifier.testTag("detail_back_button")
                        )

                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            GlassIconButton(
                                icon = if (uiState.isWatchlisted) Icons.Default.Bookmark else Icons.Default.BookmarkBorder,
                                contentDescription = "Watchlist",
                                onClick = onToggleWatchlist,
                                tint = if (uiState.isWatchlisted) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.testTag("detail_watchlist_button")
                            )
                            GlassIconButton(
                                icon = Icons.Default.Share,
                                contentDescription = "Share",
                                onClick = {
                                    val intent = Intent().apply {
                                        action = Intent.ACTION_SEND
                                        putExtra(Intent.EXTRA_TEXT, "Watch ${uiState.title} on Zippy!")
                                        type = "text/plain"
                                    }
                                    context.startActivity(Intent.createChooser(intent, "Share"))
                                }
                            )
                        }
                    }

                    // Play FAB (Brand Gradient with icon)
                    Box(
                        modifier = Modifier
                            .size(62.dp)
                            .align(Alignment.Center)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primary)
                            .border(1.dp, MaterialTheme.colorScheme.primary, CircleShape)
                            .clickable(indication = null, interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }) {
                                onPlayClick(
                                    if (uiState.isSeries) uiState.selectedSeason else 0,
                                    if (uiState.isSeries) uiState.selectedEpisode else 0
                                )
                            }
                            .testTag("detail_play_fab"),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(Icons.Default.PlayArrow, null, tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(32.dp))
                    }
                }
            }

            // ── Scrollable Content ─────────────────────────────────────────
            if (!isLandscape || !uiState.isPlaying) {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 18.dp),
                    contentPadding = PaddingValues(top = 12.dp, bottom = 52.dp)
                ) {
                    // Title & meta
                    item {
                        Text(
                            text = uiState.title,
                            color = MaterialTheme.colorScheme.onSurface,
                            fontSize = 22.sp,
                            fontWeight = FontWeight.ExtraBold,
                            lineHeight = 26.sp
                        )
                        Spacer(Modifier.height(8.dp))
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            // Rating
                            val rating = subject?.imdbRatingValue
                            if (!rating.isNullOrBlank()) {
                                RatingBadge(rating = rating)
                            }
                            // Year
                            val year = subject?.releaseDate?.take(4)
                            if (!year.isNullOrBlank()) {
                                Text(year, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
                            }
                            // Type label
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(5.dp))
                                    .background(MaterialTheme.colorScheme.tertiary.copy(0.15f))
                                    .padding(horizontal = 7.dp, vertical = 3.dp)
                            ) {
                                Text(
                                    text = if (uiState.isSeries) "Series" else "Movie",
                                    color = MaterialTheme.colorScheme.tertiary,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                            // Duration
                            subject?.duration?.let { dur ->
                                val mins = dur / 60
                                if (mins > 0) Text("• ${mins}m", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
                            }
                        }

                        subject?.genre?.let { genre ->
                            Spacer(Modifier.height(6.dp))
                            Text(genre, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
                        }

                        Spacer(Modifier.height(16.dp))

                        // Action buttons
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            val startMs = uiState.session?.startPositionMs ?: 0L
                            LiquidGlassButton(
                                text = run {
                                    when {
                                        startMs > 60000L -> {
                                            val mins = (startMs / 1000) / 60
                                            "Resume (${mins}m)"
                                        }
                                        uiState.isSeries -> "Play S${uiState.selectedSeason}:E${uiState.selectedEpisode}"
                                        else -> "Watch Movie"
                                    }
                                },
                                onClick = {
                                    onPlayClick(
                                        if (uiState.isSeries) uiState.selectedSeason else 0,
                                        if (uiState.isSeries) uiState.selectedEpisode else 0
                                    )
                                },
                                icon = Icons.Default.PlayArrow,
                                modifier = Modifier.weight(1f).testTag("detail_watch_button")
                            )

                            // Download button
                            Box(
                                modifier = Modifier
                                    .height(46.dp)
                                    .clip(RoundedCornerShape(999.dp))
                                    .background(MaterialTheme.colorScheme.surfaceContainer)
                                    .clickable(indication = null, interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }) {
                                        triggerDownload(
                                            if (uiState.isSeries) uiState.selectedSeason else 0,
                                            if (uiState.isSeries) uiState.selectedEpisode else 0
                                        )
                                    }
                                    .padding(horizontal = 16.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    if (isFetchingDownload) {
                                        CircularProgressIndicator(color = MaterialTheme.colorScheme.tertiary, strokeWidth = 2.dp, modifier = Modifier.size(16.dp))
                                    } else {
                                        Icon(Icons.Default.Download, null, tint = MaterialTheme.colorScheme.onSurface, modifier = Modifier.size(18.dp))
                                    }
                                    Text("Download", color = MaterialTheme.colorScheme.onSurface, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                                }
                            }
                        }
                        Spacer(Modifier.height(18.dp))
                    }

                    // Synopsis
                    item {
                        LiquidGlassCard(cornerRadius = 16.dp) {
                            Column(modifier = Modifier.padding(16.dp)) {
                                Text("Synopsis", color = MaterialTheme.colorScheme.onSurface, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                                Spacer(Modifier.height(8.dp))
                                Text(
                                    text = subject?.description?.ifBlank { "No synopsis available." } ?: "No synopsis available.",
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    fontSize = 13.sp,
                                    lineHeight = 19.sp
                                )
                            }
                        }
                        Spacer(Modifier.height(16.dp))
                    }

                    // Cast & Crew
                    val stars = uiState.detail?.data?.stars.orEmpty()
                    if (stars.isNotEmpty()) {
                        item {
                            Text("Cast & Crew", color = MaterialTheme.colorScheme.onSurface, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                            Spacer(Modifier.height(10.dp))
                            LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                itemsIndexed(stars, key = { idx, star -> "${star.name ?: "star"}_$idx" }) { _, star ->
                                    Column(
                                        horizontalAlignment = Alignment.CenterHorizontally,
                                        modifier = Modifier.width(72.dp)
                                    ) {
                                        Box(
                                            modifier = Modifier
                                                .size(58.dp)
                                                .clip(CircleShape)
                                                .background(MaterialTheme.colorScheme.surfaceVariant)
                                                .border(0.8.dp, MaterialTheme.colorScheme.outlineVariant, CircleShape)
                                        ) {
                                            AsyncImage(
                                                model = ImageRequest.Builder(context).data(star.avatarUrl).crossfade(true).build(),
                                                contentDescription = star.name,
                                                contentScale = ContentScale.Crop,
                                                modifier = Modifier.fillMaxSize()
                                            )
                                        }
                                        Spacer(Modifier.height(5.dp))
                                        Text(star.name ?: "", color = MaterialTheme.colorScheme.onSurface, fontSize = 10.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                                        Text(star.character ?: "", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 9.sp, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                                    }
                                }
                            }
                            Spacer(Modifier.height(18.dp))
                        }
                    }

                    // Audio & Subtitles
                    item {
                        val hasHindi = remember(uiState.captions) {
                            uiState.captions.any {
                                it.lan == "hi" ||
                                it.lanName?.contains("हिन्दी") == true ||
                                it.lanName?.contains("Hindi", ignoreCase = true) == true
                            }
                        }
                        LiquidGlassCard(cornerRadius = 16.dp) {
                            Column(modifier = Modifier.padding(16.dp)) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Icon(Icons.Default.Subtitles, null, tint = MaterialTheme.colorScheme.onSurface, modifier = Modifier.size(18.dp))
                                        Spacer(Modifier.width(8.dp))
                                        Text("Audio & Subtitles", color = MaterialTheme.colorScheme.onSurface, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                                    }
                                    if (hasHindi) {
                                        Box(
                                            modifier = Modifier
                                                .clip(RoundedCornerShape(6.dp))
                                                .background(MaterialTheme.colorScheme.tertiaryContainer)
                                                .border(0.7.dp, MaterialTheme.colorScheme.tertiary, RoundedCornerShape(6.dp))
                                                .padding(horizontal = 7.dp, vertical = 3.dp)
                                        ) {
                                            Text("🇮🇳 हिन्दी", color = MaterialTheme.colorScheme.onTertiaryContainer, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                                        }
                                    }
                                }
                                Spacer(Modifier.height(8.dp))
                                if (uiState.captions.isNotEmpty()) {
                                    val langNames = uiState.captions.mapNotNull { it.lanName ?: it.lan }.distinct()
                                    Text(
                                        text = "Subtitles in ${langNames.size} languages: ${langNames.take(4).joinToString(", ")}${if (langNames.size > 4) " +${langNames.size - 4} more" else ""}.",
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        fontSize = 12.sp,
                                        lineHeight = 17.sp
                                    )
                                } else {
                                    Text(
                                        text = "Multi-language subtitles available in player.",
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        fontSize = 12.sp
                                    )
                                }
                            }
                        }
                        Spacer(Modifier.height(16.dp))
                    }

                    // Episodes (Series only)
                    if (uiState.isSeries && uiState.availableSeasons.isNotEmpty()) {
                        item {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text("Episodes ($episodeCount)", color = MaterialTheme.colorScheme.onSurface, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                                if (episodeCount > 30) {
                                    Box(
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(999.dp))
                                            .background(MaterialTheme.colorScheme.surfaceContainer)
                                            .clickable(indication = null, interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }) { showJumpDialog = true }
                                            .padding(horizontal = 12.dp, vertical = 5.dp)
                                    ) {
                                        Text("Jump to Ep", color = MaterialTheme.colorScheme.onSurface, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                    }
                                }
                            }
                            Spacer(Modifier.height(10.dp))

                            // Season selector
                            if (uiState.availableSeasons.size > 1) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .horizontalScroll(rememberScrollState()),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    uiState.availableSeasons.forEach { season ->
                                        GlassChip(
                                            label = "Season ${season.se}",
                                            selected = uiState.selectedSeason == season.se,
                                            onClick = {
                                                selectedChunkIndex = 0
                                                onSelectEpisode(season.se, 1)
                                            }
                                        )
                                    }
                                }
                                Spacer(Modifier.height(10.dp))
                            }

                            // Episode chunk selector
                            if (numChunks > 1) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .horizontalScroll(rememberScrollState()),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    for (c in 0 until numChunks) {
                                        val start = c * chunkSize + 1
                                        val end = minOf((c + 1) * chunkSize, episodeCount)
                                        GlassChip(
                                            label = "$start - $end",
                                            selected = selectedChunkIndex == c,
                                            onClick = { selectedChunkIndex = c }
                                        )
                                    }
                                }
                                Spacer(Modifier.height(10.dp))
                            }
                        }

                        // Episode items
                        items(
                            items = activeEpisodes,
                            key = { ep -> "ep_${currentSeason?.se ?: 1}_$ep" }
                        ) { epNum ->
                            val isCurrentEp = uiState.selectedEpisode == epNum && uiState.selectedSeason == currentSeason?.se
                            val isHighContrast = LocalHighContrast.current
                            val selColors = selectableColors(isCurrentEp)
                            val borderWidth = if (isHighContrast) 1.5.dp else if (isCurrentEp) 1.dp else 0.5.dp
                            val shape = RoundedCornerShape(14.dp)

                            Box(
                                 modifier = Modifier
                                     .fillMaxWidth()
                                     .padding(vertical = 4.dp)
                                     .clip(shape)
                                     .background(selColors.container)
                                     .border(borderWidth, selColors.border, shape)
                                     .clickable(indication = null, interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }) {
                                         currentSeason?.let { onSelectEpisode(it.se, epNum) }
                                     }
                                     .padding(horizontal = 14.dp, vertical = 12.dp)
                            ) {
                                Row(
                                     modifier = Modifier.fillMaxWidth(),
                                     verticalAlignment = Alignment.CenterVertically,
                                     horizontalArrangement = Arrangement.SpaceBetween
                                 ) {
                                     Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                                         Box(
                                             modifier = Modifier
                                                 .size(34.dp)
                                                 .clip(CircleShape)
                                                 .background(if (isCurrentEp) selColors.accent else MaterialTheme.colorScheme.surfaceContainerHigh),
                                             contentAlignment = Alignment.Center
                                         ) {
                                             Icon(
                                                 imageVector = if (isCurrentEp && uiState.isPlaying) Icons.Default.GraphicEq else Icons.Default.PlayArrow,
                                                 contentDescription = if (isCurrentEp && uiState.isPlaying) "Playing" else "Play",
                                                 tint = if (isCurrentEp) MaterialTheme.colorScheme.primaryContainer else selColors.content,
                                                 modifier = Modifier.size(18.dp)
                                             )
                                         }
                                         Spacer(Modifier.width(12.dp))
                                         Column {
                                             Text(
                                                 text = "Episode $epNum",
                                                 color = selColors.content,
                                                 fontSize = 14.sp,
                                                 fontWeight = FontWeight.Bold
                                             )
                                             Text(
                                                 text = "Season ${currentSeason?.se ?: 1}",
                                                 color = selColors.secondaryContent,
                                                 fontSize = 11.sp
                                             )
                                         }
                                     }
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        if (isCurrentEp && uiState.isPlaying) {
                                            Text("▶ Playing", color = selColors.accent, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                            Spacer(Modifier.width(8.dp))
                                        }
                                        GlassIconButton(
                                            icon = Icons.Default.Download,
                                            contentDescription = "Download",
                                            onClick = { currentSeason?.let { triggerDownload(it.se, epNum) } },
                                            modifier = Modifier.size(32.dp),
                                            tint = selColors.content
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        // ── Download Dialog ──────────────────────────────────────────────────
        if (showDownloadDialog && downloadSources.isNotEmpty()) {
            AlertDialog(
                onDismissRequest = { showDownloadDialog = false },
                containerColor = MaterialTheme.colorScheme.surface,
                title = { Text("Select Download Quality", color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.Bold) },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        downloadSources.forEach { source ->
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(MaterialTheme.colorScheme.surfaceContainer)
                                    .clickable(indication = null, interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }) {
                                        showDownloadDialog = false
                                        val (s, e) = downloadTargetEpisode ?: Pair(0, 0)
                                        DownloadHelper.downloadVideo(
                                            context = context,
                                            url = source.url,
                                            title = uiState.title,
                                            resolution = source.resolution,
                                            season = if (uiState.isSeries) s else null,
                                            episode = if (uiState.isSeries) e else null
                                        )
                                    }
                                    .padding(12.dp)
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Icon(Icons.Default.DownloadForOffline, null, tint = MaterialTheme.colorScheme.tertiary, modifier = Modifier.size(20.dp))
                                        Spacer(Modifier.width(10.dp))
                                        Text(source.resolution, color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.Bold)
                                    }
                                    source.formattedSize?.let { sz ->
                                        Text(sz, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
                                    }
                                }
                            }
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = { showDownloadDialog = false }) {
                        Text("Cancel", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            )
        }

        // ── Jump to Episode Dialog ────────────────────────────────────────────
        if (showJumpDialog) {
            val currentSeasonForJump = uiState.availableSeasons.firstOrNull { it.se == uiState.selectedSeason }
                ?: uiState.availableSeasons.firstOrNull()
            val maxEp = currentSeasonForJump?.maxEp ?: 1

            AlertDialog(
                onDismissRequest = { showJumpDialog = false },
                containerColor = MaterialTheme.colorScheme.surface,
                title = { Text("Jump to Episode (1-$maxEp)", color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.Bold) },
                text = {
                    Column {
                        Text("Enter episode number:", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
                        Spacer(Modifier.height(10.dp))
                        OutlinedTextField(
                            value = jumpInputText,
                            onValueChange = { if (it.all { c -> c.isDigit() }) jumpInputText = it },
                            placeholder = { Text("e.g. 50, 200, $maxEp", color = MaterialTheme.colorScheme.onSurfaceVariant) },
                            singleLine = true,
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedTextColor = MaterialTheme.colorScheme.onSurface,
                                unfocusedTextColor = MaterialTheme.colorScheme.onSurface,
                                focusedBorderColor = MaterialTheme.colorScheme.primary,
                                unfocusedBorderColor = MaterialTheme.colorScheme.outline
                            ),
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                },
                confirmButton = {
                    Button(
                        onClick = {
                            val ep = jumpInputText.toIntOrNull()
                            if (ep != null && ep in 1..maxEp) {
                                showJumpDialog = false
                                jumpInputText = ""
                                currentSeasonForJump?.let { onSelectEpisode(it.se, ep) }
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                    ) { Text("Jump", fontWeight = FontWeight.Bold) }
                },
                dismissButton = {
                    TextButton(onClick = { showJumpDialog = false }) {
                        Text("Cancel", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            )
        }
    }
}

private fun Context.findActivity(): Activity? {
    var ctx = this
    while (ctx is ContextWrapper) {
        if (ctx is Activity) return ctx
        ctx = ctx.baseContext
    }
    return null
}
