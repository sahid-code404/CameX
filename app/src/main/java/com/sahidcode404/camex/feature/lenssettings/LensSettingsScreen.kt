package com.sahidcode404.camex.feature.lenssettings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.sahidcode404.camex.core.camera.PreviewPreferenceRegistry
import com.sahidcode404.camex.core.camera.ViewfinderFormatCapability
import com.sahidcode404.camex.core.model.FpsRange
import com.sahidcode404.camex.core.model.Size2D
import com.sahidcode404.camex.core.model.StreamFormat
import com.sahidcode404.camex.core.settings.LensSettingsStore
import kotlinx.coroutines.launch

data class LensSettingsUiModel(
    val fingerprint: String,
    val defaultLabel: String,
    val customLabel: String?,
    val facing: String,
    val visible: Boolean,
    val isOneXReference: Boolean,
    val supportsOneXReference: Boolean,
    val advanced: Boolean = false,
    /** Legacy/source-compatible field. Viewfinder resolution is no longer exposed per lens. */
    val previewSizes: List<Size2D> = emptyList(),
    val previewFpsRanges: List<FpsRange> = emptyList(),
    val selectedPreviewSize: Size2D? = null,
    val selectedPreviewFpsRange: FpsRange? = null,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LensSettingsScreen(
    lenses: List<LensSettingsUiModel>,
    onBack: () -> Unit,
    onSetVisible: (String, Boolean) -> Unit,
    onRename: (String, String?) -> Unit,
    onMove: (from: Int, to: Int) -> Unit,
    onSetOneXReference: (String) -> Unit,
    onSetPreviewSize: (String, Size2D?) -> Unit,
    onSetPreviewFps: (String, FpsRange?) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val settingsStore = remember(context) { LensSettingsStore(context) }

    var editing by remember { mutableStateOf<LensSettingsUiModel?>(null) }
    var streamEditing by remember { mutableStateOf<LensSettingsUiModel?>(null) }

    val globalFpsRange by PreviewPreferenceRegistry.globalFpsRange.collectAsState()
    val fpsOverrideEnabled by PreviewPreferenceRegistry.fpsOverrideEnabled.collectAsState()
    val highResolutionViewfinder by PreviewPreferenceRegistry.highResolutionViewfinder.collectAsState()
    val persistedPreferences by PreviewPreferenceRegistry.preferences.collectAsState()
    val viewfinderCapabilities by PreviewPreferenceRegistry.viewfinderCapabilities.collectAsState()

    val reportedFpsRanges = remember(lenses) {
        lenses.asSequence()
            .flatMap { it.previewFpsRanges.asSequence() }
            .filter { it.isValid && it.max > 0 }
            .distinct()
            .sortedWith(compareBy<FpsRange> { it.min }.thenBy { it.max })
            .toList()
    }
    val fallbackRange = remember(reportedFpsRanges) { recommendedGlobalRange(reportedFpsRanges) }
    val displayedRange = globalFpsRange ?: fallbackRange
    val supportedLowestFps = remember(reportedFpsRanges) {
        reportedFpsRanges.minOfOrNull { it.min }
    }
    val supportedHighestFps = remember(reportedFpsRanges) {
        reportedFpsRanges.maxOfOrNull { it.max }
    }

    val indexedLenses = lenses.withIndex().toList()
    val normalLenses = indexedLenses.filterNot { it.value.advanced }
    val advancedLenses = indexedLenses.filter { it.value.advanced }

    fun persistGlobalRange(range: FpsRange?) {
        onSetPreviewFps(PreviewPreferenceRegistry.GLOBAL_PREVIEW_FPS_FINGERPRINT, range)
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text("Camera settings") },
                navigationIcon = { TextButton(onClick = onBack) { Text("Back") } },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.padding(padding),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
        ) {
            item { SettingsSectionHeader("VIEWFINDER") }
            item {
                ViewfinderFrameRateCard(
                    overrideEnabled = fpsOverrideEnabled,
                    range = displayedRange,
                    hasReportedRanges = reportedFpsRanges.isNotEmpty(),
                    supportedLowestFps = supportedLowestFps,
                    supportedHighestFps = supportedHighestFps,
                    onOverrideChanged = { enabled ->
                        if (enabled && globalFpsRange == null) fallbackRange?.let(::persistGlobalRange)
                        scope.launch {
                            settingsStore.setPreviewFpsOverrideEnabled(
                                PreviewPreferenceRegistry.GLOBAL_PREVIEW_FPS_FINGERPRINT,
                                enabled,
                            )
                        }
                    },
                    onLowerChanged = { value ->
                        val basis = displayedRange ?: FpsRange(value, value)
                        persistGlobalRange(FpsRange(value, basis.max.coerceAtLeast(value)))
                    },
                    onHighChanged = { value ->
                        val basis = displayedRange ?: FpsRange(value, value)
                        persistGlobalRange(FpsRange(basis.min.coerceAtMost(value), value))
                    },
                )
            }
            item {
                HighResolutionViewfinderCard(
                    enabled = highResolutionViewfinder,
                    onChanged = { enabled ->
                        scope.launch {
                            settingsStore.setHighResolutionViewfinderEnabled(
                                PreviewPreferenceRegistry.GLOBAL_PREVIEW_FPS_FINGERPRINT,
                                enabled,
                            )
                        }
                    },
                )
            }

            item {
                Text(
                    text = "Camera stream",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
            item {
                Text(
                    text = "Choose the live Camera2 stream per optical lens. Auto uses the stable PRIVATE path. Only formats with a real bounded live implementation are selectable; capture-only formats are never faked as a viewfinder.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            itemsIndexed(
                items = lenses,
                key = { _, lens -> "viewfinder:${lens.fingerprint}" },
            ) { _, lens ->
                val selected = persistedPreferences[lens.fingerprint]?.streamFormat
                ViewfinderLensRow(
                    lens = lens,
                    selected = selected,
                    capability = viewfinderCapabilities[lens.fingerprint],
                    onClick = { streamEditing = lens },
                )
            }

            item { SettingsSectionHeader("LENS MANAGEMENT") }
            item {
                LensSettingsSectionHeader(
                    title = "NORMAL PHOTOGRAPHIC LENSES",
                    empty = normalLenses.isEmpty(),
                    emptyMessage = "No normal photographic lenses have been discovered yet.",
                )
            }
            itemsIndexed(
                items = normalLenses,
                key = { _, indexed -> "manage:${indexed.value.fingerprint}" },
            ) { sectionIndex, indexed ->
                LensPreferenceCard(
                    lens = indexed.value,
                    canMoveUp = sectionIndex > 0,
                    canMoveDown = sectionIndex < normalLenses.lastIndex,
                    onSetVisible = { onSetVisible(indexed.value.fingerprint, it) },
                    onEditName = { editing = indexed.value },
                    onMoveUp = { onMove(indexed.index, normalLenses[sectionIndex - 1].index) },
                    onMoveDown = { onMove(indexed.index, normalLenses[sectionIndex + 1].index) },
                    onSetOneXReference = { onSetOneXReference(indexed.value.fingerprint) },
                )
            }

            item {
                LensSettingsSectionHeader(
                    title = "ADVANCED DISCOVERED CAMERAS",
                    empty = advancedLenses.isEmpty(),
                    emptyMessage = "No additional camera routes have been discovered.",
                )
            }
            itemsIndexed(
                items = advancedLenses,
                key = { _, indexed -> "advanced:${indexed.value.fingerprint}" },
            ) { sectionIndex, indexed ->
                LensPreferenceCard(
                    lens = indexed.value,
                    canMoveUp = sectionIndex > 0,
                    canMoveDown = sectionIndex < advancedLenses.lastIndex,
                    onSetVisible = { onSetVisible(indexed.value.fingerprint, it) },
                    onEditName = { editing = indexed.value },
                    onMoveUp = { onMove(indexed.index, advancedLenses[sectionIndex - 1].index) },
                    onMoveDown = { onMove(indexed.index, advancedLenses[sectionIndex + 1].index) },
                    onSetOneXReference = { onSetOneXReference(indexed.value.fingerprint) },
                )
            }
        }
    }

    editing?.let { lens ->
        RenameLensDialog(
            lens = lens,
            onDismiss = { editing = null },
            onSave = { value ->
                onRename(lens.fingerprint, value)
                editing = null
            },
        )
    }

    streamEditing?.let { lens ->
        CameraStreamDialog(
            lens = lens,
            selected = persistedPreferences[lens.fingerprint]?.streamFormat,
            reportedCapabilities = viewfinderCapabilities[lens.fingerprint].orEmpty(),
            onDismiss = { streamEditing = null },
            onSelect = { format ->
                // Clear the obsolete schema-v4 per-lens resolution preference at the same time.
                onSetPreviewSize(lens.fingerprint, null)
                scope.launch { settingsStore.setPreviewStream(lens.fingerprint, format) }
                streamEditing = null
            },
        )
    }
}

@Composable
private fun ViewfinderFrameRateCard(
    overrideEnabled: Boolean,
    range: FpsRange?,
    hasReportedRanges: Boolean,
    supportedLowestFps: Int?,
    supportedHighestFps: Int?,
    onOverrideChanged: (Boolean) -> Unit,
    onLowerChanged: (Int) -> Unit,
    onHighChanged: (Int) -> Unit,
) {
    var lowerText by remember(range?.min) { mutableStateOf(range?.min?.toString().orEmpty()) }
    var highText by remember(range?.max) { mutableStateOf(range?.max?.toString().orEmpty()) }
    val fieldsEnabled = overrideEnabled && hasReportedRanges

    Card(Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("Override viewfinder frame rate setting", fontWeight = FontWeight.SemiBold)
                    Text(
                        "Off uses the camera HAL's normal photo-preview cadence. On applies the custom range only through FPS ranges reported by each active camera.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Spacer(Modifier.width(12.dp))
                Switch(
                    checked = overrideEnabled,
                    onCheckedChange = onOverrideChanged,
                    enabled = hasReportedRanges,
                )
            }

            OutlinedTextField(
                value = lowerText,
                onValueChange = { text ->
                    val digits = text.filter(Char::isDigit).take(10)
                    lowerText = digits
                    digits.toIntOrNull()?.takeIf { it > 0 }?.let(onLowerChanged)
                },
                modifier = Modifier.fillMaxWidth(),
                enabled = fieldsEnabled,
                singleLine = true,
                label = { Text("Lowest FPS") },
                suffix = { Text("fps") },
                supportingText = {
                    Text(
                        supportedLowestFps?.let {
                            "Lowest reported by the discovered camera sensors: $it fps. You can type a custom value."
                        } ?: "No Camera2 minimum FPS metadata was reported."
                    )
                },
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                    keyboardType = KeyboardType.Number,
                ),
            )

            OutlinedTextField(
                value = highText,
                onValueChange = { text ->
                    val digits = text.filter(Char::isDigit).take(10)
                    highText = digits
                    digits.toIntOrNull()?.takeIf { it > 0 }?.let(onHighChanged)
                },
                modifier = Modifier.fillMaxWidth(),
                enabled = fieldsEnabled,
                singleLine = true,
                label = { Text("Highest FPS") },
                suffix = { Text("fps") },
                supportingText = {
                    Text(
                        supportedHighestFps?.let {
                            "Highest reported by the discovered camera sensors: $it fps. You can type a custom value."
                        } ?: "No Camera2 maximum FPS metadata was reported."
                    )
                },
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                    keyboardType = KeyboardType.Number,
                ),
            )

            if (!overrideEnabled) {
                Text(
                    "Enable Override viewfinder frame rate setting to edit Lowest FPS and Highest FPS.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
                )
            }
        }
    }
}

