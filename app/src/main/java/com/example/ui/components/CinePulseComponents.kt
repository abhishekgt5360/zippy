package com.example.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import coil.size.Scale
import com.example.ui.theme.LocalHighContrast
import com.example.ui.theme.calculateContrastRatio
import com.example.ui.theme.selectableColors

// ─────────────────────────────────────────────────────────────────────────────
// LiquidGlassCard -> Material 3 Card
// ─────────────────────────────────────────────────────────────────────────────

@Composable
fun LiquidGlassCard(
    modifier: Modifier = Modifier,
    cornerRadius: Dp = 12.dp,
    onClick: (() -> Unit)? = null,
    content: @Composable () -> Unit
) {
    val isHighContrast = LocalHighContrast.current
    val border = if (isHighContrast) BorderStroke(1.5.dp, MaterialTheme.colorScheme.outline) else null
    val elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)

    if (onClick != null) {
        Card(
            onClick = onClick,
            modifier = modifier,
            shape = RoundedCornerShape(cornerRadius),
            border = border,
            elevation = elevation,
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainer
            )
        ) {
            content()
        }
    } else {
        Card(
            modifier = modifier,
            shape = RoundedCornerShape(cornerRadius),
            border = border,
            elevation = elevation,
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainer
            )
        ) {
            content()
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// LiquidGlassGrip -> Bottom sheet drag handle
// ─────────────────────────────────────────────────────────────────────────────

@Composable
fun LiquidGlassGrip(modifier: Modifier = Modifier) {
    val isHighContrast = LocalHighContrast.current
    Box(
        modifier = modifier
            .width(32.dp)
            .height(4.dp)
            .clip(RoundedCornerShape(2.dp))
            .background(if (isHighContrast) MaterialTheme.colorScheme.outline else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f))
    )
}

// ─────────────────────────────────────────────────────────────────────────────
// LiquidGlassPill -> Badge / Chip
// ─────────────────────────────────────────────────────────────────────────────

@Composable
fun LiquidGlassPill(
    label: String,
    modifier: Modifier = Modifier
) {
    val isHighContrast = LocalHighContrast.current
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(8.dp),
        border = if (isHighContrast) BorderStroke(1.5.dp, MaterialTheme.colorScheme.outline) else null,
        color = MaterialTheme.colorScheme.tertiaryContainer,
        contentColor = MaterialTheme.colorScheme.onTertiaryContainer
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                maxLines = 1,
                softWrap = false
            )
        }
    }
}

@Composable
fun LiquidGlassPill(
    text: String,
    modifier: Modifier = Modifier,
    textStyle: TextStyle = MaterialTheme.typography.labelMedium
) {
    val isHighContrast = LocalHighContrast.current
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(8.dp),
        border = if (isHighContrast) BorderStroke(1.5.dp, MaterialTheme.colorScheme.outline) else null,
        color = MaterialTheme.colorScheme.tertiaryContainer,
        contentColor = MaterialTheme.colorScheme.onTertiaryContainer
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
        ) {
            Text(
                text = text,
                style = textStyle,
                maxLines = 1,
                softWrap = false
            )
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// LiquidGlassButton -> Primary Button
// ─────────────────────────────────────────────────────────────────────────────

@Composable
fun LiquidGlassButton(
    text: String,
    icon: ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true
) {
    val isHighContrast = LocalHighContrast.current
    val border = if (isHighContrast) BorderStroke(1.5.dp, MaterialTheme.colorScheme.outline) else null
    val elevation = ButtonDefaults.buttonElevation(defaultElevation = 0.dp)

    Button(
        onClick = onClick,
        modifier = modifier.height(48.dp),
        enabled = enabled,
        border = border,
        elevation = elevation,
        colors = ButtonDefaults.buttonColors(
            containerColor = MaterialTheme.colorScheme.primary,
            contentColor = MaterialTheme.colorScheme.onPrimary
        )
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.size(20.dp)
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = text,
            fontWeight = FontWeight.Bold
        )
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// LiquidGlassSecondaryButton -> Outlined Button
// ─────────────────────────────────────────────────────────────────────────────

@Composable
fun LiquidGlassSecondaryButton(
    text: String,
    icon: ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val isHighContrast = LocalHighContrast.current
    val border = if (isHighContrast) BorderStroke(1.5.dp, MaterialTheme.colorScheme.outline) else ButtonDefaults.outlinedButtonBorder(enabled = true)

    OutlinedButton(
        onClick = onClick,
        modifier = modifier.height(48.dp),
        border = border,
        colors = ButtonDefaults.outlinedButtonColors(
            contentColor = MaterialTheme.colorScheme.onSurface
        )
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.size(20.dp)
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = text,
            fontWeight = FontWeight.Bold
        )
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// GlassChip -> FilterChip
// ─────────────────────────────────────────────────────────────────────────────

@Composable
fun GlassChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    trailingIcon: ImageVector? = null,
    trailingIconContentDescription: String? = null,
    onTrailingIconClick: (() -> Unit)? = null
) {
    val isHighContrast = LocalHighContrast.current
    val selColors = selectableColors(selected)
    val borderWidth = if (isHighContrast) 1.5.dp else if (selected) 1.dp else 0.5.dp

    FilterChip(
        selected = selected,
        onClick = onClick,
        label = {
            Text(
                text = label,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium
            )
        },
        leadingIcon = if (selected && isHighContrast) {
            {
                Icon(
                    imageVector = Icons.Default.Check,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp)
                )
            }
        } else icon?.let {
            {
                Icon(
                    imageVector = it,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp)
                )
            }
        },
        trailingIcon = trailingIcon?.let { vector ->
            {
                Icon(
                    imageVector = vector,
                    contentDescription = trailingIconContentDescription,
                    modifier = Modifier
                        .size(16.dp)
                        .then(
                            if (onTrailingIconClick != null) {
                                Modifier.clickable(onClick = onTrailingIconClick)
                            } else {
                                Modifier
                            }
                        )
                )
            }
        },
        border = BorderStroke(borderWidth, selColors.border),
        colors = FilterChipDefaults.filterChipColors(
            selectedContainerColor = selColors.container,
            selectedLabelColor = selColors.content,
            selectedLeadingIconColor = selColors.content,
            selectedTrailingIconColor = selColors.content,
            containerColor = selColors.container,
            labelColor = selColors.secondaryContent,
            iconColor = selColors.secondaryContent
        ),
        modifier = modifier
    )
}

