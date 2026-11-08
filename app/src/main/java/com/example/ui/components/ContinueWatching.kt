package com.example.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import coil.size.Scale
import com.example.data.local.WatchHistoryEntity
import com.example.ui.theme.LocalCardBorder
import com.example.ui.theme.playerAccent
import com.example.util.ContinueWatchingUtils
import com.example.util.ImageAspectRatioCategory
import com.example.util.PosterAspectCache

object OverImageStyle {
    val ScrimGradient = Brush.verticalGradient(
        listOf(
            Color.Transparent,
            Color.Black.copy(alpha = 0.40f), // legibility-ok: continue watching scrim
            Color.Black.copy(alpha = 0.85f)  // legibility-ok: continue watching scrim
        )
    )
    val MenuButtonBg = Color.Black.copy(alpha = 0.35f) // legibility-ok: menu button background
    val PrimaryText = Color.White // legibility-ok: primary text over scrim
    val SecondaryText = Color.White.copy(alpha = 0.85f) // legibility-ok: secondary text over scrim
}

@Composable
fun ContinueWatchingSection(
    items: List<WatchHistoryEntity>,
    onResumeClick: (WatchHistoryEntity) -> Unit,
    onDeleteClick: (String) -> Unit,
    onOpenDetailsClick: (WatchHistoryEntity) -> Unit,
    onSeeAllClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    if (items.isEmpty()) return

    Column(modifier = modifier.padding(top = 24.dp)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .clickable { onSeeAllClick() },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(24.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.onBackground),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.PlayArrow,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.background,
                        modifier = Modifier.size(16.dp)
                    )
                }
                Spacer(Modifier.width(8.dp))
                Text(
                    text = "Continue Watching",
                    color = MaterialTheme.colorScheme.onBackground,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
            }
            Icon(
                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = "See All History",
                tint = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier.size(24.dp)
            )
        }
        Spacer(Modifier.height(12.dp))
        
        LazyRow(
            contentPadding = PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            items(items, key = { it.subjectId }) { historyItem ->
                ContinueWatchingCard(
                    history = historyItem,
                    onPlayClick = { onResumeClick(historyItem) },
                    onDeleteClick = { onDeleteClick(historyItem.subjectId) },
                    onOpenDetailsClick = { onOpenDetailsClick(historyItem) }
                )
            }
        }
    }
}

@Composable
fun ContinueWatchingCard(
    history: WatchHistoryEntity,
    onPlayClick: () -> Unit,
    onDeleteClick: () -> Unit,
    onOpenDetailsClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val configuration = LocalConfiguration.current
    val screenWidth = configuration.screenWidthDp.dp
    // Card width = min(screenWidthDp * 0.88, 300dp) so two can comfortably fit vertically
    val cardWidth = minOf(screenWidth * 0.88f, 300.dp)
    val cardHeight = cardWidth * (9f / 16f)

    val shape = RoundedCornerShape(16.dp)
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val scale = if (isPressed) 0.96f else 1f

    val border = LocalCardBorder.current
    var showMenu by remember { mutableStateOf(false) }

    val formattedTitle = ContinueWatchingUtils.formatTitle(history.title, history.season, history.episode)
    val remainingText = ContinueWatchingUtils.formatRemaining(history.positionMs, history.durationMs)
    val progress = ContinueWatchingUtils.calculateProgressFraction(history.positionMs, history.durationMs)
    val hasValidDuration = history.durationMs > 0

    val accessibilityDesc = if (hasValidDuration) "Resume $formattedTitle, $remainingText" else "Resume $formattedTitle"
    val accentColor = playerAccent()

    Box(
        modifier = modifier
            .width(cardWidth)
            .height(cardHeight)
            .scale(scale)
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .then(if (border != null) Modifier.border(border, shape) else Modifier)
            .clickable(interactionSource = interactionSource, indication = null) { onPlayClick() }
            .testTag("continue_card_${history.subjectId}")
            .semantics(mergeDescendants = true) {
                contentDescription = accessibilityDesc
                if (hasValidDuration) {
                    progressBarRangeInfo = androidx.compose.ui.semantics.ProgressBarRangeInfo(
                        current = progress,
                        range = 0f..1f
                    )
                }
            }
    ) {
        // Full-bleed landscape poster image filling the entire 16:9 card
        AsyncImage(
            model = ImageRequest.Builder(LocalContext.current)
                .data(history.posterUrl)
                .crossfade(true)
                .build(),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            alignment = Alignment.Center,
            modifier = Modifier.fillMaxSize()
        )

        // Gradient Scrim
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(OverImageStyle.ScrimGradient)
        )

        // Menu Button (Top Right)
        Box(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(4.dp)
        ) {
            IconButton(
                onClick = { showMenu = true },
                modifier = Modifier.size(48.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(32.dp)
                        .clip(CircleShape)
                        .background(OverImageStyle.MenuButtonBg),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.MoreVert,
                        contentDescription = "More options",
                        tint = OverImageStyle.PrimaryText,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
            DropdownMenu(
                expanded = showMenu,
                onDismissRequest = { showMenu = false },
                containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
            ) {
                DropdownMenuItem(
                    text = { Text("Details", color = MaterialTheme.colorScheme.onSurface) },
                    onClick = {
                        showMenu = false
                        onOpenDetailsClick()
                    },
                    leadingIcon = {
                        Icon(
                            imageVector = Icons.Default.Info,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.size(24.dp)
                        )
                    }
                )
                DropdownMenuItem(
                    text = { Text("Remove from Continue Watching", color = MaterialTheme.colorScheme.onSurface) },
                    onClick = {
                        showMenu = false
                        onDeleteClick()
                    },
                    leadingIcon = {
                        Icon(
                            imageVector = Icons.Default.Delete,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(24.dp)
                        )
                    }
                )
            }
        }

        // Center Play Button - ensure icon is legible by using onPrimary variant that contrasts against primary background
        Box(
            modifier = Modifier
                .size(52.dp)
                .align(Alignment.Center)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primary)
                .clickable { onPlayClick() },
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Default.PlayArrow,
                contentDescription = null,
                // Use onPrimary for contrast against the primary circle background
                tint = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier.size(32.dp)
            )
        }

        // Bottom Info & Progress Bar
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.BottomStart)
                .padding(horizontal = 12.dp, vertical = 10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Bottom
            ) {
                Text(
                    text = formattedTitle,
                    color = OverImageStyle.PrimaryText,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )

                if (hasValidDuration && remainingText.isNotEmpty()) {
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = remainingText,
                        color = OverImageStyle.SecondaryText,
                        fontSize = 11.sp,
                        maxLines = 1
                    )
                }
            }

            Spacer(Modifier.height(8.dp))

            // Rounded Progress Bar slightly above the bottom edge
            if (hasValidDuration) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(4.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(Color.White.copy(alpha = 0.25f)) // legibility-ok: progress track
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(progress)
                            .fillMaxHeight()
                            .background(accentColor)
                    )
                }
            }
        }
    }
}