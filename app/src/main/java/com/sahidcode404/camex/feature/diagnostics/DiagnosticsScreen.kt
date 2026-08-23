package com.sahidcode404.camex.feature.diagnostics

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

data class DiagnosticField(val name: String, val value: String)

data class CameraProfileDiagnosticsUiModel(
    val stableKey: String,
    val title: String,
    val fields: List<DiagnosticField>,
)

data class LensDiagnosticsUiModel(
    val stableKey: String,
    val fingerprint: String,
    val title: String,
    val subtitle: String,
    val status: String,
    val summary: List<DiagnosticField>,
    val advanced: List<DiagnosticField>,
    val profiles: List<CameraProfileDiagnosticsUiModel> = emptyList(),
)

data class DiagnosticsUiState(
    val buildSummary: List<DiagnosticField> = emptyList(),
    val nativeSummary: List<DiagnosticField> = emptyList(),
    val startupTraceSummary: List<DiagnosticField> = emptyList(),
    val cacheSummary: List<DiagnosticField> = emptyList(),
    val discoverySummary: List<DiagnosticField> = emptyList(),
    val lenses: List<LensDiagnosticsUiModel> = emptyList(),
    val exportEnabled: Boolean = false,
    val statusText: String = "Waiting for camera discovery",
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DiagnosticsScreen(
    state: DiagnosticsUiState,
    otaSummary: List<DiagnosticField>,
    modifier: Modifier = Modifier,
    rawSummary: List<DiagnosticField> = emptyList(),
    onBack: () -> Unit,
    onNormalRescan: () -> Unit,
    onDeepRescan: () -> Unit,
    onResetDiscoveryCache: () -> Unit,
    onOpenUpdates: () -> Unit,
    onExport: () -> Unit,
) {
    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text("Camera diagnostics") },
                navigationIcon = { TextButton(onClick = onBack) { Text("Back") } },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.padding(padding),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Text("Compatibility report", fontWeight = FontWeight.SemiBold)
                        Text(
                            "The JSON report contains camera, Android, graphics, probe and RAW " +
                                "capture diagnostics; it contains no photos, accounts, location, " +
                                "contacts, or tokens.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Button(
                            onClick = onExport,
                            enabled = state.exportEnabled,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text("Export JSON")
                        }
                        Button(
                            onClick = onOpenUpdates,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text("Updates")
                        }
                        Button(
                            onClick = onNormalRescan,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text("Normal rescan")
                        }
                        OutlinedButton(
                            onClick = onDeepRescan,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text("Deep rescan")
                        }
                        TextButton(
                            onClick = onResetDiscoveryCache,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text("Reset discovery cache")
                        }
                        Text(state.statusText, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }

            if (state.buildSummary.isNotEmpty()) {
                item { DiagnosticSection("App and device", state.buildSummary) }
            }
            if (otaSummary.isNotEmpty()) {
                item { DiagnosticSection("Updates", otaSummary) }
            }
            if (rawSummary.isNotEmpty()) {
                item { DiagnosticSection("Single RAW capture", rawSummary) }
            }
            if (state.nativeSummary.isNotEmpty()) {
                item { DiagnosticSection("Native foundation", state.nativeSummary) }
            }
            if (state.startupTraceSummary.isNotEmpty()) {
                item { DiagnosticSection("Startup trace", state.startupTraceSummary) }
            }
            if (state.cacheSummary.isNotEmpty()) {
                item { DiagnosticSection("Discovery cache", state.cacheSummary) }
            }
            if (state.discoverySummary.isNotEmpty()) {
                item { DiagnosticSection("Discovery and failure memory", state.discoverySummary) }
            }
            items(state.lenses, key = { it.stableKey }) { lens ->
                LensDiagnosticCard(lens)
            }
        }
    }
}

@Composable
private fun DiagnosticSection(title: String, fields: List<DiagnosticField>) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text(title, fontWeight = FontWeight.SemiBold)
            fields.forEach { field -> DiagnosticRow(field) }
        }
    }
}

@Composable
private fun LensDiagnosticCard(lens: LensDiagnosticsUiModel) {
    var expanded by rememberSaveable(lens.fingerprint) { mutableStateOf(false) }
    Card(Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .clickable { expanded = !expanded }
                .padding(16.dp),
        ) {
            Text(lens.title, fontWeight = FontWeight.SemiBold)
            Text("Canonical optical lens", style = MaterialTheme.typography.labelSmall)
            Text(lens.subtitle, style = MaterialTheme.typography.bodySmall)
            Text(lens.status, color = MaterialTheme.colorScheme.primary)
            lens.summary.forEach { field -> DiagnosticRow(field) }
            if (expanded) {
                HorizontalDivider(Modifier.padding(vertical = 10.dp))
                lens.advanced.forEach { field -> DiagnosticRow(field) }
                HorizontalDivider(Modifier.padding(vertical = 10.dp))
                Text(
                    "Camera Profiles (${lens.profiles.size})",
                    fontWeight = FontWeight.SemiBold,
                )
                if (lens.profiles.isEmpty()) {
                    Text("No transport profiles", style = MaterialTheme.typography.bodySmall)
                } else {
                    lens.profiles.forEach { profile -> ProfileDiagnostics(profile) }
                }
            }
            Text(
                if (expanded) "Hide profiles and advanced metadata" else "Show camera profiles",
                modifier = Modifier.padding(top = 10.dp),
                color = MaterialTheme.colorScheme.primary,
                style = MaterialTheme.typography.labelLarge,
            )
        }
    }
}

@Composable
private fun ProfileDiagnostics(profile: CameraProfileDiagnosticsUiModel) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 12.dp),
    ) {
        Text(profile.title, fontWeight = FontWeight.Medium)
        profile.fields.forEach { field -> DiagnosticRow(field) }
    }
}

@Composable
private fun DiagnosticRow(field: DiagnosticField) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = field.name,
            modifier = Modifier.weight(0.42f),
            style = MaterialTheme.typography.bodySmall,
        )
        Text(
            text = field.value,
            modifier = Modifier.weight(0.58f),
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
        )
    }
}
