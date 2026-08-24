package com.sahidcode404.camex.feature.update

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.sahidcode404.camex.core.update.UpdateChannel
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
    val isDevelopment = state.channel == UpdateChannel.DEVELOPMENT
    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Updates", fontWeight = FontWeight.SemiBold)
                        Text(
                            if (isDevelopment) "Development channel" else "Stable channel",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                navigationIcon = {
                    TextButton(onClick = onBack) { Text("Back") }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(horizontal = 18.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            item { CurrentVersionCard(state) }
            item {
                UpdateStatusCard(
                    state = state,
                    onCheck = onCheck,
                    onDownload = onDownload,
                    onInstall = onInstall,
                    onOpenInstallPermissionSettings = onOpenInstallPermissionSettings,
                    onResetFailure = onResetFailure,
                )
            }
            item {
                Text(
                    text = if (isDevelopment) {
                        "Development builds check the rolling dev-latest CameX release. " +
                            "Downloads stay in Camera's private storage until Android's installer opens."
                    } else {
                        "Stable builds check the latest CameX GitHub release. " +
                            "Downloads stay in Camera's private storage until Android's installer opens."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp),
                )
            }
        }
    }
}

@Composable
private fun CurrentVersionCard(state: UpdateUiState) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        ),
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                "Current version",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                state.installed.versionName,
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
            )
            Text(
                "Build ${state.installed.versionCode}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                "${state.channel.buildConfigValue.replaceFirstChar(Char::uppercase)} OTA",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun UpdateStatusCard(
    state: UpdateUiState,
    onCheck: () -> Unit,
    onDownload: () -> Unit,
    onInstall: () -> Unit,
    onOpenInstallPermissionSettings: () -> Unit,
    onResetFailure: () -> Unit,
) {
    val isDevelopment = state.channel == UpdateChannel.DEVELOPMENT
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        tonalElevation = 2.dp,
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            when (val update = state.updateState) {
                UpdateState.Idle -> {
                    StatusHeader(
                        title = "Ready to check",
                        subtitle = if (isDevelopment) {
                            "Look for the newest development build on GitHub."
                        } else {
                            "Look for a newer stable Camera build on GitHub."
                        },
                    )
                    Button(onClick = onCheck, modifier = Modifier.fillMaxWidth()) {
                        Text("Check now")
                    }
                }

                UpdateState.Checking -> {
                    StatusHeader(
                        title = "Checking for updates",
                        subtitle = if (isDevelopment) {
                            "Contacting the CameX development channel…"
                        } else {
                            "Contacting the CameX stable channel…"
                        },
                    )
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }

                UpdateState.UpToDate -> {
                    StatusHeader(
                        title = "You're up to date",
                        subtitle = if (isDevelopment) {
                            "This is the newest published development build."
                        } else {
                            "This is the newest stable Camera version currently available."
                        },
                    )
                    FilledTonalButton(onClick = onCheck, modifier = Modifier.fillMaxWidth()) {
                        Text("Check again")
                    }
                }

                is UpdateState.Available -> {
                    val manifest = update.update.manifest
                    StatusHeader(
                        title = if (isDevelopment) {
                            "Development update available"
                        } else {
                            "Camera ${manifest.versionName} is available"
                        },
                        subtitle = buildString {
                            append("Build ${manifest.versionCode}")
                            manifest.gitSha.takeIf { isDevelopment && it.isNotBlank() }?.let { sha ->
                                append(" · ${sha.take(12)}")
                            }
                        },
                    )
                    if (manifest.changelog.isNotBlank()) {
                        HorizontalDivider()
                        Text(
                            manifest.changelog,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Button(onClick = onDownload, modifier = Modifier.fillMaxWidth()) {
                        Text("Download update")
                    }
                    Text(
                        "The APK is verified before installation.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                is UpdateState.Downloading -> {
                    val total = update.totalBytes?.takeIf { it > 0L }
                    val fraction = total?.let {
                        (update.downloadedBytes.toFloat() / it.toFloat()).coerceIn(0f, 1f)
                    }
                    val percent = fraction?.let { (it * 100).toInt() }
                    StatusHeader(
                        title = "Downloading update",
                        subtitle = percent?.let { "$it% complete" } ?: "Downloading securely…",
                    )
                    if (fraction != null) {
                        LinearProgressIndicator(
                            progress = { fraction },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    } else {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    }
                }

                is UpdateState.Verifying -> {
                    StatusHeader(
                        title = "Verifying update",
                        subtitle = "Checking hash, package, version and signing certificate.",
                    )
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }

                is UpdateState.ReadyToInstall -> {
                    StatusHeader(
                        title = "Ready to install",
                        subtitle = "The update passed verification.",
                    )
                    if (state.installPermissionGranted) {
                        Button(onClick = onInstall, modifier = Modifier.fillMaxWidth()) {
                            Text("Install update")
                        }
                    } else {
                        Text(
                            "Android needs permission to install apps from Camera before continuing.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
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
                    StatusHeader(
                        title = "Update couldn't continue",
                        subtitle = update.message,
                        error = true,
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        OutlinedButton(
                            onClick = onResetFailure,
                            modifier = Modifier.weight(1f),
                        ) {
                            Text("Dismiss")
                        }
                        Button(
                            onClick = onCheck,
                            modifier = Modifier.weight(1f),
                        ) {
                            Text("Try again")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun StatusHeader(
    title: String,
    subtitle: String,
    error: Boolean = false,
) {
    Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Text(
            title,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold,
            color = if (error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
        )
        Text(
            subtitle,
            style = MaterialTheme.typography.bodyMedium,
            color = if (error) {
                MaterialTheme.colorScheme.error
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
        )
    }
}
