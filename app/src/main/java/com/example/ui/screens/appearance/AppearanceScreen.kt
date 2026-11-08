package com.example.ui.screens.appearance

import android.os.Build
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.theme.ThemeMode
import com.example.util.DevCreditManager
import com.example.util.ResumeNoticePosition
import com.example.util.ResumeNoticePreferences
import kotlinx.coroutines.delay

private const val ADULT_UNLOCK_PIN = "1027"

fun ThemeMode.getDisplayName(): String = when (this) {
    ThemeMode.CRIMSON -> "Crimson"
    ThemeMode.OLED -> "OLED Black"
    ThemeMode.DYNAMIC -> "System Colors"
    ThemeMode.HIGH_CONTRAST -> "High Contrast"
    ThemeMode.PAPER -> "Paper"
}

fun ThemeMode.getDescription(): String = when (this) {
    ThemeMode.CRIMSON -> "Classic deep cinematic theme with crimson accents"
    ThemeMode.OLED -> "Pure true-black background optimized for OLED displays"
    ThemeMode.DYNAMIC -> "Adapts to your Android system wallpaper colors"
    ThemeMode.HIGH_CONTRAST -> "High readability with reinforced borders & contrasts"
    ThemeMode.PAPER -> "Warm reader-friendly tint with softened contrast"
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppearanceScreen(
    currentThemeMode: ThemeMode,
    onThemeSelected: (ThemeMode) -> Unit,
    onBackClick: () -> Unit,
    isAdultEnabled: Boolean = false,
    onAdultToggle: (Boolean) -> Unit = {},
    onOpenDevCredit: () -> Unit = {},
    tmdbApiKey: String = "",
    onTmdbApiKeySave: (String) -> Unit = {}
) {
    var inputApiKey by remember(tmdbApiKey) { mutableStateOf(tmdbApiKey) }
    var showApiKeySavedNotice by remember { mutableStateOf(false) }
    var showAdultConfirmDialog by remember { mutableStateOf(false) }
    var adultPin by remember { mutableStateOf("") }
    var adultPinError by remember { mutableStateOf(false) }
    val adultPinFocusRequester = remember { FocusRequester() }

    var themeCardExpanded by rememberSaveable { mutableStateOf(false) }
    var tmdbKeyCardExpanded by rememberSaveable { mutableStateOf(false) }
    var adultCardExpanded by rememberSaveable { mutableStateOf(false) }
    var resumeNoticeExpanded by rememberSaveable { mutableStateOf(false) }

    val isDynamicSupported = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
    val context = LocalContext.current
    var resumeNoticeConfig by remember { mutableStateOf(ResumeNoticePreferences.load(context)) }

    val updateResumeNotice: (com.example.util.ResumeNoticeConfig) -> Unit = { updated ->
        resumeNoticeConfig = updated
        ResumeNoticePreferences.save(context, updated)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                )
            )
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // ── 1. Theme & Appearance (Collapsible) ──────────────────────────
            val themeChevronRotation by animateFloatAsState(
                targetValue = if (themeCardExpanded) 180f else 0f,
                label = "themeChevron"
            )

            Card(
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .animateContentSize()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { themeCardExpanded = !themeCardExpanded }
                            .padding(16.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Theme & Appearance",
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurface,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = currentThemeMode.getDisplayName(),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        Surface(
                            shape = RoundedCornerShape(4.dp),
                            color = MaterialTheme.colorScheme.primaryContainer
                        ) {
                            Text(
                                text = currentThemeMode.getDisplayName().uppercase(),
                                style = MaterialTheme.typography.labelSmall,
                                fontSize = 9.sp,
                                fontWeight = FontWeight.ExtraBold,
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        Icon(
                            imageVector = Icons.Filled.KeyboardArrowDown,
                            contentDescription = if (themeCardExpanded) "Collapse theme settings" else "Expand theme settings",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.rotate(themeChevronRotation)
                        )
                    }

                    if (themeCardExpanded) {
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            ThemeMode.entries.forEach { mode ->
                                val isSupported = mode != ThemeMode.DYNAMIC || isDynamicSupported
                                if (isSupported) {
                                    val isSelected = currentThemeMode == mode
                                    Surface(
                                        shape = RoundedCornerShape(8.dp),
                                        color = if (isSelected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
                                        border = BorderStroke(
                                            1.dp,
                                            if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant
                                        ),
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clickable { onThemeSelected(mode) }
                                    ) {
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(12.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            RadioButton(
                                                selected = isSelected,
                                                onClick = { onThemeSelected(mode) },
                                                colors = RadioButtonDefaults.colors(
                                                    selectedColor = MaterialTheme.colorScheme.primary,
                                                    unselectedColor = MaterialTheme.colorScheme.onSurfaceVariant
                                                )
                                            )
                                            Spacer(modifier = Modifier.width(8.dp))
                                            Column(modifier = Modifier.weight(1f)) {
                                                Row(verticalAlignment = Alignment.CenterVertically) {
                                                    Text(
                                                        text = mode.getDisplayName(),
                                                        style = MaterialTheme.typography.bodyMedium,
                                                        fontWeight = FontWeight.Bold,
                                                        color = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface
                                                    )
                                                    if (isSelected) {
                                                        Spacer(modifier = Modifier.width(6.dp))
                                                        Surface(
                                                            shape = RoundedCornerShape(4.dp),
                                                            color = MaterialTheme.colorScheme.primary
                                                        ) {
                                                            Text(
                                                                text = "ACTIVE",
                                                                style = MaterialTheme.typography.labelSmall,
                                                                fontSize = 9.sp,
                                                                fontWeight = FontWeight.ExtraBold,
                                                                color = MaterialTheme.colorScheme.onPrimary,
                                                                modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                                                            )
                                                        }
                                                    }
                                                }
                                                Spacer(modifier = Modifier.height(2.dp))
                                                Text(
                                                    text = mode.getDescription(),
                                                    style = MaterialTheme.typography.bodySmall,
                                                    color = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.85f) else MaterialTheme.colorScheme.onSurfaceVariant
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // ── 2. TMDB API Key (Collapsible) ────────────────────────────────
            val tmdbKeyChevronRotation by animateFloatAsState(
                targetValue = if (tmdbKeyCardExpanded) 180f else 0f,
                label = "tmdbKeyChevron"
            )

            Card(
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .animateContentSize()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { tmdbKeyCardExpanded = !tmdbKeyCardExpanded }
                            .padding(16.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "TMDB API Key",
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurface,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = if (tmdbApiKey.isNotBlank()) "Personal key active" else "Not set · Using AnyFilm provider",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        Surface(
                            shape = RoundedCornerShape(4.dp),
                            color = if (tmdbApiKey.isNotBlank()) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh
                        ) {
                            Text(
                                text = if (tmdbApiKey.isNotBlank()) "ACTIVE" else "NOT SET",
                                style = MaterialTheme.typography.labelSmall,
                                fontSize = 9.sp,
                                fontWeight = FontWeight.ExtraBold,
                                color = if (tmdbApiKey.isNotBlank()) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        Icon(
                            imageVector = Icons.Filled.KeyboardArrowDown,
                            contentDescription = if (tmdbKeyCardExpanded) "Collapse TMDB key settings" else "Expand TMDB key settings",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.rotate(tmdbKeyChevronRotation)
                        )
                    }

                    if (tmdbKeyCardExpanded) {
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Text(
                                text = "Personal API key for TMDB catalogue, search & artwork. Leave empty to use AnyFilm provider catalogue without TMDB.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )

                            OutlinedTextField(
                                value = inputApiKey,
                                onValueChange = { inputApiKey = it.trim() },
                                modifier = Modifier.fillMaxWidth(),
                                placeholder = {
                                    Text(
                                        text = "Enter 32-character API key",
                                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                                        fontSize = 13.sp
                                    )
                                },
                                singleLine = true,
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedBorderColor = MaterialTheme.colorScheme.primary,
                                    unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant,
                                    focusedTextColor = MaterialTheme.colorScheme.onSurface,
                                    unfocusedTextColor = MaterialTheme.colorScheme.onSurface
                                ),
                                trailingIcon = {
                                    if (inputApiKey.isNotBlank()) {
                                        IconButton(onClick = { inputApiKey = "" }) {
                                            Icon(
                                                Icons.Default.Clear,
                                                contentDescription = "Clear",
                                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                    }
                                }
                            )

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Button(
                                    onClick = {
                                        onTmdbApiKeySave(inputApiKey)
                                        showApiKeySavedNotice = true
                                    },
                                    enabled = inputApiKey != tmdbApiKey,
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Text("Save Key")
                                }

                                if (tmdbApiKey.isNotBlank()) {
                                    OutlinedButton(
                                        onClick = {
                                            inputApiKey = ""
                                            onTmdbApiKeySave("")
                                        }
                                    ) {
                                        Text("Remove")
                                    }
                                }
                            }

                            if (showApiKeySavedNotice) {
                                LaunchedEffect(showApiKeySavedNotice) {
                                    delay(2500)
                                    showApiKeySavedNotice = false
                                }
                                Text(
                                    text = if (tmdbApiKey.isNotBlank()) "✓ TMDB API key saved successfully" else "TMDB API key removed",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.primary,
                                    fontWeight = FontWeight.Medium
                                )
                            }

                            Text(
                                text = "Get a free API key at themoviedb.org/settings/api",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                fontSize = 11.sp
                            )
                        }
                    }
                }
            }

            // ── 3. Adult Content (18+) (Collapsible) ──────────────────────────
            val adultChevronRotation by animateFloatAsState(
                targetValue = if (adultCardExpanded) 180f else 0f,
                label = "adultChevron"
            )

            Card(
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .animateContentSize()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { adultCardExpanded = !adultCardExpanded }
                            .padding(16.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Adult content (18+)",
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurface,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = if (isAdultEnabled) "Unlocked · 18+ content visible" else "Locked · 18+ content hidden",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        Switch(
                            checked = isAdultEnabled,
                            onCheckedChange = { checked ->
                                if (checked) {
                                    adultPin = ""
                                    adultPinError = false
                                    showAdultConfirmDialog = true
                                } else {
                                    onAdultToggle(false)
                                }
                            },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = MaterialTheme.colorScheme.onPrimary,
                                checkedTrackColor = MaterialTheme.colorScheme.primary,
                                uncheckedThumbColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                uncheckedTrackColor = MaterialTheme.colorScheme.surfaceContainerHigh
                            )
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Icon(
                            imageVector = Icons.Filled.KeyboardArrowDown,
                            contentDescription = if (adultCardExpanded) "Collapse adult settings" else "Expand adult settings",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.rotate(adultChevronRotation)
                        )
                    }

                    if (adultCardExpanded) {
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text(
                                text = "Protects your feed from adult titles and categories. Unlocking requires a 4-digit security PIN.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            if (isAdultEnabled) {
                                OutlinedButton(
                                    onClick = { onAdultToggle(false) },
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text("Lock Adult Content Now")
                                }
                            }
                        }
                    }
                }
            }

            // ── 4. Resume message (Collapsible) ──────────────────────────────
            val resumeNoticeSummary = if (!resumeNoticeConfig.enabled) {
                "Off"
            } else {
                listOf(
                    ResumeNoticePreferences.durationLabel(resumeNoticeConfig.durationMs),
                    ResumeNoticePreferences.positionLabel(resumeNoticeConfig.position)
                ).joinToString(" · ")
            }

            val resumeChevronRotation by animateFloatAsState(
                targetValue = if (resumeNoticeExpanded) 180f else 0f,
                label = "resumeChevron"
            )

            Card(
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .animateContentSize()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { resumeNoticeExpanded = !resumeNoticeExpanded }
                            .padding(16.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Resume message",
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurface,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = if (resumeNoticeExpanded) {
                                    "Shown when playback picks up where you left off"
                                } else {
                                    resumeNoticeSummary
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        Switch(
                            checked = resumeNoticeConfig.enabled,
                            onCheckedChange = { enabled ->
                                updateResumeNotice(resumeNoticeConfig.copy(enabled = enabled))
                            },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = MaterialTheme.colorScheme.onPrimary,
                                checkedTrackColor = MaterialTheme.colorScheme.primary,
                                uncheckedThumbColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                uncheckedTrackColor = MaterialTheme.colorScheme.surfaceContainerHigh
                            )
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Icon(
                            imageVector = Icons.Filled.KeyboardArrowDown,
                            contentDescription = if (resumeNoticeExpanded) {
                                "Collapse resume message settings"
                            } else {
                                "Expand resume message settings"
                            },
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.rotate(resumeChevronRotation)
                        )
                    }

                    if (resumeNoticeExpanded) {
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "Display for",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                SettingsDropdown(
                                    selectedLabel = ResumeNoticePreferences.durationLabel(resumeNoticeConfig.durationMs),
                                    options = ResumeNoticePreferences.DURATION_OPTIONS,
                                    optionLabel = { ResumeNoticePreferences.durationLabel(it) },
                                    onSelected = { duration ->
                                        updateResumeNotice(resumeNoticeConfig.copy(durationMs = duration))
                                    },
                                    modifier = Modifier.width(170.dp)
                                )
                            }

                            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text(
                                    text = "Position",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    ResumeNoticePosition.entries.forEach { position ->
                                        FilterChip(
                                            selected = resumeNoticeConfig.position == position,
                                            onClick = {
                                                updateResumeNotice(resumeNoticeConfig.copy(position = position))
                                            },
                                            label = { Text(ResumeNoticePreferences.positionLabel(position)) }
                                        )
                                    }
                                }
                            }

                            OutlinedTextField(
                                value = resumeNoticeConfig.messageTemplate,
                                onValueChange = { message ->
                                    updateResumeNotice(resumeNoticeConfig.copy(messageTemplate = message))
                                },
                                label = { Text("Message") },
                                supportingText = { Text("Use {time} for the timestamp") },
                                singleLine = false,
                                maxLines = 3,
                                modifier = Modifier.fillMaxWidth()
                            )

                            OutlinedTextField(
                                value = resumeNoticeConfig.actionLabel,
                                onValueChange = { label ->
                                    updateResumeNotice(resumeNoticeConfig.copy(actionLabel = label))
                                },
                                label = { Text("Action button") },
                                placeholder = { Text(ResumeNoticePreferences.DEFAULT_ACTION_LABEL) },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth()
                            )

                            TextButton(
                                onClick = { updateResumeNotice(ResumeNoticePreferences.reset(context)) },
                                contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp)
                            ) {
                                Text(
                                    text = "Reset resume message",
                                    style = MaterialTheme.typography.labelLarge,
                                    color = MaterialTheme.colorScheme.primary,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }
                }
            }

            // ── 5. Application developer credit ──────────────────────────────
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onOpenDevCredit() }
                    .testTag("settings_dev_credit"),
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Application Developer",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurface,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = DevCreditManager.DEV_NAME,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.OpenInNew,
                        contentDescription = "Open developer credit",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }

    if (showAdultConfirmDialog) {
        LaunchedEffect(Unit) {
            delay(150)
            adultPinFocusRequester.requestFocus()
        }
        AlertDialog(
            onDismissRequest = {
                showAdultConfirmDialog = false
                adultPin = ""
                adultPinError = false
            },
            title = {
                Text(
                    text = "Enter PIN",
                    color = MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "18+ content is locked. Enter the 4-digit PIN to enable it.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    OutlinedTextField(
                        value = adultPin,
                        onValueChange = { input ->
                            adultPin = input.filter(Char::isDigit).take(4)
                            if (adultPinError) adultPinError = false
                        },
                        label = { Text("PIN") },
                        singleLine = true,
                        isError = adultPinError,
                        supportingText = if (adultPinError) {
                            { Text("Incorrect PIN. Try again.") }
                        } else {
                            null
                        },
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                        modifier = Modifier
                            .fillMaxWidth()
                            .focusRequester(adultPinFocusRequester)
                            .testTag("adult_pin_field")
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (adultPin == ADULT_UNLOCK_PIN) {
                            showAdultConfirmDialog = false
                            adultPin = ""
                            adultPinError = false
                            onAdultToggle(true)
                        } else {
                            adultPinError = true
                        }
                    },
                    enabled = adultPin.length == 4,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary
                    )
                ) {
                    Text("Unlock", fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                OutlinedButton(
                    onClick = {
                        showAdultConfirmDialog = false
                        adultPin = ""
                        adultPinError = false
                    }
                ) {
                    Text("Cancel")
                }
            },
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun <T> SettingsDropdown(
    selectedLabel: String,
    options: List<T>,
    optionLabel: (T) -> String,
    onSelected: (T) -> Unit,
    modifier: Modifier = Modifier
) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = it },
        modifier = modifier
    ) {
        Surface(
            shape = RoundedCornerShape(8.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            modifier = Modifier
                .menuAnchor(MenuAnchorType.PrimaryNotEditable)
                .fillMaxWidth()
                .height(40.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = selectedLabel,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded)
            }
        }
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
        ) {
            options.forEach { option ->
                DropdownMenuItem(
                    text = { Text(optionLabel(option), color = MaterialTheme.colorScheme.onSurface) },
                    onClick = {
                        onSelected(option)
                        expanded = false
                    },
                    contentPadding = ExposedDropdownMenuDefaults.ItemContentPadding
                )
            }
        }
    }
}
