package com.sahidcode404.camex.feature.lenssettings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

data class LensSettingsUiModel(
    val fingerprint: String,
    val defaultLabel: String,
    val customLabel: String?,
    val facing: String,
    val visible: Boolean,
    val isOneXReference: Boolean,
    val supportsOneXReference: Boolean,
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
    modifier: Modifier = Modifier,
) {
    var editing by remember { mutableStateOf<LensSettingsUiModel?>(null) }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text("Lens settings") },
                navigationIcon = { TextButton(onClick = onBack) { Text("Back") } },
            )
        },
    ) { padding ->
        if (lenses.isEmpty()) {
            Column(
                modifier = Modifier
                    .padding(padding)
                    .padding(24.dp),
            ) {
                Text("No usable photographic lenses have been discovered yet.")
            }
        } else {
            LazyColumn(
                modifier = Modifier.padding(padding),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
            ) {
                itemsIndexed(lenses, key = { _, lens -> lens.fingerprint }) { index, lens ->
                    LensPreferenceCard(
                        lens = lens,
                        canMoveUp = index > 0,
                        canMoveDown = index < lenses.lastIndex,
                        onSetVisible = { onSetVisible(lens.fingerprint, it) },
                        onEditName = { editing = lens },
                        onMoveUp = { onMove(index, index - 1) },
                        onMoveDown = { onMove(index, index + 1) },
                        onSetOneXReference = { onSetOneXReference(lens.fingerprint) },
                    )
                }
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
                Switch(checked = lens.visible, onCheckedChange = onSetVisible)
            }

            if (lens.supportsOneXReference) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(
                        selected = lens.isOneXReference,
                        onClick = onSetOneXReference,
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
