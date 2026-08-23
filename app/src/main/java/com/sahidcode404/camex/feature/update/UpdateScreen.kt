package com.sahidcode404.camex.feature.update

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.sahidcode404.camex.core.update.UpdateState
import com.sahidcode404.camex.core.update.UpdateUiState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UpdateScreen(
    state: UpdateUiState,
    onBack: () -> Unit,
    onCheck: () -> Unit,
    onDownload: () -> Unit,
    onInstall: () -> Unit,
    onOpenInstallPermissionSettings: () -> Unit,
    onResetFailure: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text("Updates") },
                navigationIcon = { TextButton(onClick = onBack) { Text("Back") } },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text("Current version", fontWeight = FontWeight.SemiBold)
                        Text("${state.installed.versionName} (${state.installed.versionCode})")
                    }
                }
            }
            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        when (val update = state.updateState) {
                            UpdateState.Idle -> Button(
                                onClick = onCheck,
                                modifier = Modifier.fillMaxWidth(),
                            ) { Text("Check for updates") }

                            UpdateState.Checking -> Text("Checking GitHub…")
                            UpdateState.UpToDate -> {
                                Text("Camera is up to date")
                                OutlinedButton(onClick = onCheck, modifier = Modifier.fillMaxWidth()) {
                                    Text("Check again")
                                }
                            }
                            is UpdateState.Available -> {
                                Text(
                                    "Camera ${update.update.manifest.versionName} available",
                                    fontWeight = FontWeight.SemiBold,
                                )
                                if (update.update.manifest.changelog.isNotBlank()) {
                                    Text(
                                        update.update.manifest.changelog,
                                        style = MaterialTheme.typography.bodySmall,
                                    )
                                }
                                Button(onClick = onDownload, modifier = Modifier.fillMaxWidth()) {
                                    Text("Download update")
                                }
                            }
                            is UpdateState.Downloading -> {
                                val total = update.totalBytes?.takeIf { it > 0L }
                                val percent = total?.let {
                                    ((update.downloadedBytes * 100L) / it).coerceIn(0L, 100L)
                                }
                                Text(percent?.let { "Downloading… $it%" } ?: "Downloading update…")
                            }
                            is UpdateState.Verifying -> Text("Verifying update…")
                            is UpdateState.ReadyToInstall -> {
                                Text("Update verified and ready to install")
                                if (state.installPermissionGranted) {
                                    Button(onClick = onInstall, modifier = Modifier.fillMaxWidth()) {
                                        Text("Install update")
                                    }
                                } else {
                                    Text(
                                        "Allow Camera to install updates, then return here.",
                                        style = MaterialTheme.typography.bodySmall,
                                    )
                                    Button(
                                        onClick = onOpenInstallPermissionSettings,
                                        modifier = Modifier.fillMaxWidth(),
                                    ) {
                                        Text("Allow installs from Camera")
                                    }
                                }
                            }
                            is UpdateState.Failed -> {
                                Text(update.message, color = MaterialTheme.colorScheme.error)
                                Button(onClick = onResetFailure, modifier = Modifier.fillMaxWidth()) {
                                    Text("Dismiss")
                                }
                                OutlinedButton(onClick = onCheck, modifier = Modifier.fillMaxWidth()) {
                                    Text("Check again")
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