@Composable
private fun HighResolutionViewfinderCard(
    enabled: Boolean,
    onChanged: (Boolean) -> Unit,
) {
    Card(Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text("High resolution viewfinder", fontWeight = FontWeight.SemiBold)
                Text(
                    "Automatically uses the highest regular live-viewfinder configuration reported by each active lens/profile. Unsupported routes safely fall back to Auto.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Spacer(Modifier.width(12.dp))
            Switch(checked = enabled, onCheckedChange = onChanged)
        }
    }
}

@Composable
private fun ViewfinderLensRow(
    lens: LensSettingsUiModel,
    selected: StreamFormat?,
    capability: List<ViewfinderFormatCapability>?,
    onClick: () -> Unit,
) {
    val availableLive = capability.orEmpty().filter { it.selectableLiveStream && it.regularSizes.isNotEmpty() }
    val privateFallback = lens.previewSizes.isNotEmpty()
    val enabled = availableLive.isNotEmpty() || privateFallback
    val effective = selected?.takeIf { wanted -> availableLive.any { it.format == wanted } }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(lens.customLabel ?: lens.defaultLabel, fontWeight = FontWeight.SemiBold)
                Text(
                    effective?.let(::streamFormatLabel) ?: "Auto · Camera2 PRIVATE",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Text(if (enabled) "Open" else "Unavailable")
        }
    }
}