// ─────────────────────────────────────────────────────────────────────────────
// GlassIconButton -> IconButton
// ─────────────────────────────────────────────────────────────────────────────

@Composable
fun GlassIconButton(
    icon: ImageVector,
    onClick: () -> Unit,
    contentDescription: String,
    modifier: Modifier = Modifier,
    tint: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.onSurface
) {
    val isHighContrast = LocalHighContrast.current
    val borderModifier = if (isHighContrast) Modifier.border(BorderStroke(1.5.dp, MaterialTheme.colorScheme.outline), RoundedCornerShape(24.dp)) else Modifier

    IconButton(
        onClick = onClick,
        modifier = modifier.then(borderModifier)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = tint
        )
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// PosterImage -> Shared Uncropped Poster Component with Aspect Ratio Detection
// ─────────────────────────────────────────────────────────────────────────────

@Composable
fun PosterImage(
    imageUrl: String?,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    title: String? = null,
    subtitle: String? = null,
    titleBelow: Boolean = true,
    aspectRatio: Float = 2f / 3f,
    shape: Shape = RoundedCornerShape(12.dp),
    overlay: @Composable BoxScope.() -> Unit = {}
) {
    val context = LocalContext.current
    val isHighContrast = LocalHighContrast.current
    val border = if (isHighContrast) {
        BorderStroke(1.5.dp, MaterialTheme.colorScheme.outline)
    } else {
        BorderStroke(0.5.dp, MaterialTheme.colorScheme.outlineVariant)
    }

    var detectedRatio by remember(imageUrl) {
        mutableStateOf(com.example.util.PosterAspectCache.getRatio(imageUrl))
    }
    val category = com.example.util.PosterAspectCache.classify(detectedRatio)

    Box(
        modifier = modifier
            .aspectRatio(aspectRatio)
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceContainerHighest)
            .border(border, shape),
        contentAlignment = Alignment.Center
    ) {
        if (!imageUrl.isNullOrBlank()) {
            when {
                // Wide or Squareish image, and title is NOT drawn below the card elsewhere
                (category == com.example.util.ImageAspectRatioCategory.WIDE || category == com.example.util.ImageAspectRatioCategory.SQUAREISH) && !titleBelow -> {
                    Column(modifier = Modifier.fillMaxSize()) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f)
                                .background(MaterialTheme.colorScheme.surfaceContainerHighest),
                            contentAlignment = Alignment.TopCenter
                        ) {
                            AsyncImage(
                                model = ImageRequest.Builder(context)
                                    .data(imageUrl)
                                    .scale(Scale.FIT)
                                    .crossfade(200)
                                    .build(),
                                contentDescription = contentDescription,
                                contentScale = ContentScale.Fit,
                                alignment = Alignment.TopCenter,
                                onSuccess = { state ->
                                    val d = state.result.drawable
                                    if (d.intrinsicWidth > 0 && d.intrinsicHeight > 0) {
                                        val r = d.intrinsicWidth.toFloat() / d.intrinsicHeight.toFloat()
                                        com.example.util.PosterAspectCache.putRatio(imageUrl, r)
                                        detectedRatio = r
                                    }
                                },
                                modifier = Modifier.fillMaxSize()
                            )
                        }

                        // Themed bottom strip displaying title & subtitle
                        if (!title.isNullOrBlank() || !subtitle.isNullOrBlank()) {
                            Surface(
                                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp)
                                ) {
                                    if (!title.isNullOrBlank()) {
                                        Text(
                                            text = title,
                                            style = MaterialTheme.typography.labelMedium.copy(
                                                fontWeight = FontWeight.Bold,
                                                color = MaterialTheme.colorScheme.onSurface
                                            ),
                                            maxLines = 2,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                    if (!subtitle.isNullOrBlank()) {
                                        Text(
                                            text = subtitle,
                                            style = MaterialTheme.typography.labelSmall.copy(
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            ),
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                // Default/Portrait or Wide with title below: Fill/Fit centered on surfaceContainerHighest
                else -> {
                    // When the artwork does not match the frame, fill the leftover space with a
                    // dimmed copy of the same artwork instead of showing empty bands.
                    val currentRatio = detectedRatio
                    if (currentRatio != null && kotlin.math.abs(currentRatio - aspectRatio) > 0.05f) {
                        AsyncImage(
                            model = ImageRequest.Builder(context)
                                .data(imageUrl)
                                .size(32, 32)
                                .scale(Scale.FILL)
                                .crossfade(200)
                                .build(),
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize()
                        )
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(Color.Black.copy(alpha = 0.55f)) // legibility-ok: poster ambient backdrop
                        )
                    }

                    AsyncImage(
                        model = ImageRequest.Builder(context)
                            .data(imageUrl)
                            .scale(Scale.FIT)
                            .crossfade(200)
                            .build(),
                        contentDescription = contentDescription,
                        contentScale = ContentScale.Fit,
                        alignment = Alignment.Center,
                        onSuccess = { state ->
                            val d = state.result.drawable
                            if (d.intrinsicWidth > 0 && d.intrinsicHeight > 0) {
                                val r = d.intrinsicWidth.toFloat() / d.intrinsicHeight.toFloat()
                                com.example.util.PosterAspectCache.putRatio(imageUrl, r)
                                detectedRatio = r
                            }
                        },
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }
        }

        overlay()
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// ThemedPosterBadge -> Shared pill shell for every poster overlay badge
// ─────────────────────────────────────────────────────────────────────────────

// Badges are drawn over arbitrary poster artwork, so the pill is filled with
// the active theme surface (near-opaque over artwork) instead of leaning on the
// image behind it. Labels and icons then use the theme accent. If a palette's
// primary is too close to that surface (possible with dynamic palettes), the
// accent falls back through onSurface/onSurfaceVariant, which stay legible
// against their own surface pair by construction.
@Composable
private fun ThemedPosterBadge(
    onArtwork: Boolean,
    modifier: Modifier = Modifier,
    content: @Composable RowScope.(accent: Color) -> Unit
) {
    val colors = MaterialTheme.colorScheme
    val isHighContrast = LocalHighContrast.current
    val surfaceBase = if (onArtwork) colors.surface else colors.surfaceContainerHigh
    val container = if (onArtwork) surfaceBase.copy(alpha = 0.94f) else surfaceBase
    val accent = listOf(colors.primary, colors.onSurface, colors.onSurfaceVariant)
        .firstOrNull { calculateContrastRatio(it, surfaceBase) >= 4.5 }
        ?: colors.onSurface
    val shape = RoundedCornerShape(999.dp)

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .clip(shape)
            .background(container, shape)
            .then(if (isHighContrast) Modifier.border(1.dp, colors.onSurface, shape) else Modifier)
            .padding(horizontal = 8.dp, vertical = 4.dp)
    ) {
        content(accent)
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// PosterBadge -> Type / language badge ON TOP of artwork
// ─────────────────────────────────────────────────────────────────────────────

@Composable
fun PosterBadge(text: String, modifier: Modifier = Modifier) {
    ThemedPosterBadge(onArtwork = true, modifier = modifier) { accent ->
        Text(
            text = text,
            color = accent,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            softWrap = false
        )
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// RatingBadge -> Star rating, same theme-aware shell as PosterBadge
// ─────────────────────────────────────────────────────────────────────────────

@Composable
fun RatingBadge(
    rating: String,
    modifier: Modifier = Modifier,
    onArtwork: Boolean = false
) {
    ThemedPosterBadge(onArtwork = onArtwork, modifier = modifier) { accent ->
        Icon(
            imageVector = Icons.Default.Star,
            contentDescription = null,
            tint = accent,
            modifier = Modifier.size(11.dp)
        )
        Spacer(Modifier.width(3.dp))
        Text(
            text = rating,
            color = accent,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            softWrap = false
        )
    }
}
