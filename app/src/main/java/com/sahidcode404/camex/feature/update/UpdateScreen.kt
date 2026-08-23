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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.sahidcode404.camex.core.update.UpdateState
import com.sahidcode404.camex.core.update.UpdateUiState
import java.text.DateFormat
import java.util.Date

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UpdateScreen(
    state: UpdateUiState,
    onBack: () -> Unit,
    onCheck: () -> Unit,
    onDownloadAndInstall: () -> Unit,
    onRetryInstall: () -> Unit,
    onOpenInstallPermissionSettings: () -> Unit,
    onResetFailure: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text("Development updates") },
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
                        verticalArrangement = Arrangement.spacedBy(7.dp),
                    ) {
                        Text("Current build", fontWeight = FontWeight.SemiBold)
                        Field("Version", state.installed.versionName)
                        Field("Version code", state.installed.versionCode.toString())
                        Field("Git SHA", state.installed.gitSha)
                        Field("Channel", state.installed.channel)
                        Field(
                            "Installed signer SHA-256",
                            state.installed.installedSigningCertificateSha256 ?: "Unavailable",
                        )
                        Field(
                            "Pinned OTA signer SHA-256",
                            state.installed.pinnedSigningCertificateSha256 ?: "Not pinned",
                        )
                        Field(
                            "Last checked",
                            state.preferences.lastCheckEpochMs?.let(::formatEpoch) ?: "Never",
                        )
                    }
                }
            }

            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Text("Development OTA", fontWeight = FontWeight.SemiBold)
                        if (!state.installed.otaEnabled) {
                            Text(
                                "This installed APK is not the stable devOta variant. Install the first " +
                                    "stable development OTA base APK before using in-app updates.",
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        UpdateStateContent(
                            state = state,
                            onCheck = onCheck,
                            onDownloadAndInstall = onDownloadAndInstall,
                            onRetryInstall = onRetryInstall,
                            onOpenInstallPermissionSettings = onOpenInstallPermissionSettings,
                            onResetFailure = onResetFailure,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun UpdateStateContent(
    state: UpdateUiState,
    onCheck: () -> Unit,
    onDownloadAndInstall: () -> Unit,
    onRetryInstall: () -> Unit,
    onOpenInstallPermissionSettings: () -> Unit,
    onResetFailure: () -> Unit,
) {
    when (val update = state.updateState) {
        UpdateState.Idle -> {
            Text("No update check is running.")
            Button(
                onClick = onCheck,
                enabled = state.installed.otaEnabled,
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Check for updates") }
        }
        UpdateState.Checking -> Text("Checking GitHub development prereleases…")
        is UpdateState.UpToDate -> {
            Text("This development build is up to date.")
            OutlinedButton(onClick = onCheck, modifier = Modifier.fillMaxWidth()) {
                Text("Check again")
            }
        }
        is UpdateState.UpdateAvailable -> {
            Text("New development build available", fontWeight = FontWeight.SemiBold)
            Field("Version", update.manifest.versionName)
            Field("Version code", update.manifest.versionCode.toString())
            if (update.manifest.releaseNotes.isNotBlank()) {
                Text(update.manifest.releaseNotes, style = MaterialTheme.typography.bodySmall)
            }
            Button(onClick = onDownloadAndInstall, modifier = Modifier.fillMaxWidth()) {
                Text("Download & install")
            }
        }
        is UpdateState.Downloading -> {
            val total = update.totalBytes?.takeIf { it > 0L }
            val progress = total?.let { ((update.downloadedBytes * 100L) / it).coerceIn(0L, 100L) }
            Text(
                progress?.let { "Downloading… $it%" }
                    ?: "Downloading… ${update.downloadedBytes} bytes",
            )
        }
        is UpdateState.Verifying -> Text("Verifying hash, package, version, and signer…")
        is UpdateState.ReadyToInstall -> {
            Text("Update verified and ready for Android installation.")
            Button(onClick = onRetryInstall, modifier = Modifier.fillMaxWidth()) { Text("Install") }
        }
        is UpdateState.AwaitingInstallPermission -> {
            Text(
                "Android must allow Camera to request package installs. This permission is only " +
                    "needed for the development OTA channel.",
                style = MaterialTheme.typography.bodySmall,
            )
            Button(
                onClick = onOpenInstallPermissionSettings,
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Open install-source settings") }
            OutlinedButton(onClick = onRetryInstall, modifier = Modifier.fillMaxWidth()) {
                Text("Try install again")
            }
        }
        is UpdateState.AwaitingUserAction -> Text("Waiting for Android update confirmation…")
        is UpdateState.Installing -> Text("Installing verified update…")
        is UpdateState.Installed -> Text("Update installed. Reopen Camera to use version ${update.versionCode}.")
        is UpdateState.Failed -> {
            Text("${update.code.name}: ${update.detail}", color = MaterialTheme.colorScheme.error)
            Button(onClick = onResetFailure, modifier = Modifier.fillMaxWidth()) { Text("Dismiss") }
            OutlinedButton(onClick = onCheck, modifier = Modifier.fillMaxWidth()) {
                Text("Check again")
            }
        }
    }
}

@Composable
private fun Field(name: String, value: String) {
    Column(Modifier.fillMaxWidth()) {
        Text(name, style = MaterialTheme.typography.labelSmall)
        Text(
            value,
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
        )
    }
}

private fun formatEpoch(epochMs: Long): String =
    DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(epochMs))
