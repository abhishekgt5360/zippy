package com.example.ui.components

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.*
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.example.data.local.WatchHistoryEntity
import com.example.data.model.MovieItem

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import com.example.ui.theme.LocalHighContrast


// ─────────────────────────────────────────────────────────────────────────────
//  MoviePosterCard — Liquid glass poster card with authentic brand accents
// ─────────────────────────────────────────────────────────────────────────────
@Composable
fun MoviePosterCard(
    movie: MovieItem,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    cardWidth: Dp = 135.dp,
    cardHeight: Dp = 200.dp
) {
    val shape = RoundedCornerShape(16.dp)

    Column(
        modifier = modifier
            .width(cardWidth)
            .testTag("movie_card_${movie.safeSlug}")
    ) {
        PosterImage(
            imageUrl = movie.posterUrl,
            contentDescription = movie.safeName,
            aspectRatio = 2f / 3f,
            shape = shape,
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onClick() }
        ) {
            // Bottom gradient fade
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(64.dp)
                    .align(Alignment.BottomCenter)
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(Color.Transparent, Color.Black.copy(alpha = 0.90f)) // legibility-ok: thumbnail gradient scrim
                        )
                    )
            )

            // Rating badge — top right
            val rating = movie.rating
            if (!rating.isNullOrBlank()) {
                RatingBadge(
                    rating = rating,
                    onArtwork = true,
                    modifier = Modifier
                        .padding(7.dp)
                        .align(Alignment.TopEnd)
                )
            }

            // Type/badge pill — top left
            val badgeText = movie.badge?.takeIf { it.isNotBlank() } ?: movie.type?.uppercase()
            if (!badgeText.isNullOrBlank()) {
                PosterBadge(
                    text = badgeText,
                    modifier = Modifier
                        .padding(7.dp)
                        .align(Alignment.TopStart)
                )
            }
        }

        // Title + meta below poster
        Spacer(Modifier.height(7.dp))
        Text(
            text = movie.safeName,
            color = MaterialTheme.colorScheme.onBackground,
            fontSize = 12.5.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            lineHeight = 16.sp
        )
        Spacer(Modifier.height(2.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = movie.year ?: "",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 11.sp
            )
            Text(
                text = movie.genre?.split(",")?.firstOrNull()?.trim() ?: "",
                color = MaterialTheme.colorScheme.primary,
                fontSize = 11.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = 70.dp)
            )
        }
    }
}