@Composable
private fun CameraStreamDialog(
    lens: LensSettingsUiModel,
    selected: StreamFormat?,
    reportedCapabilities: List<ViewfinderFormatCapability>,
    onDismiss: () -> Unit,
    onSelect: (StreamFormat?) -> Unit,
) {
    val selectable = reportedCapabilities
        .filter { it.selectableLiveStream && it.regularSizes.isNotEmpty() }
        .map { it.format }
        .distinct()
        .let { reported ->
            if (reported.isEmpty() && lens.previewSizes.isNotEmpty()) listOf(StreamFormat.PRIVATE)
            else reported
        }
        .sortedBy { it.ordinal }
    val reportedOnly = reportedCapabilities
        .filterNot { it.selectableLiveStream }
        .map { it.format }
        .distinct()
        .sortedBy { it.ordinal }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Camera stream · ${lens.customLabel ?: lens.defaultLabel}") },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 520.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                ChoiceRow(
                    selected = selected == null,
                    label = "Auto (recommended)",
                    onClick = { onSelect(null) },
                )
                selectable.forEach { format ->
                    ChoiceRow(
                        selected = selected == format,
                        label = streamFormatLabel(format),
                        onClick = { onSelect(format) },
                    )
                }
                if (reportedOnly.isNotEmpty()) {
                    Text(
                        "Reported non-viewfinder outputs",
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(top = 10.dp),
                    )
                    Text(
                        "Detected from this lens but intentionally not selectable as repeating viewfinder streams.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    reportedOnly.forEach { format ->
                        Text(
                            streamFormatLabel(format),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 5.dp),
                        )
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}

