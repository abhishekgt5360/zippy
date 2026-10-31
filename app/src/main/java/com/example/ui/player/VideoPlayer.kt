@file:OptIn(
    androidx.media3.common.util.UnstableApi::class,
    androidx.compose.material3.ExperimentalMaterial3Api::class
)

package com.example.ui.player

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.media.AudioManager
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import androidx.activity.compose.BackHandler
import androidx.annotation.OptIn
import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.example.data.model.CaptionItem
import com.example.data.model.StreamSource
import com.example.ui.theme.*
import com.example.util.CaptionPreferences
import com.example.util.PlaybackSession
import com.example.util.ResumeManager
import com.example.util.ResumeNoticePosition
import com.example.util.ResumeNoticePreferences
import com.example.util.StreamTokenManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Shadow
import java.util.Locale
import kotlin.math.roundToInt

enum class ResizeMode(val mode: Int, val label: String) {
    FIT(AspectRatioFrameLayout.RESIZE_MODE_FIT, "Fit"),
    ZOOM(AspectRatioFrameLayout.RESIZE_MODE_ZOOM, "Crop/Fill"),
    FILL(AspectRatioFrameLayout.RESIZE_MODE_FILL, "Stretch")
}

@OptIn(UnstableApi::class, ExperimentalMaterial3Api::class)
@Composable
fun VideoPlayer(
    exoPlayer: ExoPlayer,
    title: String,
    subtitleInfo: String?,
    sources: List<StreamSource>,
    initialSourceIndex: Int = 0,
    captions: List<CaptionItem> = emptyList(),
    session: PlaybackSession? = null,
    isSeries: Boolean = false,
    onProgressUpdate: (positionMs: Long, durationMs: Long) -> Unit,
    onStartOver: (subjectId: String, season: Int, episode: Int, isSeries: Boolean) -> Unit = { _, _, _, _ -> },
    onBackClick: () -> Unit,
    onRefreshStream: (resumePos: Long) -> Unit = {},
    hasPreviousEpisode: Boolean = false,
    hasNextEpisode: Boolean = false,
    onPreviousEpisode: () -> Unit = {},
    onNextEpisode: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val view = LocalView.current
    val activity = remember(context) { context.findActivity() }
    val configuration = LocalConfiguration.current
    val isLandscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
    val scope = rememberCoroutineScope()
    val isHighContrast = LocalHighContrast.current
    val accentColor = playerAccent()

    val audioManager = remember { context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager }
    val isInPip = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
        activity?.isInPictureInPictureMode == true
    } else false

    var selectedSourceIndex by remember(sources) {
        val preferred1080 = sources.indexOfFirst { it.resolution.contains("1080", ignoreCase = true) }
        val preferred720 = sources.indexOfFirst { it.resolution.contains("720", ignoreCase = true) }
        val preferred480 = sources.indexOfFirst { it.resolution.contains("480", ignoreCase = true) }
        val defaultIdx = when {
            preferred720 >= 0 -> preferred720
            preferred1080 >= 0 -> preferred1080
            preferred480 >= 0 -> preferred480
            sources.isNotEmpty() -> 0
            else -> 0
        }
        mutableIntStateOf(defaultIdx.coerceIn(0, (sources.size - 1).coerceAtLeast(0)))
    }

    val currentSource = sources.getOrNull(selectedSourceIndex)

    var isPlaying by remember { mutableStateOf(exoPlayer.isPlaying) }
    var currentPosition by remember { mutableLongStateOf(exoPlayer.currentPosition) }
    var duration by remember { mutableLongStateOf(exoPlayer.duration.coerceAtLeast(0L)) }
    var isBuffering by remember { mutableStateOf(exoPlayer.playbackState == Player.STATE_BUFFERING) }
    var hasError by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var autoRetryCount by remember { mutableIntStateOf(0) }
    var reconnectingMessage by remember { mutableStateOf<String?>(null) }

    var controlsVisible by remember { mutableStateOf(true) }
    var isLocked by remember { mutableStateOf(false) }
    var currentResizeMode by remember { mutableStateOf(ResizeMode.FIT) }
    var playbackSpeed by remember { mutableFloatStateOf(1.0f) }

    // Session Start Position
    val resumeNoticeConfig = remember { ResumeNoticePreferences.load(context) }
    val startPosMs = session?.startPositionMs ?: 0L
    var showResumeSnackbar by remember(session?.sessionId) {
        mutableStateOf(startPosMs > 0L && resumeNoticeConfig.enabled)
    }
    var hasAppliedSessionStart by remember(session?.sessionId) { mutableStateOf(false) }

    // Gesture HUD feedback
    var doubleTapFeedback by remember { mutableStateOf<String?>(null) }
    var volumeLevel by remember { mutableFloatStateOf(-1f) }
    var brightnessLevel by remember { mutableFloatStateOf(-1f) }

    // Subtitles
    var selectedCaptionIndex by remember { mutableIntStateOf(-1) }
    var parsedSubtitles by remember { mutableStateOf<List<SubtitleCue>>(emptyList()) }
    var currentSubtitleText by remember { mutableStateOf("") }
    var captionTextOpacity by remember { mutableFloatStateOf(CaptionPreferences.getTextOpacity(context)) }
    var captionBgOpacity by remember { mutableFloatStateOf(CaptionPreferences.getBackgroundOpacity(context)) }
    var subtitleDialogTab by remember { mutableIntStateOf(0) }
    var streamReloadTrigger by remember { mutableIntStateOf(0) }

    // Modals
    var showSettingsSheet by remember { mutableStateOf(false) }
    var showQualityDialog by remember { mutableStateOf(false) }
    var showSubtitleDialog by remember { mutableStateOf(false) }
    var showSpeedDialog by remember { mutableStateOf(false) }

    // Fix: Keep screen on during playback
    fun updateKeepScreenOn(playing: Boolean, state: Int, error: Boolean) {
        val shouldKeepOn = playing && !error && (state == Player.STATE_READY || state == Player.STATE_BUFFERING)
        view.keepScreenOn = shouldKeepOn
        activity?.window?.let { window ->
            if (shouldKeepOn) {
                window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            } else {
                window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
        }
    }

    LaunchedEffect(isPlaying, isBuffering, hasError) {
        updateKeepScreenOn(isPlaying, exoPlayer.playbackState, hasError)
    }

    // System bars formatting
    fun setImmersive(enabled: Boolean) {
        activity?.window?.let { window ->
            val insetsController = WindowCompat.getInsetsController(window, window.decorView)
            if (enabled) {
                insetsController.hide(WindowInsetsCompat.Type.systemBars())
                insetsController.systemBarsBehavior =
                    WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                insetsController.isAppearanceLightStatusBars = false // legibility-ok: force light icons over video
                insetsController.isAppearanceLightNavigationBars = false // legibility-ok: force light icons over video
            } else {
                insetsController.show(WindowInsetsCompat.Type.systemBars())
                val isLightTheme = (configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) != Configuration.UI_MODE_NIGHT_YES
                insetsController.isAppearanceLightStatusBars = isLightTheme
                insetsController.isAppearanceLightNavigationBars = isLightTheme
            }
        }
    }

    LaunchedEffect(isLandscape) {
        setImmersive(isLandscape)
    }

    // Toggle orientation
    fun toggleOrientation() {
        activity?.let { act ->
            act.requestedOrientation = if (isLandscape) {
                ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            } else {
                ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            }
        }
    }

    BackHandler(enabled = isLandscape) {
        activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
    }

    // Seamless media loading: applies session start position EXACTLY ONCE
    LaunchedEffect(currentSource, session?.sessionId, streamReloadTrigger) {
        if (currentSource != null && currentSource.url.isNotBlank()) {
            val url = currentSource.url
            val currentMediaId = exoPlayer.currentMediaItem?.mediaId
            val isPlayerIdleOrError = exoPlayer.playbackState == Player.STATE_IDLE || exoPlayer.playerError != null
            
            if (!hasAppliedSessionStart || currentMediaId != url || isPlayerIdleOrError || streamReloadTrigger > 0) {
                hasAppliedSessionStart = true
                hasError = false
                errorMessage = null
                isBuffering = true
                try {
                    val mediaItem = MediaItem.Builder()
                        .setMediaId(url)
                        .setUri(url)
                        .apply {
                            currentSource?.resolveMimeType()?.let { setMimeType(it) }
                        }
                        .build()
                    exoPlayer.setMediaItem(mediaItem, startPosMs.coerceAtLeast(0L))
                    exoPlayer.prepare()
                    exoPlayer.play()
                } catch (e: Exception) {
                    hasError = true
                    errorMessage = e.localizedMessage ?: "Failed to load stream"
                }
            } else {
                if (!exoPlayer.isPlaying && exoPlayer.playbackState == Player.STATE_READY) {
                    exoPlayer.play()
                }
            }
        } else if (sources.isEmpty()) {
            hasError = true
            errorMessage = "No stream links found for this title."
        }
    }

    // Auto-select English or default caption
    LaunchedEffect(captions) {
        if (selectedCaptionIndex == -1 && captions.isNotEmpty()) {
            val defaultIndex = captions.indexOfFirst {
                it.lan.equals("en", ignoreCase = true) ||
                it.lanName?.contains("English", ignoreCase = true) == true
            }.takeIf { it >= 0 } ?: 0
            selectedCaptionIndex = defaultIndex
        }
    }

    // Subtitle parsing
    LaunchedEffect(selectedCaptionIndex) {
        if (selectedCaptionIndex >= 0 && selectedCaptionIndex < captions.size) {
            val caption = captions[selectedCaptionIndex]
            parsedSubtitles = SubtitleManager.loadAndParseSrt(caption.url)
        } else {
            parsedSubtitles = emptyList()
            currentSubtitleText = ""
        }
    }

    // Subtitle sync, position tick, and 10s periodic watch progress saving
    LaunchedEffect(exoPlayer, session?.sessionId) {
        var saveCounter = 0
        while (true) {
            delay(250)
            if (exoPlayer.playbackState != Player.STATE_IDLE) {
                val pos = exoPlayer.currentPosition
                val dur = exoPlayer.duration.coerceAtLeast(0L)
                currentPosition = pos
                duration = dur

                if (parsedSubtitles.isNotEmpty()) {
                    val matchingCue = parsedSubtitles.firstOrNull { pos in it.startTimeMs..it.endTimeMs }
                    currentSubtitleText = matchingCue?.text ?: ""
                } else {
                    currentSubtitleText = ""
                }

                // Periodic save every 10 seconds while playing and READY
                saveCounter++
                if (saveCounter >= 40) {
                    saveCounter = 0
                    if (exoPlayer.isPlaying && dur > 0 && exoPlayer.playbackState == Player.STATE_READY) {
                        onProgressUpdate(pos, dur)
                    }
                }
            }
        }
    }

    // Sync preferred resolution
    LaunchedEffect(currentSource) {
        currentSource?.resolution?.let { res ->
            StreamTokenManager.setPreferredResolution(res)
        }
    }

    // Proactive background keep-alive
    LaunchedEffect(isPlaying) {
        if (isPlaying) {
            while (true) {
                delay(35_000L)
                if (isPlaying) {
                    withContext(Dispatchers.IO) {
                        StreamTokenManager.keepAlive()
                    }
                }
            }
        }
    }

    // Auto-hide controls
    LaunchedEffect(controlsVisible, isPlaying, isLocked) {
        if (controlsVisible && isPlaying && !isLocked) {
            delay(3500)
            controlsVisible = false
        }
    }

    // Auto-hide the resume notice after the user-configured delay
    LaunchedEffect(showResumeSnackbar, isPlaying, resumeNoticeConfig.durationMs) {
        if (
            showResumeSnackbar &&
            resumeNoticeConfig.enabled &&
            resumeNoticeConfig.durationMs > ResumeNoticePreferences.DURATION_UNTIL_DISMISSED &&
            isPlaying
        ) {
            delay(resumeNoticeConfig.durationMs)
            showResumeSnackbar = false
        }
    }

    DisposableEffect(exoPlayer) {
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(playing: Boolean) {
                isPlaying = playing
                updateKeepScreenOn(playing, exoPlayer.playbackState, hasError)
                if (!playing && exoPlayer.duration > 0 && exoPlayer.currentPosition > 0) {
                    onProgressUpdate(exoPlayer.currentPosition, exoPlayer.duration)
                }
            }

            override fun onPlaybackStateChanged(state: Int) {
                isBuffering = state == Player.STATE_BUFFERING
                updateKeepScreenOn(exoPlayer.playWhenReady, state, hasError)
                if (state == Player.STATE_READY) {
                    duration = exoPlayer.duration.coerceAtLeast(0L)
                    hasError = false
                    autoRetryCount = 0
                    reconnectingMessage = null
                } else if (state == Player.STATE_ENDED) {
                    if (exoPlayer.duration > 0) {
                        onProgressUpdate(exoPlayer.duration, exoPlayer.duration)
                    }
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                val resumePos = exoPlayer.currentPosition.takeIf { it > 0 } ?: startPosMs
                val isContainerError = error.errorCode == PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED ||
                        error.errorCodeName.contains("CONTAINER", ignoreCase = true) ||
                        error.message?.contains("malformed", ignoreCase = true) == true
                val isNetworkOrHttp = error.errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED ||
                        error.errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT ||
                        error.errorCode == PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS ||
                        error.errorCode == PlaybackException.ERROR_CODE_IO_UNSPECIFIED ||
                        error.errorCode in 2000..2999 ||
                        error.errorCodeName.contains("IO", ignoreCase = true)

                if ((isContainerError || isNetworkOrHttp) && autoRetryCount < 4) {
                    autoRetryCount++
                    isBuffering = true
                    hasError = false

                    scope.launch {
                        if (isNetworkOrHttp) {
                            reconnectingMessage = "Stream link refreshed, resuming..."
                            val safePos = resumePos.coerceAtLeast(0L)
                            val freshSources = withContext(Dispatchers.IO) {
                                StreamTokenManager.forceRefreshSources()
                            }
                            val targetSource = freshSources.getOrNull(selectedSourceIndex)
                                ?: freshSources.firstOrNull()
                                ?: currentSource

                            if (targetSource != null && targetSource.url.isNotBlank()) {
                                reconnectingMessage = "Resuming playback..."
                                val mediaItem = MediaItem.Builder()
                                    .setMediaId(targetSource.url)
                                    .setUri(targetSource.url)
                                    .apply {
                                        targetSource.resolveMimeType()?.let { setMimeType(it) }
                                    }
                                    .build()
                                exoPlayer.setMediaItem(mediaItem, safePos)
                                exoPlayer.prepare()
                                exoPlayer.play()
                            }
                            onRefreshStream(safePos)
                        } else {
                            reconnectingMessage = "Reconnecting stream ($autoRetryCount/4)..."
                            delay(300)
                            try {
                                val url = currentSource?.url
                                val safePos = resumePos.coerceAtLeast(0L)
                                if (!url.isNullOrBlank()) {
                                    val mediaItem = MediaItem.Builder()
                                        .setMediaId(url)
                                        .setUri(url)
                                        .apply {
                                            currentSource?.resolveMimeType()?.let { setMimeType(it) }
                                        }
                                        .build()
                                    exoPlayer.setMediaItem(mediaItem, safePos)
                                }
                                exoPlayer.prepare()
                                exoPlayer.play()
                            } catch (_: Exception) {
                                onRefreshStream(resumePos)
                            }
                        }
                    }
                } else if (selectedSourceIndex + 1 < sources.size) {
                    selectedSourceIndex++
                } else {
                    isBuffering = false
                    hasError = true
                    errorMessage = if (isContainerError) {
                        "Stream connection interrupted by server. Tap Refresh Stream to reconnect."
                    } else if (isNetworkOrHttp) {
                        "Stream link expired or disconnected. Tap Refresh Stream to reconnect."
                    } else {
                        "Stream playback error (${error.errorCodeName}). Tap Refresh Stream to reload."
                    }
                }
            }
        }
        exoPlayer.addListener(listener)

        onDispose {
            if (exoPlayer.duration > 0 && exoPlayer.currentPosition > 0) {
                onProgressUpdate(exoPlayer.currentPosition, exoPlayer.duration)
            }
            exoPlayer.removeListener(listener)
            updateKeepScreenOn(false, Player.STATE_IDLE, true)
            setImmersive(false)
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black) // legibility-ok: video surface
            .testTag("video_player_container")
            .pointerInput(isLocked, isInPip) {
                if (isInPip) return@pointerInput
                detectTapGestures(
                    onTap = {
                        if (!isLocked) {
                            controlsVisible = !controlsVisible
                        }
                    },
                    onDoubleTap = { offset ->
                        if (!isLocked) {
                            val width = size.width
                            if (offset.x < width / 2) {
                                val target = (exoPlayer.currentPosition - 10000L).coerceAtLeast(0L)
                                exoPlayer.seekTo(target)
                                doubleTapFeedback = "-10s"
                            } else {
                                val target = (exoPlayer.currentPosition + 10000L).coerceAtMost(duration)
                                exoPlayer.seekTo(target)
                                doubleTapFeedback = "+10s"
                            }
                            scope.launch {
                                delay(600)
                                doubleTapFeedback = null
                            }
                        }
                    }
                )
            }
            .pointerInput(isLocked, isLandscape, isInPip) {
                if (!isInPip && isLandscape && !isLocked) {
                    detectVerticalDragGestures(
                        onVerticalDrag = { change, dragAmount ->
                            change.consume()
                            val width = size.width
                            val x = change.position.x
                            val sensitivity = 0.005f

                            if (x < width / 2) {
                                val currentBr = activity?.window?.attributes?.screenBrightness.takeIf { it != null && it >= 0f } ?: 0.5f
                                val newBr = (currentBr - dragAmount * sensitivity).coerceIn(0.05f, 1.0f)
                                activity?.window?.attributes = activity?.window?.attributes?.apply { screenBrightness = newBr }
                                brightnessLevel = newBr
                            } else {
                                audioManager?.let { am ->
                                    val maxVol = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                                    val currentVol = am.getStreamVolume(AudioManager.STREAM_MUSIC)
                                    val delta = (-dragAmount * sensitivity * maxVol).toInt()
                                    val newVol = (currentVol + delta).coerceIn(0, maxVol)
                                    am.setStreamVolume(AudioManager.STREAM_MUSIC, newVol, 0)
                                    volumeLevel = newVol.toFloat() / maxVol.toFloat()
                                }
                            }
                        },
                        onDragEnd = {
                            scope.launch {
                                delay(1200)
                                brightnessLevel = -1f
                                volumeLevel = -1f
                            }
                        }
                    )
                }
            }
    ) {
        // Video Surface
        AndroidView(
            factory = { ctx ->
                PlayerView(ctx).apply {
                    useController = false
                    layoutParams = FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )
                }
            },
            update = { playerView ->
                if (playerView.player != exoPlayer) {
                    playerView.player = exoPlayer
                }
                playerView.resizeMode = currentResizeMode.mode
            },
            modifier = Modifier.fillMaxSize()
        )

        // Subtitles Overlay
        if (currentSubtitleText.isNotBlank()) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = if (controlsVisible) 64.dp else 20.dp)
                    .padding(horizontal = 24.dp)
            ) {
                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = Color.Black.copy(alpha = captionBgOpacity) // legibility-ok: subtitles
                ) {
                    Text(
                        text = currentSubtitleText,
                        style = MaterialTheme.typography.bodyLarge.copy(
                            color = Color.White.copy(alpha = captionTextOpacity), // legibility-ok: subtitles
                            fontWeight = FontWeight.Bold,
                            fontSize = if (isLandscape) 16.sp else 13.sp,
                            shadow = if (captionBgOpacity < 0.40f) {
                                Shadow(
                                    color = Color.Black.copy(alpha = 0.95f), // legibility-ok: subtitles
                                    offset = Offset(2f, 2f),
                                    blurRadius = 4f
                                )
                            } else null
                        ),
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(
                            horizontal = if (captionBgOpacity > 0.05f) 12.dp else 4.dp,
                            vertical = if (captionBgOpacity > 0.05f) 6.dp else 2.dp
                        )
                    )
                }
            }
        }

        // Resume notice with configurable text, action, duration, and position.
        // Intentionally drawn without an enclosing background.
        if (showResumeSnackbar && startPosMs > 0L && resumeNoticeConfig.enabled) {
            val isTopPosition = resumeNoticeConfig.position == ResumeNoticePosition.TOP
            val noticeShadow = Shadow(
                color = Color.Black.copy(alpha = 0.9f), // legibility-ok: text over video
                offset = Offset(1.5f, 1.5f),
                blurRadius = 6f
            )

            Box(
                modifier = Modifier
                    .align(if (isTopPosition) Alignment.TopCenter else Alignment.BottomCenter)
                    .padding(
                        if (isTopPosition) {
                            PaddingValues(top = if (controlsVisible) 76.dp else 24.dp)
                        } else {
                            PaddingValues(bottom = if (controlsVisible) 76.dp else 24.dp)
                        }
                    )
                    .padding(horizontal = 24.dp)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = resumeNoticeConfig.formatMessage(
                            ResumeManager.formatTimeLabel(startPosMs)
                        ),
                        style = MaterialTheme.typography.bodySmall.copy(
                            color = Color.White, // legibility-ok: text over video
                            fontWeight = FontWeight.Bold,
                            shadow = noticeShadow
                        ),
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    TextButton(
                        onClick = {
                            showResumeSnackbar = false
                            exoPlayer.seekTo(0L)
                            currentPosition = 0L
                            if (session != null) {
                                onStartOver(session.subjectId, session.season, session.episode, session.isSeries)
                            }
                        },
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                    ) {
                        Text(
                            text = resumeNoticeConfig.resolvedActionLabel(),
                            style = MaterialTheme.typography.labelMedium.copy(
                                color = MaterialTheme.colorScheme.primary,
                                fontWeight = FontWeight.Bold,
                                shadow = noticeShadow
                            )
                        )
                    }
                }
            }
        }

        // Double-tap visual feedback
        doubleTapFeedback?.let { feedback ->
            Box(
                modifier = Modifier
                    .align(if (feedback.startsWith("-")) Alignment.CenterStart else Alignment.CenterEnd)
                    .padding(horizontal = 40.dp)
            ) {
                Surface(
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.92f),
                    border = if (isHighContrast) BorderStroke(1.5.dp, MaterialTheme.colorScheme.outline) else null,
                    modifier = Modifier.size(64.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Text(
                            text = feedback,
                            color = MaterialTheme.colorScheme.onSurface,
                            fontWeight = FontWeight.ExtraBold,
                            fontSize = 16.sp
                        )
                    }
                }
            }
        }

        // Brightness HUD
        if (brightnessLevel >= 0f) {
            Box(
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .padding(start = 28.dp)
            ) {
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.92f),
                    border = if (isHighContrast) BorderStroke(1.5.dp, MaterialTheme.colorScheme.outline) else null,
                    modifier = Modifier.padding(6.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.BrightnessMedium, contentDescription = "Brightness", tint = accentColor, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "${(brightnessLevel * 100).toInt()}%",
                            color = MaterialTheme.colorScheme.onSurface,
                            fontWeight = FontWeight.Bold,
                            fontSize = 12.sp
                        )
                    }
                }
            }
        }

        // Volume HUD
        if (volumeLevel >= 0f) {
            Box(
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .padding(end = 28.dp)
            ) {
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.92f),
                    border = if (isHighContrast) BorderStroke(1.5.dp, MaterialTheme.colorScheme.outline) else null,
                    modifier = Modifier.padding(6.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.AutoMirrored.Filled.VolumeUp, contentDescription = "Volume", tint = accentColor, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "${(volumeLevel * 100).toInt()}%",
                            color = MaterialTheme.colorScheme.onSurface,
                            fontWeight = FontWeight.Bold,
                            fontSize = 12.sp
                        )
                    }
                }
            }
        }

        // Buffering Spinner / Reconnecting Overlay
        if (isBuffering && !hasError) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.4f)), // legibility-ok: video surface
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator(
                        color = accentColor,
                        strokeWidth = 3.dp,
                        modifier = Modifier.size(44.dp)
                    )
                    if (!reconnectingMessage.isNullOrBlank()) {
                        Spacer(modifier = Modifier.height(12.dp))
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.92f),
                            border = if (isHighContrast) BorderStroke(1.5.dp, MaterialTheme.colorScheme.outline) else null
                        ) {
                            Text(
                                text = reconnectingMessage ?: "",
                                style = MaterialTheme.typography.bodySmall.copy(
                                    color = MaterialTheme.colorScheme.onSurface,
                                    fontWeight = FontWeight.SemiBold
                                ),
                                modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp)
                            )
                        }
                    }
                }
            }
        }

        // Error Banner with one-tap Resume from exact interrupted position
        if (hasError) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.92f)), // legibility-ok: video surface
                contentAlignment = Alignment.Center
            ) {
                Card(
                    shape = RoundedCornerShape(20.dp),
                    border = if (isHighContrast) BorderStroke(1.5.dp, MaterialTheme.colorScheme.outline) else null,
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
                    modifier = Modifier.padding(24.dp)
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.padding(24.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Warning,
                            contentDescription = "Error",
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(40.dp)
                        )
                        Spacer(modifier = Modifier.height(10.dp))
                        Text(
                            text = "Playback Interrupted",
                            style = MaterialTheme.typography.titleMedium.copy(
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = errorMessage ?: "Unable to stream. Stream connection was interrupted.",
                            style = MaterialTheme.typography.bodySmall.copy(
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = TextAlign.Center
                            )
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Button(
                                onClick = {
                                    hasError = false
                                    isBuffering = true
                                    autoRetryCount = 0
                                    reconnectingMessage = "Fetching fresh stream token..."
                                    val safePos = currentPosition.coerceAtLeast(0L)
                                    scope.launch {
                                        val freshSources = withContext(Dispatchers.IO) {
                                            StreamTokenManager.forceRefreshSources()
                                        }
                                        val targetSource = freshSources.getOrNull(selectedSourceIndex)
                                            ?: freshSources.firstOrNull()
                                            ?: currentSource
                                        if (targetSource != null && targetSource.url.isNotBlank()) {
                                            reconnectingMessage = "Resuming playback..."
                                            val mediaItem = MediaItem.Builder()
                                                .setMediaId(targetSource.url)
                                                .setUri(targetSource.url)
                                                .apply {
                                                    targetSource.resolveMimeType()?.let { setMimeType(it) }
                                                }
                                                .build()
                                            exoPlayer.setMediaItem(mediaItem, safePos)
                                            exoPlayer.prepare()
                                            exoPlayer.play()
                                        }
                                        onRefreshStream(safePos)
                                    }
                                },
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = MaterialTheme.colorScheme.primary,
                                    contentColor = MaterialTheme.colorScheme.onPrimary
                                ),
                                modifier = Modifier.height(38.dp)
                            ) {
                                Icon(Icons.Default.Refresh, contentDescription = "Refresh Stream", modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Refresh Stream", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                            }

                            OutlinedButton(
                                onClick = {
                                    hasError = false
                                    isBuffering = true
                                    autoRetryCount = 0
                                    scope.launch {
                                        try {
                                            val url = currentSource?.url
                                            val safePos = currentPosition.coerceAtLeast(0L)
                                            if (!url.isNullOrBlank()) {
                                                val mediaItem = MediaItem.Builder()
                                                    .setMediaId(url)
                                                    .setUri(url)
                                                    .apply {
                                                        currentSource?.resolveMimeType()?.let { setMimeType(it) }
                                                    }
                                                    .build()
                                                exoPlayer.setMediaItem(mediaItem, safePos)
                                            } else if (safePos > 0L) {
                                                exoPlayer.seekTo(safePos)
                                            }
                                            exoPlayer.prepare()
                                            exoPlayer.play()
                                        } catch (_: Exception) {
                                            onRefreshStream(currentPosition)
                                            streamReloadTrigger++
                                        }
                                    }
                                },
                                colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.onSurface),
                                modifier = Modifier.height(38.dp)
                            ) {
                                Icon(Icons.Default.PlayArrow, contentDescription = "Resume", modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                val resumeTime = formatTime(currentPosition)
                                Text(
                                    if (currentPosition > 5000L) "Resume ($resumeTime)" else "Retry",
                                    fontSize = 12.sp
                                )
                            }

                            if (sources.size > 1) {
                                OutlinedButton(
                                    onClick = {
                                        selectedSourceIndex = (selectedSourceIndex + 1) % sources.size
                                        hasError = false
                                        isBuffering = true
                                        autoRetryCount = 0
                                        streamReloadTrigger++
                                    },
                                    colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.primary),
                                    modifier = Modifier.height(38.dp)
                                ) {
                                    val nextSrc = sources.getOrNull((selectedSourceIndex + 1) % sources.size)
                                    Text("Try ${nextSrc?.resolution ?: "Quality"}", fontSize = 12.sp)
                                }
                            }

                            if (isLandscape) {
                                OutlinedButton(
                                    onClick = { toggleOrientation() },
                                    colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.onSurfaceVariant),
                                    modifier = Modifier.height(38.dp)
                                ) {
                                    Text("Exit Fullscreen", fontSize = 12.sp)
                                }
                            }
                        }
                    }
                }
            }
        }

        // Lock Toggle Button
        AnimatedVisibility(
            visible = !isInPip && ((controlsVisible && isLandscape) || isLocked),
            enter = fadeIn(tween(150)),
            exit = fadeOut(tween(150)),
            modifier = Modifier
                .align(Alignment.CenterStart)
                .padding(start = 20.dp)
        ) {
            IconButton(
                onClick = { isLocked = !isLocked },
                modifier = Modifier
                    .size(44.dp)
                    .background(
                        MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.85f),
                        CircleShape
                    )
                    .then(if (isHighContrast) Modifier.border(1.5.dp, MaterialTheme.colorScheme.outline, CircleShape) else Modifier)
            ) {
                Icon(
                    imageVector = if (isLocked) Icons.Default.Lock else Icons.Default.LockOpen,
                    contentDescription = if (isLocked) "Unlock controls" else "Lock controls",
                    tint = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.size(22.dp)
                )
            }
        }

        // CONTROLS OVERLAY (never in PiP)
        AnimatedVisibility(
            visible = !isInPip && controlsVisible && !isLocked,
            enter = fadeIn(tween(150)),
            exit = fadeOut(tween(150))
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            listOf(
                                Color.Black.copy(alpha = 0.8f), // legibility-ok: over video scrim
                                Color.Transparent,
                                Color.Black.copy(alpha = 0.85f) // legibility-ok: over video scrim
                            )
                        )
                    )
            ) {
                // TOP BAR
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .align(Alignment.TopCenter)
                        .displayCutoutPadding()
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(
                        onClick = {
                            if (isLandscape) {
                                toggleOrientation()
                            } else {
                                onBackClick()
                            }
                        },
                        modifier = Modifier
                            .size(40.dp)
                            .testTag("player_back_button")
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                            tint = Color.White, // legibility-ok: over video scrim
                            modifier = Modifier.size(24.dp)
                        )
                    }

                    Spacer(modifier = Modifier.width(6.dp))

                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = title,
                            style = MaterialTheme.typography.titleMedium.copy(
                                fontWeight = FontWeight.Bold,
                                color = Color.White // legibility-ok: over video scrim
                            ),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        if (!subtitleInfo.isNullOrBlank()) {
                            Text(
                                text = subtitleInfo,
                                style = MaterialTheme.typography.bodySmall.copy(
                                    color = Color.White.copy(alpha = 0.85f), // legibility-ok: over video scrim
                                    fontSize = 11.sp
                                ),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }

                    if (isLandscape) {
                        // Landscape controls in top bar
                        IconButton(
                            onClick = {
                                currentResizeMode = when (currentResizeMode) {
                                    ResizeMode.FIT -> ResizeMode.ZOOM
                                    ResizeMode.ZOOM -> ResizeMode.FILL
                                    ResizeMode.FILL -> ResizeMode.FIT
                                }
                            }
                        ) {
                            Icon(Icons.Default.AspectRatio, contentDescription = "Aspect Ratio", tint = Color.White) // legibility-ok: over video scrim
                        }

                        TextButton(onClick = { showSpeedDialog = true }) {
                            Text("${playbackSpeed}x", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 12.sp) // legibility-ok: over video scrim
                        }

                        IconButton(onClick = { showSubtitleDialog = true }) {
                            Icon(Icons.Default.ClosedCaption, contentDescription = "Subtitles", tint = Color.White) // legibility-ok: over video scrim
                        }

                        // Floating Screen (PiP) button
                        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                            IconButton(
                                onClick = {
                                    try {
                                        val pipParams = android.app.PictureInPictureParams.Builder()
                                            .setAspectRatio(android.util.Rational(16, 9))
                                            .build()
                                        activity?.enterPictureInPictureMode(pipParams)
                                    } catch (_: Exception) {}
                                },
                                modifier = Modifier.size(38.dp).testTag("player_pip_button")
                            ) {
                                Icon(
                                    imageVector = Icons.Default.PictureInPictureAlt,
                                    contentDescription = "Floating Screen (PiP)",
                                    tint = Color.White, // legibility-ok: over video scrim
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }

                        if (sources.isNotEmpty()) {
                            Surface(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(6.dp))
                                    .clickable { showQualityDialog = true },
                                color = MaterialTheme.colorScheme.primary
                            ) {
                                Text(
                                    text = currentSource?.resolution ?: "AUTO",
                                    style = MaterialTheme.typography.labelSmall.copy(
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.onPrimary,
                                        fontSize = 11.sp
                                    ),
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                )
                            }
                        }
                    } else {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                                IconButton(
                                    onClick = {
                                        try {
                                            val pipParams = android.app.PictureInPictureParams.Builder()
                                                .setAspectRatio(android.util.Rational(16, 9))
                                                .build()
                                            activity?.enterPictureInPictureMode(pipParams)
                                        } catch (_: Exception) {}
                                    },
                                    modifier = Modifier.size(38.dp).testTag("player_pip_button")
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.PictureInPictureAlt,
                                        contentDescription = "Floating Screen (PiP)",
                                        tint = Color.White, // legibility-ok: over video scrim
                                        modifier = Modifier.size(20.dp)
                                    )
                                }
                            }

                            IconButton(
                                onClick = { showSettingsSheet = true },
                                modifier = Modifier.size(38.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Settings,
                                    contentDescription = "Player Settings",
                                    tint = Color.White, // legibility-ok: over video scrim
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }
                    }
                }

                // CENTER CONTROLS
                Row(
                    modifier = Modifier
                        .align(Alignment.Center)
                        .padding(horizontal = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center
                ) {
                    if (isLandscape && isSeries) {
                        IconButton(
                            onClick = onPreviousEpisode,
                            enabled = hasPreviousEpisode,
                            modifier = Modifier
                                .size(44.dp)
                                .background(MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.85f), CircleShape)
                                .then(if (isHighContrast) Modifier.border(1.5.dp, MaterialTheme.colorScheme.outline, CircleShape) else Modifier)
                        ) {
                            Icon(
                                imageVector = Icons.Default.SkipPrevious,
                                contentDescription = "Previous Episode",
                                tint = if (hasPreviousEpisode) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f),
                                modifier = Modifier.size(24.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(24.dp))
                    }

                    // Rewind 10s
                    IconButton(
                        onClick = {
                            val target = (exoPlayer.currentPosition - 10000L).coerceAtLeast(0L)
                            exoPlayer.seekTo(target)
                        },
                        modifier = Modifier
                            .size(48.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.85f), CircleShape)
                            .then(if (isHighContrast) Modifier.border(1.5.dp, MaterialTheme.colorScheme.outline, CircleShape) else Modifier)
                    ) {
                        Icon(Icons.Default.Replay10, contentDescription = "Rewind 10 seconds", tint = MaterialTheme.colorScheme.onSurface, modifier = Modifier.size(28.dp))
                    }

                    Spacer(modifier = Modifier.width(28.dp))

                    // Play / Pause Circle
                    Box(
                        modifier = Modifier
                            .size(60.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primary)
                            .then(if (isHighContrast) Modifier.border(1.5.dp, MaterialTheme.colorScheme.outline, CircleShape) else Modifier)
                            .clickable {
                                if (isPlaying) exoPlayer.pause() else exoPlayer.play()
                            }
                            .testTag("player_play_pause_button"),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                            contentDescription = if (isPlaying) "Pause" else "Play",
                            tint = MaterialTheme.colorScheme.onPrimary,
                            modifier = Modifier.size(34.dp)
                        )
                    }

                    Spacer(modifier = Modifier.width(28.dp))

                    // Forward 10s
                    IconButton(
                        onClick = {
                            val target = (exoPlayer.currentPosition + 10000L).coerceAtMost(duration)
                            exoPlayer.seekTo(target)
                        },
                        modifier = Modifier
                            .size(48.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.85f), CircleShape)
                            .then(if (isHighContrast) Modifier.border(1.5.dp, MaterialTheme.colorScheme.outline, CircleShape) else Modifier)
                    ) {
                        Icon(Icons.Default.Forward10, contentDescription = "Fast forward 10 seconds", tint = MaterialTheme.colorScheme.onSurface, modifier = Modifier.size(28.dp))
                    }

                    if (isLandscape && isSeries) {
                        Spacer(modifier = Modifier.width(24.dp))
                        IconButton(
                            onClick = onNextEpisode,
                            enabled = hasNextEpisode,
                            modifier = Modifier
                                .size(44.dp)
                                .background(MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.85f), CircleShape)
                                .then(if (isHighContrast) Modifier.border(1.5.dp, MaterialTheme.colorScheme.outline, CircleShape) else Modifier)
                        ) {
                            Icon(
                                imageVector = Icons.Default.SkipNext,
                                contentDescription = "Next Episode",
                                tint = if (hasNextEpisode) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f),
                                modifier = Modifier.size(24.dp)
                            )
                        }
                    }
                }

                // BOTTOM CONTROLS
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .align(Alignment.BottomCenter)
                        .padding(horizontal = 16.dp, vertical = 6.dp)
                ) {
                    Slider(
                        value = if (duration > 0) currentPosition.toFloat() / duration.toFloat() else 0f,
                        onValueChange = { frac ->
                            currentPosition = (frac * duration).toLong()
                        },
                        onValueChangeFinished = {
                            exoPlayer.seekTo(currentPosition)
                        },
                        colors = SliderDefaults.colors(
                            thumbColor = accentColor,
                            activeTrackColor = accentColor,
                            inactiveTrackColor = Color.White.copy(alpha = 0.3f) // legibility-ok: seek bar inactive track
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(20.dp)
                            .testTag("player_seek_slider")
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = "${formatTime(currentPosition)} / ${formatTime(duration)}",
                            style = MaterialTheme.typography.bodySmall.copy(
                                color = Color.White, // legibility-ok: over video scrim
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 12.sp
                            )
                        )

                        IconButton(
                            onClick = { toggleOrientation() },
                            modifier = Modifier
                                .size(36.dp)
                                .testTag("player_rotate_button")
                        ) {
                            Icon(
                                imageVector = if (isLandscape) Icons.Default.FullscreenExit else Icons.Default.Fullscreen,
                                contentDescription = if (isLandscape) "Exit Fullscreen" else "Fullscreen Landscape",
                                tint = Color.White, // legibility-ok: over video scrim
                                modifier = Modifier.size(26.dp)
                            )
                        }
                    }
                }
            }
        }
    }

    // SETTINGS DIALOG
    if (showSettingsSheet) {
        AlertDialog(
            onDismissRequest = { showSettingsSheet = false },
            title = {
                Text(
                    text = "Playback Settings",
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
                )
            },
            text = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(MaterialTheme.colorScheme.surfaceContainer)
                            .border(
                                if (isHighContrast) 1.5.dp else 0.5.dp,
                                MaterialTheme.colorScheme.outlineVariant,
                                RoundedCornerShape(12.dp)
                            )
                            .clickable {
                                showSettingsSheet = false
                                showQualityDialog = true
                            }
                            .padding(14.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text("Video Quality", fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onSurface)
                            Text(currentSource?.resolution ?: "Auto", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                        }
                    }

                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(MaterialTheme.colorScheme.surfaceContainer)
                            .border(
                                if (isHighContrast) 1.5.dp else 0.5.dp,
                                MaterialTheme.colorScheme.outlineVariant,
                                RoundedCornerShape(12.dp)
                            )
                            .clickable {
                                showSettingsSheet = false
                                showSubtitleDialog = true
                            }
                            .padding(14.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text("Subtitles & Style", fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onSurface)
                            val subName = if (selectedCaptionIndex >= 0) captions.getOrNull(selectedCaptionIndex)?.lanName ?: "On" else "Off"
                            Text("$subName • ${(captionBgOpacity * 100).roundToInt()}% bg", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                        }
                    }

                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(MaterialTheme.colorScheme.surfaceContainer)
                            .border(
                                if (isHighContrast) 1.5.dp else 0.5.dp,
                                MaterialTheme.colorScheme.outlineVariant,
                                RoundedCornerShape(12.dp)
                            )
                            .clickable {
                                showSettingsSheet = false
                                showSpeedDialog = true
                            }
                            .padding(14.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text("Playback Speed", fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onSurface)
                            Text("${playbackSpeed}x", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                        }
                    }

                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(MaterialTheme.colorScheme.surfaceContainer)
                            .border(
                                if (isHighContrast) 1.5.dp else 0.5.dp,
                                MaterialTheme.colorScheme.outlineVariant,
                                RoundedCornerShape(12.dp)
                            )
                            .clickable {
                                currentResizeMode = when (currentResizeMode) {
                                    ResizeMode.FIT -> ResizeMode.ZOOM
                                    ResizeMode.ZOOM -> ResizeMode.FILL
                                    ResizeMode.FILL -> ResizeMode.FIT
                                }
                            }
                            .padding(14.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text("Aspect Ratio", fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onSurface)
                            Text(currentResizeMode.label, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showSettingsSheet = false }) {
                    Text("Close", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                }
            },
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            shape = RoundedCornerShape(20.dp)
        )
    }

    // QUALITY DIALOG
    if (showQualityDialog) {
        AlertDialog(
            onDismissRequest = { showQualityDialog = false },
            title = { Text(text = "Video Resolution", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    sources.forEachIndexed { index, source ->
                        val isSelected = index == selectedSourceIndex
                        val selColors = selectableColors(isSelected)
                        val shape = RoundedCornerShape(12.dp)
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(shape)
                                .background(selColors.container)
                                .border(
                                    if (isHighContrast) 1.5.dp else if (isSelected) 1.dp else 0.5.dp,
                                    selColors.border,
                                    shape
                                )
                                .clickable {
                                    selectedSourceIndex = index
                                    showQualityDialog = false
                                }
                                .padding(12.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(
                                        imageVector = if (isSelected) Icons.Default.CheckCircle else Icons.Default.RadioButtonUnchecked,
                                        contentDescription = null,
                                        tint = selColors.content,
                                        modifier = Modifier.size(18.dp)
                                    )
                                    Spacer(modifier = Modifier.width(10.dp))
                                    Text(
                                        text = source.resolution,
                                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                        color = selColors.content
                                    )
                                }
                                source.formattedSize?.let { sz ->
                                    Text(text = sz, style = MaterialTheme.typography.bodySmall.copy(color = selColors.secondaryContent))
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showQualityDialog = false }) {
                    Text("Cancel", color = MaterialTheme.colorScheme.primary)
                }
            },
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            shape = RoundedCornerShape(20.dp)
        )
    }

    // SUBTITLES DIALOG
    if (showSubtitleDialog) {
        AlertDialog(
            onDismissRequest = { showSubtitleDialog = false },
            title = {
                Column {
                    Text(text = "Subtitles & Captions", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
                    Spacer(modifier = Modifier.height(10.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = if (subtitleDialogTab == 0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainer,
                            modifier = Modifier
                                .weight(1f)
                                .clickable { subtitleDialogTab = 0 }
                        ) {
                            Text(
                                text = "Tracks",
                                color = if (subtitleDialogTab == 0) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                                fontWeight = FontWeight.Bold,
                                fontSize = 13.sp,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.padding(vertical = 8.dp)
                            )
                        }
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = if (subtitleDialogTab == 1) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainer,
                            modifier = Modifier
                                .weight(1f)
                                .clickable { subtitleDialogTab = 1 }
                        ) {
                            Text(
                                text = "Style & Transparency",
                                color = if (subtitleDialogTab == 1) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                                fontWeight = FontWeight.Bold,
                                fontSize = 13.sp,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.padding(vertical = 8.dp)
                            )
                        }
                    }
                }
            },
            text = {
                if (subtitleDialogTab == 0) {
                    Column(
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 300.dp)
                            .verticalScroll(rememberScrollState())
                    ) {
                        val isOffSelected = selectedCaptionIndex == -1
                        val offColors = selectableColors(isOffSelected)
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = offColors.container,
                            border = BorderStroke(if (isHighContrast) 1.5.dp else 0.5.dp, offColors.border),
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    selectedCaptionIndex = -1
                                    showSubtitleDialog = false
                                }
                        ) {
                            Row(
                                modifier = Modifier.padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = if (isOffSelected) Icons.Default.CheckCircle else Icons.Default.RadioButtonUnchecked,
                                    contentDescription = null,
                                    tint = offColors.content,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(10.dp))
                                Text("Off", color = offColors.content, fontWeight = if (isOffSelected) FontWeight.Bold else FontWeight.Normal)
                            }
                        }

                        captions.forEachIndexed { index, caption ->
                            val isHindi = caption.lan == "hi" ||
                                    caption.lanName?.contains("हिन्दी") == true ||
                                    caption.lanName?.contains("Hindi", ignoreCase = true) == true
                            val isSelected = index == selectedCaptionIndex
                            val capColors = selectableColors(isSelected)

                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = capColors.container,
                                border = BorderStroke(if (isHighContrast) 1.5.dp else if (isHindi || isSelected) 1.dp else 0.5.dp, capColors.border),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        selectedCaptionIndex = index
                                        showSubtitleDialog = false
                                    }
                            ) {
                                Row(
                                    modifier = Modifier.padding(12.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        imageVector = if (isSelected) Icons.Default.CheckCircle else Icons.Default.RadioButtonUnchecked,
                                        contentDescription = null,
                                        tint = capColors.content,
                                        modifier = Modifier.size(18.dp)
                                    )
                                    Spacer(modifier = Modifier.width(10.dp))
                                    Text(
                                        text = if (isHindi) "🇮🇳 हिन्दी (Hindi)" else (caption.lanName ?: caption.lan ?: "Subtitle"),
                                        color = capColors.content,
                                        fontWeight = if (isHindi || isSelected) FontWeight.Bold else FontWeight.Normal,
                                        modifier = Modifier.weight(1f)
                                    )
                                    if (isHindi) {
                                        Surface(
                                            shape = RoundedCornerShape(4.dp),
                                            color = MaterialTheme.colorScheme.tertiaryContainer
                                        ) {
                                            Text(
                                                text = "Hindi Sub",
                                                color = MaterialTheme.colorScheme.onTertiaryContainer,
                                                fontSize = 11.sp,
                                                fontWeight = FontWeight.Bold,
                                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                            )
                                        }
                                    }
                                }
                            }
                        }

                        if (captions.isEmpty()) {
                            Text(
                                text = "No subtitle tracks available for this title.",
                                style = MaterialTheme.typography.bodySmall.copy(color = MaterialTheme.colorScheme.onSurfaceVariant),
                                modifier = Modifier.padding(vertical = 8.dp)
                            )
                        }
                    }
                } else {
                    // Style & Transparency Controls
                    Column(
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 350.dp)
                            .verticalScroll(rememberScrollState())
                    ) {
                        Text(
                            text = "Live Preview",
                            style = MaterialTheme.typography.labelMedium.copy(color = MaterialTheme.colorScheme.onSurfaceVariant, fontWeight = FontWeight.Bold)
                        )
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = MaterialTheme.colorScheme.surfaceContainer,
                            border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.outlineVariant),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 14.dp, horizontal = 8.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Surface(
                                    shape = RoundedCornerShape(6.dp),
                                    color = Color.Black.copy(alpha = captionBgOpacity) // legibility-ok: subtitles
                                ) {
                                    Text(
                                        text = "Sample Subtitle / ক্যাপশন প্রিভিউ",
                                        style = MaterialTheme.typography.bodyMedium.copy(
                                            color = Color.White.copy(alpha = captionTextOpacity), // legibility-ok: subtitles
                                            fontWeight = FontWeight.Bold,
                                            shadow = if (captionBgOpacity < 0.40f) {
                                                Shadow(
                                                    color = Color.Black.copy(alpha = 0.95f), // legibility-ok: subtitles
                                                    offset = Offset(2f, 2f),
                                                    blurRadius = 4f
                                                )
                                            } else null
                                        ),
                                        modifier = Modifier.padding(
                                            horizontal = if (captionBgOpacity > 0.05f) 10.dp else 4.dp,
                                            vertical = if (captionBgOpacity > 0.05f) 5.dp else 2.dp
                                        ),
                                        textAlign = TextAlign.Center
                                    )
                                }
                            }
                        }

                        // Background Transparency Controls
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    text = "Background Opacity",
                                    style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
                                )
                                Text(
                                    text = "${(captionBgOpacity * 100).roundToInt()}% (${if (captionBgOpacity == 0f) "Transparent" else if (captionBgOpacity >= 0.95f) "Solid" else "${(100 - (captionBgOpacity * 100)).roundToInt()}% transparent"})",
                                    style = MaterialTheme.typography.bodySmall.copy(color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                                )
                            }
                            Slider(
                                value = captionBgOpacity,
                                onValueChange = { newVal ->
                                    captionBgOpacity = newVal
                                    CaptionPreferences.setBackgroundOpacity(context, newVal)
                                },
                                valueRange = 0.0f..1.0f,
                                colors = SliderDefaults.colors(
                                    thumbColor = accentColor,
                                    activeTrackColor = accentColor,
                                    inactiveTrackColor = MaterialTheme.colorScheme.surfaceContainerHighest
                                )
                            )
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                listOf(
                                    0.0f to "0% None",
                                    0.25f to "25%",
                                    0.50f to "50%",
                                    0.75f to "75%",
                                    1.0f to "Solid"
                                ).forEach { (preset, label) ->
                                    val isSelected = kotlin.math.abs(captionBgOpacity - preset) < 0.06f
                                    val presetColors = selectableColors(isSelected)
                                    Surface(
                                        shape = RoundedCornerShape(6.dp),
                                        color = presetColors.container,
                                        border = if (isSelected || isHighContrast) BorderStroke(1.dp, presetColors.border) else null,
                                        modifier = Modifier
                                            .weight(1f)
                                            .clickable {
                                                captionBgOpacity = preset
                                                CaptionPreferences.setBackgroundOpacity(context, preset)
                                            }
                                    ) {
                                        Text(
                                            text = label,
                                            color = presetColors.content,
                                            fontSize = 11.sp,
                                            textAlign = TextAlign.Center,
                                            modifier = Modifier.padding(vertical = 5.dp)
                                        )
                                    }
                                }
                            }
                        }

                        // Text Transparency Controls
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    text = "Text Opacity",
                                    style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
                                )
                                Text(
                                    text = "${(captionTextOpacity * 100).roundToInt()}% (${(100 - (captionTextOpacity * 100)).roundToInt()}% transparent)",
                                    style = MaterialTheme.typography.bodySmall.copy(color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                                )
                            }
                            Slider(
                                value = captionTextOpacity,
                                onValueChange = { newVal ->
                                    captionTextOpacity = newVal
                                    CaptionPreferences.setTextOpacity(context, newVal)
                                },
                                valueRange = 0.2f..1.0f,
                                colors = SliderDefaults.colors(
                                    thumbColor = accentColor,
                                    activeTrackColor = accentColor,
                                    inactiveTrackColor = MaterialTheme.colorScheme.surfaceContainerHighest
                                )
                            )
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                listOf(
                                    0.35f to "35%",
                                    0.50f to "50%",
                                    0.70f to "70%",
                                    0.85f to "85%",
                                    1.0f to "100%"
                                ).forEach { (preset, label) ->
                                    val isSelected = kotlin.math.abs(captionTextOpacity - preset) < 0.06f
                                    val presetColors = selectableColors(isSelected)
                                    Surface(
                                        shape = RoundedCornerShape(6.dp),
                                        color = presetColors.container,
                                        border = if (isSelected || isHighContrast) BorderStroke(1.dp, presetColors.border) else null,
                                        modifier = Modifier
                                            .weight(1f)
                                            .clickable {
                                                captionTextOpacity = preset
                                                CaptionPreferences.setTextOpacity(context, preset)
                                            }
                                    ) {
                                        Text(
                                            text = label,
                                            color = presetColors.content,
                                            fontSize = 11.sp,
                                            textAlign = TextAlign.Center,
                                            modifier = Modifier.padding(vertical = 5.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showSubtitleDialog = false }) {
                    Text("Done", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                }
            },
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
        )
    }

    // SPEED DIALOG
    if (showSpeedDialog) {
        val speeds = listOf(0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 2.0f)
        AlertDialog(
            onDismissRequest = { showSpeedDialog = false },
            title = { Text(text = "Playback Speed", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    speeds.forEach { speed ->
                        val isSelected = playbackSpeed == speed
                        val selColors = selectableColors(isSelected)
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = selColors.container,
                            border = BorderStroke(if (isHighContrast) 1.5.dp else if (isSelected) 1.dp else 0.5.dp, selColors.border),
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    playbackSpeed = speed
                                    exoPlayer.setPlaybackSpeed(speed)
                                    showSpeedDialog = false
                                }
                        ) {
                            Row(
                                modifier = Modifier.padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = if (isSelected) Icons.Default.CheckCircle else Icons.Default.RadioButtonUnchecked,
                                    contentDescription = null,
                                    tint = selColors.content,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(10.dp))
                                Text("${speed}x", color = selColors.content, fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal)
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showSpeedDialog = false }) {
                    Text("Cancel", color = MaterialTheme.colorScheme.primary)
                }
            },
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
        )
    }
}

private fun formatTime(millis: Long): String {
    val totalSeconds = (millis / 1000).coerceAtLeast(0)
    val seconds = totalSeconds % 60
    val minutes = (totalSeconds / 60) % 60
    val hours = totalSeconds / 3600
    return if (hours > 0) {
        String.format(Locale.US, "%d:%02d:%02d", hours, minutes, seconds)
    } else {
        String.format(Locale.US, "%02d:%02d", minutes, seconds)
    }
}

private fun Context.findActivity(): Activity? {
    var currentContext = this
    while (currentContext is ContextWrapper) {
        if (currentContext is Activity) return currentContext
        currentContext = currentContext.baseContext
    }
    return null
}
