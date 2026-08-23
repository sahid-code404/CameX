package com.sahidcode404.camex.feature.camera

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

data class LensButtonUiModel(
    val fingerprint: String,
    val label: String,
    val facing: String,
    val enabled: Boolean = true,
)

data class CameraScreenUiState(
    val permissionGranted: Boolean,
    val statusText: String,
    val lenses: List<LensButtonUiModel> = emptyList(),
    val selectedFingerprint: String? = null,
    val activeFacing: String = "UNKNOWN",
    val switchFacingLabel: String = "Switch",
    val switchFacingEnabled: Boolean = false,
    val previewVisible: Boolean = false,
    val recoverableError: String? = null,
)

@Composable
fun CameraScreen(
    state: CameraScreenUiState,
    previewContent: @Composable () -> Unit,
    permissionPermanentlyDenied: Boolean,
    updateAvailable: Boolean = false,
    onRequestPermission: () -> Unit,
    onOpenAppSettings: () -> Unit,
    onSelectLens: (String) -> Unit,
    onSwitchFacing: () -> Unit,
    onOpenLensSettings: () -> Unit,
    onOpenDiagnostics: () -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black),
    ) {
        if (state.permissionGranted) {
            previewContent()
        }

        CameraTopBar(
            updateAvailable = updateAvailable,
            onOpenLensSettings = onOpenLensSettings,
            onOpenDiagnostics = onOpenDiagnostics,
            modifier = Modifier.align(Alignment.TopCenter),
        )

        when {
            !state.permissionGranted -> PermissionPrompt(
                permanentlyDenied = permissionPermanentlyDenied,
                onRequestPermission = onRequestPermission,
                onOpenAppSettings = onOpenAppSettings,
                modifier = Modifier.align(Alignment.Center),
            )
            state.recoverableError != null -> ErrorPrompt(
                message = state.recoverableError,
                onRetry = onRetry,
                modifier = Modifier.align(Alignment.Center),
            )
            !state.previewVisible -> Text(
                text = state.statusText,
                color = Color.White,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .align(Alignment.Center)
                    .padding(32.dp),
            )
        }

        CameraBottomControls(
            lenses = state.lenses,
            selectedFingerprint = state.selectedFingerprint,
            switchFacingLabel = state.switchFacingLabel,
            switchFacingEnabled = state.switchFacingEnabled,
            onSelectLens = onSelectLens,
            onSwitchFacing = onSwitchFacing,
            modifier = Modifier.align(Alignment.BottomCenter),
        )
    }
}

@Composable
private fun CameraTopBar(
    updateAvailable: Boolean,
    onOpenLensSettings: () -> Unit,
    onOpenDiagnostics: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(Color.Black.copy(alpha = 0.38f))
            .statusBarsPadding()
            .padding(horizontal = 12.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TextButton(onClick = onOpenLensSettings) {
            Text("Lenses", color = Color.White)
        }
        Text(
            text = "Camera",
            color = Color.White,
            fontWeight = FontWeight.SemiBold,
        )
        TextButton(onClick = onOpenDiagnostics) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Diagnostics", color = Color.White)
                if (updateAvailable) {
                    Spacer(Modifier.size(6.dp))
                    Box(
                        Modifier
                            .size(8.dp)
                            .background(Color(0xFFA8C7FA), CircleShape),
                    )
                }
            }
        }
    }
}

@Composable
private fun PermissionPrompt(
    permanentlyDenied: Boolean,
    onRequestPermission: () -> Unit,
    onOpenAppSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.padding(28.dp),
        color = Color(0xE6212226),
        shape = RoundedCornerShape(24.dp),
    ) {
        Column(
            modifier = Modifier.padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = if (permanentlyDenied) {
                    "Camera permission is disabled. Open app settings to enable discovery and preview."
                } else {
                    "Camera permission is required for discovery and preview. CameX never captures " +
                        "or saves a photo during Phase 1."
                },
                color = Color.White,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(18.dp))
            Button(onClick = if (permanentlyDenied) onOpenAppSettings else onRequestPermission) {
                Text(if (permanentlyDenied) "Open app settings" else "Allow camera")
            }
        }
    }
}

@Composable
private fun ErrorPrompt(
    message: String,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.padding(28.dp),
        color = Color(0xE6212226),
        shape = RoundedCornerShape(24.dp),
    ) {
        Column(
            modifier = Modifier.padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(message, color = Color.White, textAlign = TextAlign.Center)
            Spacer(Modifier.height(16.dp))
            Button(onClick = onRetry) { Text("Retry safely") }
        }
    }
}

@Composable
private fun CameraBottomControls(
    lenses: List<LensButtonUiModel>,
    selectedFingerprint: String?,
    switchFacingLabel: String,
    switchFacingEnabled: Boolean,
    onSelectLens: (String) -> Unit,
    onSwitchFacing: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(Color.Black.copy(alpha = 0.58f))
            .navigationBarsPadding()
            .padding(vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.Center,
        ) {
            lenses.forEach { lens ->
                val selected = lens.fingerprint == selectedFingerprint
                Text(
                    text = lens.label,
                    color = if (selected) Color.Black else Color.White,
                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                    modifier = Modifier
                        .padding(horizontal = 4.dp)
                        .background(
                            color = if (selected) Color.White else Color.Black.copy(alpha = 0.55f),
                            shape = CircleShape,
                        )
                        .border(1.dp, Color.White.copy(alpha = 0.5f), CircleShape)
                        .clickable(
                            enabled = lens.enabled,
                            role = Role.Button,
                            onClick = { onSelectLens(lens.fingerprint) },
                        )
                        .padding(horizontal = 14.dp, vertical = 9.dp),
                )
            }
        }

        Spacer(Modifier.height(14.dp))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 34.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .border(1.dp, Color.White.copy(alpha = 0.4f), RoundedCornerShape(12.dp))
                    .semantics { contentDescription = "Gallery is planned for a later phase" },
                contentAlignment = Alignment.Center,
            ) {
                Text("—", color = Color.White.copy(alpha = 0.5f))
            }
            Box(
                modifier = Modifier
                    .size(76.dp)
                    .border(5.dp, Color.White, CircleShape)
                    .semantics { contentDescription = "Capture unavailable in Phase 1" },
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    Modifier
                        .size(58.dp)
                        .background(Color.White.copy(alpha = 0.35f), CircleShape),
                )
            }
            TextButton(
                enabled = switchFacingEnabled,
                onClick = onSwitchFacing,
            ) {
                Text(
                    text = switchFacingLabel,
                    color = if (switchFacingEnabled) Color.White else Color.White.copy(alpha = 0.45f),
                )
            }
        }
        Text(
            text = "Capture arrives in Phase 2",
            color = Color.White.copy(alpha = 0.55f),
            style = MaterialTheme.typography.labelSmall,
        )
    }
}
