package com.sahidcode404.camex.feature.lenssettings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.sahidcode404.camex.core.camera.PreviewPreferenceRegistry
import com.sahidcode404.camex.core.model.FpsRange
import com.sahidcode404.camex.core.model.Size2D

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
    /** Reported ranges are retained here only to build the one centralized FPS selector. */
    val previewFpsRanges: List<FpsRange> = emptyList(),
    val selectedPreviewSize: Size2D? = null,
    /** Legacy UI projection; FPS selection is now centralized and this is intentionally not shown. */
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
    var editing by remember { mutableStateOf<LensSettingsUiModel?>(null) }
    var previewSizeEditing by remember { mutableStateOf<LensSettingsUiModel?>(null) }
    var previewFpsEditing by remember { mutableStateOf(false) }
    val globalFpsTarget by PreviewPreferenceRegistry.globalFpsTarget.collectAsState()
    val globalFpsOptions = remember(lenses) {
        lenses.asSequence()
            .flatMap { it.previewFpsRanges.asSequence() }
            .filter { it.isValid && it.max > 0 }
            .map { it.max }
            .distinct()
            .sortedDescending()
            .toList()
    }
    val indexedLenses = lenses.withIndex().toList()
    val normalLenses = indexedLenses.filterNot { it.value.advanced }
    val advancedLenses = indexedLenses.filter { it.value.advanced }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text("Lens settings") },
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
                Text(
                    text = "Preview stream selection is per optical lens. Frame rate is one global target. Every option comes from Camera2 metadata; unsupported or stale choices fall back to Auto instead of breaking a lens.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            item {
                GlobalPreviewFpsCard(
                    options = globalFpsOptions,
                    selectedFps = globalFpsTarget,
                    onEdit = { previewFpsEditing = true },
                )
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
                key = { _, indexed -> indexed.value.fingerprint },
            ) { sectionIndex, indexed ->
                LensPreferenceCard(
                    lens = indexed.value,
                    canMoveUp = sectionIndex > 0,
                    canMoveDown = sectionIndex < normalLenses.lastIndex,
                    onSetVisible = { onSetVisible(indexed.value.fingerprint, it) },
                    onEditName = { editing = indexed.value },
                    onEditPreviewSize = { previewSizeEditing = indexed.value },
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
                key = { _, indexed -> indexed.value.fingerprint },
            ) { sectionIndex, indexed ->
                LensPreferenceCard(
                    lens = indexed.value,
                    canMoveUp = sectionIndex > 0,
                    canMoveDown = sectionIndex < advancedLenses.lastIndex,
                    onSetVisible = { onSetVisible(indexed.value.fingerprint, it) },
                    onEditName = { editing = indexed.value },
                    onEditPreviewSize = { previewSizeEditing = indexed.value },
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
    previewSizeEditing?.let { lens ->
        PreviewSizeDialog(
            lens = lens,
            onDismiss = { previewSizeEditing = null },
            onSelect = { size ->
                onSetPreviewSize(lens.fingerprint, size)
                previewSizeEditing = null
            },
        )
    }
    if (previewFpsEditing) {
        GlobalPreviewFpsDialog(
            options = globalFpsOptions,
            selectedFps = globalFpsTarget,
            onDismiss = { previewFpsEditing = false },
            onSelect = { fps ->
                onSetPreviewFps(
                    PreviewPreferenceRegistry.GLOBAL_PREVIEW_FPS_FINGERPRINT,
                    fps?.let { FpsRange(it, it) },
                )
                previewFpsEditing = false
            },
        )
    }
}

@Composable
private fun GlobalPreviewFpsCard(
    options: List<Int>,
    selectedFps: Int?,
    onEdit: () -> Unit,
) {
    Card(Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            Text("Preview frame rate", fontWeight = FontWeight.SemiBold)
            val available = selectedFps == null || selectedFps in options
            Text(
                when {
                    selectedFps == null -> "Auto (recommended)"
                    available -> "$selectedFps fps target"
                    else -> "Auto · saved $selectedFps fps target is unavailable"
                },
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                "One setting for every lens. Each camera maps the target only to an FPS range it actually reports.",
                style = MaterialTheme.typography.bodySmall,
            )
            TextButton(onClick = onEdit, enabled = options.isNotEmpty()) {
                Text("Choose frame rate")
            }
        }
    }
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
    onEditPreviewSize: () -> Unit,
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

            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("Preview stream", style = MaterialTheme.typography.labelMedium)
                Text(
                    lens.selectedPreviewSize?.let(::sizeLabel) ?: "Auto (recommended)",
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(
                    "${lens.previewSizes.size} supported Camera2 PRIVATE stream${if (lens.previewSizes.size == 1) "" else "s"}",
                    style = MaterialTheme.typography.bodySmall,
                )
                TextButton(
                    onClick = onEditPreviewSize,
                    enabled = lens.previewSizes.isNotEmpty(),
                ) { Text("Choose preview stream") }
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
private fun PreviewSizeDialog(
    lens: LensSettingsUiModel,
    onDismiss: () -> Unit,
    onSelect: (Size2D?) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Preview stream · ${lens.customLabel ?: lens.defaultLabel}") },
        text = {
            Column(
                Modifier
                    .heightIn(max = 480.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                Text(
                    "Only stream sizes reported for this optical lens are shown.",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
                ChoiceRow(
                    selected = lens.selectedPreviewSize == null,
                    label = "Auto (recommended)",
                    onClick = { onSelect(null) },
                )
                lens.previewSizes.forEach { size ->
                    ChoiceRow(
                        selected = lens.selectedPreviewSize == size,
                        label = sizeLabel(size),
                        onClick = { onSelect(size) },
                    )
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun GlobalPreviewFpsDialog(
    options: List<Int>,
    selectedFps: Int?,
    onDismiss: () -> Unit,
    onSelect: (Int?) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Preview frame rate") },
        text = {
            Column(
                Modifier
                    .heightIn(max = 480.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                Text(
                    "This is one global target. A lens that cannot report or sustain it automatically uses its own Auto range.",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
                ChoiceRow(
                    selected = selectedFps == null || selectedFps !in options,
                    label = "Auto (recommended)",
                    onClick = { onSelect(null) },
                )
                options.forEach { fps ->
                    ChoiceRow(
                        selected = selectedFps == fps,
                        label = "$fps fps",
                        onClick = { onSelect(fps) },
                    )
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun ChoiceRow(
    selected: Boolean,
    label: String,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = onClick)
        TextButton(onClick = onClick) { Text(label) }
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

private fun sizeLabel(size: Size2D): String {
    val divisor = greatestCommonDivisor(size.width, size.height).coerceAtLeast(1)
    return "${size.width}×${size.height} · ${size.width / divisor}:${size.height / divisor}"
}

private tailrec fun greatestCommonDivisor(a: Int, b: Int): Int =
    if (b == 0) kotlin.math.abs(a) else greatestCommonDivisor(b, a % b)
