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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.sahidcode404.camex.core.camera.PreviewPreferenceRegistry
import com.sahidcode404.camex.core.camera.ViewfinderFormatCapability
import com.sahidcode404.camex.core.model.FpsRange
import com.sahidcode404.camex.core.model.Size2D
import com.sahidcode404.camex.core.model.StreamFormat
import kotlin.math.abs

data class LensSettingsUiModel(
    val fingerprint: String,
    val defaultLabel: String,
    val customLabel: String?,
    val facing: String,
    val visible: Boolean,
    val isOneXReference: Boolean,
    val supportsOneXReference: Boolean,
    val advanced: Boolean = false,
    val previewSizes: List<Size2D> = emptyList(),
    /** Reported ranges feed the one centralized viewfinder FPS override. */
    val previewFpsRanges: List<FpsRange> = emptyList(),
    val selectedPreviewSize: Size2D? = null,
    /** Kept for source compatibility with the existing ViewModel projection; no per-lens FPS UI. */
    val selectedPreviewFpsRange: FpsRange? = null,
)

private enum class ThresholdKind { LOWER, HIGH }

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
    var editing by remember { mutableStateOf<LensSettingsUiModel?>(null) }
    var viewfinderEditing by remember { mutableStateOf<LensSettingsUiModel?>(null) }
    var thresholdEditing by remember { mutableStateOf<ThresholdKind?>(null) }

    val globalFpsRange by PreviewPreferenceRegistry.globalFpsRange.collectAsState()
    val viewfinderCapabilities by PreviewPreferenceRegistry.viewfinderCapabilities.collectAsState()
    val reportedFpsRanges = remember(lenses) {
        lenses.asSequence()
            .flatMap { it.previewFpsRanges.asSequence() }
            .filter { it.isValid && it.max > 0 }
            .distinct()
            .sortedWith(compareBy<FpsRange> { it.min }.thenBy { it.max })
            .toList()
    }
    val lowerThresholdOptions = remember(reportedFpsRanges, globalFpsRange) {
        (reportedFpsRanges.map { it.min } + listOfNotNull(globalFpsRange?.min))
            .filter { it >= 0 }
            .distinct()
            .sorted()
    }
    val highThresholdOptions = remember(reportedFpsRanges, globalFpsRange) {
        (reportedFpsRanges.map { it.max } + listOfNotNull(globalFpsRange?.max))
            .filter { it > 0 }
            .distinct()
            .sorted()
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
            item {
                SettingsSectionHeader("VIEWFINDER")
            }
            item {
                ViewfinderFrameRateCard(
                    range = globalFpsRange,
                    availableRanges = reportedFpsRanges,
                    onOverrideChanged = { enabled ->
                        if (!enabled) {
                            persistGlobalRange(null)
                        } else {
                            recommendedGlobalRange(reportedFpsRanges)?.let(::persistGlobalRange)
                        }
                    },
                    onEditLower = { thresholdEditing = ThresholdKind.LOWER },
                    onEditHigh = { thresholdEditing = ThresholdKind.HIGH },
                )
            }
            item {
                Text(
                    text = "Per-lens viewfinder stream",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
            item {
                Text(
                    text = "Each lens keeps its own Camera2 PRIVATE stream size. Auto is the default. The detail page also shows every other stream format the camera reports, without pretending capture-only outputs are zero-copy viewfinder backends.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            itemsIndexed(
                items = lenses,
                key = { _, lens -> "viewfinder:${lens.fingerprint}" },
            ) { _, lens ->
                ViewfinderLensRow(
                    lens = lens,
                    capability = viewfinderCapabilities[lens.fingerprint],
                    onClick = { viewfinderEditing = lens },
                )
            }

            item {
                SettingsSectionHeader("LENS MANAGEMENT")
            }
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
                    onMoveUp = {
                        onMove(indexed.index, normalLenses[sectionIndex - 1].index)
                    },
                    onMoveDown = {
                        onMove(indexed.index, normalLenses[sectionIndex + 1].index)
                    },
                    onSetOneXReference = {
                        onSetOneXReference(indexed.value.fingerprint)
                    },
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
                    onMoveUp = {
                        onMove(indexed.index, advancedLenses[sectionIndex - 1].index)
                    },
                    onMoveDown = {
                        onMove(indexed.index, advancedLenses[sectionIndex + 1].index)
                    },
                    onSetOneXReference = {
                        onSetOneXReference(indexed.value.fingerprint)
                    },
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

    viewfinderEditing?.let { lens ->
        ViewfinderStreamDialog(
            lens = lens,
            reportedCapabilities = viewfinderCapabilities[lens.fingerprint].orEmpty(),
            onDismiss = { viewfinderEditing = null },
            onSelectPrivateSize = { size ->
                onSetPreviewSize(lens.fingerprint, size)
                viewfinderEditing = null
            },
        )
    }

    thresholdEditing?.let { kind ->
        val current = globalFpsRange ?: recommendedGlobalRange(reportedFpsRanges)
        ThresholdDialog(
            title = if (kind == ThresholdKind.LOWER) "Lower threshold" else "High threshold",
            options = if (kind == ThresholdKind.LOWER) lowerThresholdOptions else highThresholdOptions,
            selected = if (kind == ThresholdKind.LOWER) current?.min else current?.max,
            onDismiss = { thresholdEditing = null },
            onSelect = { value ->
                val basis = current ?: FpsRange(value, value)
                val next = if (kind == ThresholdKind.LOWER) {
                    FpsRange(value, basis.max.coerceAtLeast(value))
                } else {
                    FpsRange(basis.min.coerceAtMost(value), value)
                }
                persistGlobalRange(next)
                thresholdEditing = null
            },
        )
    }
}

@Composable
private fun ViewfinderFrameRateCard(
    range: FpsRange?,
    availableRanges: List<FpsRange>,
    onOverrideChanged: (Boolean) -> Unit,
    onEditLower: () -> Unit,
    onEditHigh: () -> Unit,
) {
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
                    Text("Viewfinder frame rate", fontWeight = FontWeight.SemiBold)
                    Text(
                        "One centralized override for every lens. Each active profile maps it only to a range that Camera2 actually reports.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Spacer(Modifier.width(12.dp))
                Switch(
                    checked = range != null,
                    onCheckedChange = onOverrideChanged,
                    enabled = availableRanges.isNotEmpty(),
                )
            }

            SettingValueRow(
                title = "Lower threshold",
                value = range?.min?.let { "$it fps" } ?: "Auto",
                enabled = range != null,
                onClick = onEditLower,
            )
            SettingValueRow(
                title = "High threshold",
                value = range?.max?.let { "$it fps" } ?: "Auto",
                enabled = range != null,
                onClick = onEditHigh,
            )
        }
    }
}

@Composable
private fun ViewfinderLensRow(
    lens: LensSettingsUiModel,
    capability: List<ViewfinderFormatCapability>?,
    onClick: () -> Unit,
) {
    val privateReported = capability.orEmpty().any {
        it.format == StreamFormat.PRIVATE && it.regularSizes.isNotEmpty()
    } || lens.previewSizes.isNotEmpty()
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(
                enabled = privateReported,
                role = Role.Button,
                onClick = onClick,
            ),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(lens.customLabel ?: lens.defaultLabel, fontWeight = FontWeight.SemiBold)
                Text(
                    buildString {
                        append("Camera2 PRIVATE")
                        append(" · ")
                        append(lens.selectedPreviewSize?.let(::sizeLabel) ?: "Auto")
                    },
                    style = MaterialTheme.typography.bodySmall,
                )
                val otherCount = capability.orEmpty().count { it.format != StreamFormat.PRIVATE }
                if (otherCount > 0) {
                    Text(
                        "$otherCount other reported Camera2 output format${if (otherCount == 1) "" else "s"}",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
            Text(if (privateReported) "Open" else "Unavailable")
        }
    }
}

@Composable
private fun ViewfinderStreamDialog(
    lens: LensSettingsUiModel,
    reportedCapabilities: List<ViewfinderFormatCapability>,
    onDismiss: () -> Unit,
    onSelectPrivateSize: (Size2D?) -> Unit,
) {
    val privateSizes = (lens.previewSizes + reportedCapabilities
        .firstOrNull { it.format == StreamFormat.PRIVATE }
        ?.regularSizes
        .orEmpty())
        .asSequence()
        .filter(Size2D::isValid)
        .distinct()
        .sortedWith(sizeComparator())
        .toList()
    val otherFormats = reportedCapabilities
        .filter { it.format != StreamFormat.PRIVATE }
        .sortedBy { it.format.ordinal }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Viewfinder · ${lens.customLabel ?: lens.defaultLabel}") },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 560.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    "Active live backend: Camera2 PRIVATE / SurfaceTexture. This is the zero-copy path used for the normal viewfinder and physical/AUX routing.",
                    style = MaterialTheme.typography.bodySmall,
                )
                Text("Camera2 PRIVATE", fontWeight = FontWeight.SemiBold)
                ChoiceRow(
                    selected = lens.selectedPreviewSize == null,
                    label = "Auto (recommended)",
                    onClick = { onSelectPrivateSize(null) },
                )
                privateSizes.forEach { size ->
                    ChoiceRow(
                        selected = lens.selectedPreviewSize == size,
                        label = sizeLabel(size),
                        onClick = { onSelectPrivateSize(size) },
                    )
                }

                if (otherFormats.isNotEmpty()) {
                    Text(
                        "Other outputs reported by this lens",
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                    Text(
                        "These are shown exactly from Camera2 metadata. They are not silently substituted for the PRIVATE TextureView path: JPEG/HEIC/RAW are capture outputs, and YUV requires its own renderer before it can safely become a selectable live backend.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    otherFormats.forEach { capability ->
                        ReportedOutputBlock(capability)
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}

@Composable
private fun ReportedOutputBlock(capability: ViewfinderFormatCapability) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(streamFormatLabel(capability.format), fontWeight = FontWeight.Medium)
        capability.regularSizes.forEach { size ->
            Text("• ${sizeLabel(size)}", style = MaterialTheme.typography.bodySmall)
        }
        capability.maximumResolutionSizes.forEach { size ->
            Text(
                "• ${sizeLabel(size)} · maximum-resolution path",
                style = MaterialTheme.typography.bodySmall,
            )
        }
        if (capability.regularSizes.isEmpty() && capability.maximumResolutionSizes.isEmpty()) {
            Text("Reported without a valid size", style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun ThresholdDialog(
    title: String,
    options: List<Int>,
    selected: Int?,
    onDismiss: () -> Unit,
    onSelect: (Int) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 460.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                options.forEach { value ->
                    ChoiceRow(
                        selected = selected == value,
                        label = "$value fps",
                        onClick = { onSelect(value) },
                    )
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun SettingValueRow(
    title: String,
    value: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            title,
            modifier = Modifier.weight(1f),
            color = if (enabled) {
                MaterialTheme.colorScheme.onSurface
            } else {
                MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f)
            },
        )
        Text(
            value,
            color = if (enabled) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f)
            },
        )
    }
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
        Text(
            text = title,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Bold,
        )
        if (empty) {
            Text(emptyMessage, style = MaterialTheme.typography.bodySmall)
        }
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
                    Text(
                        text = lens.customLabel ?: lens.defaultLabel,
                        fontWeight = FontWeight.SemiBold,
                    )
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

private fun sizeLabel(size: Size2D): String =
    "${size.width}×${size.height} · ${aspectRatioLabel(size)}"

private fun aspectRatioLabel(size: Size2D): String {
    val width = abs(size.width)
    val height = abs(size.height)
    if (width == 0 || height == 0) return "unknown"
    val divisor = greatestCommonDivisor(width, height)
    return "${width / divisor}:${height / divisor}"
}

private tailrec fun greatestCommonDivisor(a: Int, b: Int): Int =
    if (b == 0) a.coerceAtLeast(1) else greatestCommonDivisor(b, a % b)

private fun sizeComparator(): Comparator<Size2D> =
    compareByDescending<Size2D> { it.area ?: 0L }
        .thenByDescending { it.width }
        .thenByDescending { it.height }
