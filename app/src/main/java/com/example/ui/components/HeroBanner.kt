package com.example.ui.components

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.example.data.model.MovieItem
import kotlinx.coroutines.delay

/**
 * Immersive full-bleed Hero Banner extending behind the top status bar.
 */
@Composable
fun HeroBanner(
    items: List<MovieItem>,
    onItemClick: (MovieItem) -> Unit,
    onWatchClick: (MovieItem) -> Unit,
    modifier: Modifier = Modifier
) {
    if (items.isEmpty()) return

    val pageCount = items.size.coerceAtMost(8)
    val pagerState = rememberPagerState(pageCount = { pageCount })

    // Auto-advance every 5 seconds
    LaunchedEffect(pagerState) {
        while (true) {
            delay(5_000)
            if (pagerState.pageCount > 1) {
                val next = (pagerState.currentPage + 1) % pagerState.pageCount
                pagerState.animateScrollToPage(next)
            }
        }
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(310.dp)
            .testTag("hero_banner")
    ) {
        HorizontalPager(
            state = pagerState,
            modifier = Modifier.fillMaxSize()
        ) { page ->
            HeroBannerSlide(
                item = items[page],
                onItemClick = { onItemClick(items[page]) },
                onWatchClick = { onWatchClick(items[page]) }
            )
        }

        // ── Pager Dot Indicators ─────────────────────────────────────────────
        if (pagerState.pageCount > 1) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(5.dp),
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 6.dp)
            ) {
                repeat(pagerState.pageCount) { index ->
                    val isActive = pagerState.currentPage == index
                    val dotWidth by animateDpAsState(
                        targetValue = if (isActive) 18.dp else 5.dp,
                        animationSpec = spring(
                            dampingRatio = Spring.DampingRatioMediumBouncy,
                            stiffness = Spring.StiffnessMedium
                        ),
                        label = "dot_width_$index"
                    )
                    Box(
                        modifier = Modifier
                            .height(5.dp)
                            .width(dotWidth)
                            .clip(RoundedCornerShape(999.dp))
                            .background(
                                if (isActive) MaterialTheme.colorScheme.primary
                                else Color.White.copy(alpha = 0.40f) // legibility-ok: dot indicator
                            )
                    )
                }
            }
        }
    }
}

@Composable
private fun HeroBannerSlide(
    item: MovieItem,
    onItemClick: () -> Unit,
    onWatchClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surfaceContainerHighest)
            .clickable(onClick = onItemClick)
    ) {
        // High-definition 16:9 widescreen backdrop with centered crop (no distortion)
        val artworkUrl = item.backdropUrl?.takeIf { it.isNotBlank() } ?: item.posterUrl
        AsyncImage(
            model = ImageRequest.Builder(LocalContext.current)
                .data(artworkUrl)
                .crossfade(true)
                .build(),
            contentDescription = item.safeName,
            contentScale = ContentScale.Crop,
            alignment = Alignment.Center,
            modifier = Modifier.fillMaxSize()
        )

        // Top vignette for top bar & status bar icon clarity
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(100.dp)
                .align(Alignment.TopCenter)
                .background(
                    Brush.verticalGradient(
                        listOf(
                            Color.Black.copy(alpha = 0.70f), // legibility-ok: top vignette scrim
                            Color.Transparent
                        )
                    )
                )
        )

        // Cinematic bottom scrim fading into background
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        colorStops = arrayOf(
                            0.00f to Color.Transparent,
                            0.20f to Color.Transparent,
                            0.45f to Color.Black.copy(alpha = 0.35f), // legibility-ok: hero image scrim
                            0.65f to Color.Black.copy(alpha = 0.65f), // legibility-ok: hero image scrim
                            0.85f to Color.Black.copy(alpha = 0.88f), // legibility-ok: hero image scrim
                            1.00f to MaterialTheme.colorScheme.background
                        )
                    )
                )
        )

        // ── Spotlight Content & Actions (Bottom Aligned) ───────────────────────
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.BottomStart)
                .padding(horizontal = 18.dp, vertical = 18.dp)
        ) {
            // Type Pill & Meta Row (Inline for sleek cinematic look)
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                val typeText = if (item.isTvSeries) "SERIES" else "MOVIE"
                PosterBadge(text = typeText)

                val metaList = listOfNotNull(
                    item.genre?.split(",")?.firstOrNull()?.trim(),
                    item.year?.takeIf { it.isNotBlank() }
                )
                if (metaList.isNotEmpty()) {
                    Text(
                        text = metaList.joinToString(" • "),
                        color = Color.White.copy(alpha = 0.85f), // legibility-ok: hero metadata text
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium
                    )
                }
            }

            Spacer(modifier = Modifier.height(4.dp))

            // Movie/Show Title
            Text(
                text = item.safeName,
                color = Color.White, // legibility-ok: text over hero image scrim
                fontSize = 22.sp,
                fontWeight = FontWeight.ExtraBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                lineHeight = 26.sp
            )

            Spacer(modifier = Modifier.height(8.dp))

            // ── Primary Actions: Watch Now (Primary) & Details (Tonal) ──
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Button(
                    onClick = onWatchClick,
                    shape = RoundedCornerShape(999.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary
                    ),
                    modifier = Modifier
                        .weight(1f)
                        .height(38.dp)
                        .testTag("hero_watch_button"),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 2.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.PlayArrow,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(
                        text = "Watch Now",
                        fontWeight = FontWeight.Bold,
                        fontSize = 13.sp
                    )
                }

                OutlinedButton(
                    onClick = onItemClick,
                    shape = RoundedCornerShape(999.dp),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary),
                    colors = ButtonDefaults.outlinedButtonColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer,
                        contentColor = MaterialTheme.colorScheme.onPrimaryContainer
                    ),
                    modifier = Modifier
                        .weight(1f)
                        .height(38.dp)
                        .testTag("hero_details_button"),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 2.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Info,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(
                        text = "Details",
                        fontWeight = FontWeight.Bold,
                        fontSize = 13.sp
                    )
                }
            }
        }
    }
}
