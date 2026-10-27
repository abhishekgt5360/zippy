package com.example.ui.screens.appearance

import android.os.Build
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.ui.theme.HighContrastScheme
import com.example.ui.theme.OledScheme
import com.example.ui.theme.PaperScheme
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppearanceScreen(
    currentThemeMode: ThemeMode,
    onThemeSelected: (ThemeMode) -> Unit,
    onBackClick: () -> Unit,
    isAdultEnabled: Boolean = false,
    onAdultToggle: (Boolean) -> Unit = {},
    onOpenDevCredit: () -> Unit = {}
) {
    var showAdultConfirmDialog by remember { mutableStateOf(false) }
    var adultPin by remember { mutableStateOf("") }
    var adultPinError by remember { mutableStateOf(false) }
    val adultPinFocusRequester = remember { FocusRequester() }
    var expandedThemeDropdown by remember { mutableStateOf(false) }
    val isDynamicSupported = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
    val context = LocalContext.current
    var resumeNoticeConfig by remember { mutableStateOf(ResumeNoticePreferences.load(context)) }
    var resumeNoticeExpanded by remember { mutableStateOf(false) }
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
            Card(
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Choose theme",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.weight(1f)
                    )

                    Spacer(modifier = Modifier.width(12.dp))

                    ExposedDropdownMenuBox(
                        expanded = expandedThemeDropdown,
                        onExpandedChange = { expandedThemeDropdown = it },
                        modifier = Modifier.width(160.dp)
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
                                    text = currentThemeMode.getDisplayName(),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f)
                                )
                                ExposedDropdownMenuDefaults.TrailingIcon(expanded = expandedThemeDropdown)
                            }
                        }
                        ExposedDropdownMenu(
                            expanded = expandedThemeDropdown,
                            onDismissRequest = { expandedThemeDropdown = false },
                            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
                        ) {
                            ThemeMode.entries.forEach { mode ->
                                val isSupported = mode != ThemeMode.DYNAMIC || isDynamicSupported
                                if (isSupported) {
                                    DropdownMenuItem(
                                        text = { Text(mode.getDisplayName(), color = MaterialTheme.colorScheme.onSurface) },
                                        onClick = {
                                            onThemeSelected(mode)
                                            expandedThemeDropdown = false
                                        },
                                        contentPadding = ExposedDropdownMenuDefaults.ItemContentPadding
                                    )
                                }
                            }
                        }
                    }
                }
            }

            Card(
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
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
                            text = "Show 18+ adult content preset and filters",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
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
                }
            }

            val resumeNoticeSummary = if (!resumeNoticeConfig.enabled) {
                "Off"
            } else {
                listOf(
                    ResumeNoticePreferences.durationLabel(resumeNoticeConfig.durationMs),
                    ResumeNoticePreferences.positionLabel(resumeNoticeConfig.position)
                ).joinToString(" · ")
            }

            Card(
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)
            ) {
                Column(modifier = Modifier.fillMaxWidth()) {
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
                        Spacer(modifier = Modifier.width(12.dp))
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
                            modifier = Modifier.rotate(if (resumeNoticeExpanded) 180f else 0f)
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

            // Application developer credit
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

@Composable
private fun ThemeOptionCard(
    title: String,
    subtitle: String,
    selected: Boolean,
    onClick: () -> Unit,
    primarySwatch: Color,
    backgroundSwatch: Color,
    outlineSwatch: Color? = null,
    enabled: Boolean = true
) {
    Card(
        onClick = onClick,
        enabled = enabled,
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
            disabledContainerColor = MaterialTheme.colorScheme.surfaceContainerLow.copy(alpha = 0.5f)
        ),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Swatch Preview
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(backgroundSwatch)
                    .border(2.dp, outlineSwatch ?: primarySwatch, CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Box(
                    modifier = Modifier
                        .size(16.dp)
                        .clip(CircleShape)
                        .background(primarySwatch)
                )
            }

            Spacer(modifier = Modifier.width(16.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (enabled) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f)
                )
            }

            Spacer(modifier = Modifier.width(8.dp))

            RadioButton(
                selected = selected,
                onClick = null, // handled by Card click
                enabled = enabled
            )
        }
    }
}