@Composable
private fun SettingsSectionHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 4.dp, bottom = 2.dp),
    )
}

@Composable
private fun LensSettingsSectionHeader(
    title: String,
    empty: Boolean,
    emptyMessage: String,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp, bottom = 2.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(text = title, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
        if (empty) Text(emptyMessage, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun LensPreferenceCard(
    lens: LensSettingsUiModel,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    onSetVisible: (Boolean) -> Unit,
    onEditName: () -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onSetOneXReference: () -> Unit,
) {
    Card(Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(text = lens.customLabel ?: lens.defaultLabel, fontWeight = FontWeight.SemiBold)
                    Text(lens.facing, style = MaterialTheme.typography.bodySmall)
                }
                Text("Visible")
                Spacer(Modifier.width(8.dp))
                Switch(
                    checked = lens.visible,
                    onCheckedChange = onSetVisible,
                    modifier = Modifier.semantics {
                        contentDescription = "Show ${lens.customLabel ?: lens.defaultLabel}"
                    },
                )
            }

            if (lens.supportsOneXReference) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(
                        selected = lens.isOneXReference,
                        onClick = onSetOneXReference,
                        modifier = Modifier.semantics {
                            contentDescription =
                                "Use ${lens.customLabel ?: lens.defaultLabel} as rear 1× reference"
                        },
                    )
                    Text("Use as rear 1× reference")
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onEditName) { Text("Rename") }
                TextButton(onClick = onMoveUp, enabled = canMoveUp) { Text("Move up") }
                TextButton(onClick = onMoveDown, enabled = canMoveDown) { Text("Move down") }
            }
        }
    }
}

@Composable
private fun ChoiceRow(
    selected: Boolean,
    label: String,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(role = Role.RadioButton, onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = onClick)
        Text(label)
    }
}

@Composable
private fun RenameLensDialog(
    lens: LensSettingsUiModel,
    onDismiss: () -> Unit,
    onSave: (String?) -> Unit,
) {
    var value by remember(lens.fingerprint) {
        mutableStateOf(lens.customLabel ?: lens.defaultLabel)
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Rename lens") },
        text = {
            OutlinedTextField(
                value = value,
                onValueChange = { value = it.take(48) },
                singleLine = true,
                label = { Text("Display name") },
            )
        },
        confirmButton = {
            TextButton(onClick = { onSave(value.trim().takeIf(String::isNotEmpty)) }) {
                Text("Save")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

private fun recommendedGlobalRange(ranges: List<FpsRange>): FpsRange? = ranges
    .groupingBy { it }
    .eachCount()
    .entries
    .maxWithOrNull(
        compareBy<Map.Entry<FpsRange, Int>> { it.value }
            .thenBy { it.key.min }
            .thenBy { it.key.max },
    )
    ?.key

private fun streamFormatLabel(format: StreamFormat): String = when (format) {
    StreamFormat.PRIVATE -> "Camera2 PRIVATE"
    StreamFormat.YUV_420_888 -> "Camera2 YUV_420_888"
    StreamFormat.JPEG -> "Camera2 JPEG"
    StreamFormat.HEIC -> "Camera2 HEIC"
    StreamFormat.RAW_SENSOR -> "Camera2 RAW_SENSOR"
    StreamFormat.RAW10 -> "Camera2 RAW10"
    StreamFormat.RAW12 -> "Camera2 RAW12"
    StreamFormat.RAW14 -> "Camera2 RAW14"
    StreamFormat.RAW_PRIVATE -> "Camera2 RAW_PRIVATE"
    StreamFormat.DEPTH16 -> "Camera2 DEPTH16"
    StreamFormat.DEPTH_POINT_CLOUD -> "Camera2 DEPTH_POINT_CLOUD"
    StreamFormat.DEPTH_JPEG -> "Camera2 DEPTH_JPEG"
    StreamFormat.UNKNOWN -> "Camera2 UNKNOWN"
}
