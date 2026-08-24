package com.sahidcode404.camex

import android.Manifest
import android.content.pm.PackageManager
import android.view.TextureView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import com.sahidcode404.camex.core.camera.raw.RawCaptureRegistry
import com.sahidcode404.camex.core.camera.raw.RawCaptureState
import com.sahidcode404.camex.core.camera.raw.RawCompatibilityReportJson
import com.sahidcode404.camex.core.model.Size2D
import com.sahidcode404.camex.core.update.UpdateState
import com.sahidcode404.camex.feature.camera.CameraScreen
import com.sahidcode404.camex.feature.diagnostics.DiagnosticField
import com.sahidcode404.camex.feature.diagnostics.DiagnosticsScreen
import com.sahidcode404.camex.feature.lenssettings.LensSettingsScreen
import com.sahidcode404.camex.feature.update.UpdateScreen
import com.sahidcode404.camex.feature.update.UpdateViewModel
import com.sahidcode404.camex.ui.theme.CameraTheme
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val viewModel: CameraViewModel by viewModels()
    private val updateViewModel: UpdateViewModel by viewModels()
    private var cameraPermissionRequestedThisActivity = false
    private val cameraPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        viewModel.onCameraPermission(granted)
    }

    override fun onCreate(savedInstanceState: android.os.Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            CameraTheme(darkTheme = true) {
                CameraApplication(viewModel, updateViewModel, this@MainActivity)
            }
        }
    }

    override fun onStart() {
        super.onStart()
        requestCameraPermissionImmediatelyIfNeeded()
    }

    override fun onResume() {
        super.onResume()
        // Standard Camera2 ownership: acquire only while interactive. The TextureView itself remains
        // stable, so returning from another camera app reuses one surface while Camera2 rebuilds its
        // producer/session geometry and transform from current cached capabilities.
        viewModel.onCameraPermission(hasCameraPermission())
    }

    override fun onPause() {
        // Release CameraDevice/session promptly so another camera app never has to race this process.
        // Do not recreate the TextureView here; only the Camera2 producer/session is cycled.
        viewModel.onBackground()
        super.onPause()
    }

    private fun requestCameraPermissionImmediatelyIfNeeded() {
        if (hasCameraPermission() || cameraPermissionRequestedThisActivity) return
        cameraPermissionRequestedThisActivity = true
        cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
    }

    private fun hasCameraPermission(): Boolean = ContextCompat.checkSelfPermission(
        this,
        Manifest.permission.CAMERA,
    ) == PackageManager.PERMISSION_GRANTED
}

private enum class AppScreen { CAMERA, LENS_SETTINGS, DIAGNOSTICS, UPDATES }

@Composable
private fun CameraApplication(
    viewModel: CameraViewModel,
    updateViewModel: UpdateViewModel,
    activity: ComponentActivity,
) {
    val context = LocalContext.current
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val updateState by updateViewModel.uiState.collectAsStateWithLifecycle()
    val rawState by RawCaptureRegistry.rawCaptureState.collectAsStateWithLifecycle()
    var screen by rememberSaveable { mutableStateOf(AppScreen.CAMERA) }
    var pendingReport by remember { mutableStateOf<String?>(null) }

    // Startup is camera-only: do not perform network update checks or show update dialogs while the
    // user is waiting for the first preview frame. Updates remain explicit from Diagnostics.
    val availableUpdate = (updateState.updateState as? UpdateState.Available)?.update

    BackHandler(enabled = screen != AppScreen.CAMERA) {
        screen = if (screen == AppScreen.UPDATES) AppScreen.DIAGNOSTICS else AppScreen.CAMERA
    }

    val reportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json"),
    ) { uri ->
        val report = pendingReport
        pendingReport = null
        if (uri != null && report != null) {
            activity.lifecycleScope.launch {
                val result = runCatching { viewModel.writeCompatibilityReport(uri, report) }
                Toast.makeText(
                    context,
                    if (result.isSuccess) "Compatibility report exported" else "Report export failed",
                    Toast.LENGTH_LONG,
                ).show()
            }
        }
    }

    when (screen) {
        AppScreen.CAMERA -> CameraScreen(
            state = state.camera,
            rawState = rawState,
            previewContent = { CameraPreview(viewModel) },
            updateAvailable = availableUpdate != null,
            onSelectLens = viewModel::selectLens,
            onSwitchFacing = viewModel::switchFacing,
            onCapture = {
                activity.lifecycleScope.launch { RawCaptureRegistry.captureCurrent() }
            },
            onOpenLensSettings = { screen = AppScreen.LENS_SETTINGS },
            onOpenDiagnostics = { screen = AppScreen.DIAGNOSTICS },
        )
        AppScreen.LENS_SETTINGS -> LensSettingsScreen(
            lenses = state.lensSettings,
            onBack = { screen = AppScreen.CAMERA },
            onSetVisible = viewModel::setLensVisible,
            onRename = viewModel::renameLens,
            onMove = viewModel::moveLens,
            onSetOneXReference = viewModel::setOneXReference,
            onSetPreviewSize = viewModel::setPreviewSize,
            onSetPreviewFps = viewModel::setPreviewFps,
        )
        AppScreen.DIAGNOSTICS -> DiagnosticsScreen(
            state = state.diagnostics,
            otaSummary = listOf(
                DiagnosticField("Version", updateState.installed.versionName),
                DiagnosticField("Version code", updateState.installed.versionCode.toString()),
                DiagnosticField("Git SHA", updateState.installed.gitSha),
                DiagnosticField("OTA channel", updateState.channel.buildConfigValue),
                DiagnosticField(
                    "Signing certificate SHA-256",
                    updateState.installed.signingCertificateSha256 ?: "Unavailable",
                ),
            ),
            rawSummary = rawDiagnosticsFields(rawState),
            onBack = { screen = AppScreen.CAMERA },
            onNormalRescan = viewModel::rescanCameras,
            onDeepRescan = viewModel::deepRescanCameras,
            onResetDiscoveryCache = viewModel::resetDiscoveryCache,
            onOpenUpdates = {
                updateViewModel.refreshInstallPermission()
                screen = AppScreen.UPDATES
            },
            onExport = {
                val report = runCatching {
                    RawCompatibilityReportJson.append(viewModel.compatibilityReportJson(), rawState)
                }.getOrNull()
                if (report == null) {
                    Toast.makeText(context, "Could not create compatibility report", Toast.LENGTH_LONG)
                        .show()
                } else {
                    pendingReport = report
                    val shortSha = BuildConfig.GIT_SHA.take(12).ifBlank { "unknown" }
                    reportLauncher.launch("Camera-compatibility-$shortSha.json")
                }
            },
        )
        AppScreen.UPDATES -> UpdateScreen(
            state = updateState,
            onBack = { screen = AppScreen.DIAGNOSTICS },
            onCheck = updateViewModel::checkForUpdates,
            onDownload = updateViewModel::downloadUpdate,
            onInstall = updateViewModel::installUpdate,
            onOpenInstallPermissionSettings = updateViewModel::openInstallPermissionSettings,
            onResetFailure = updateViewModel::resetFailure,
        )
    }
}

private fun rawDiagnosticsFields(state: RawCaptureState): List<DiagnosticField> {
    val diagnostics = state.diagnostics
    val context = diagnostics.context
    return listOf(
        DiagnosticField("State", state.phase.name),
        DiagnosticField("rawSupported", diagnostics.rawSupported.name),
        DiagnosticField(
            "availableRawSizes",
            diagnostics.availableRawSizes.joinToString(transform = ::formatSize).ifBlank { "None" },
        ),
        DiagnosticField("selectedRawSize", diagnostics.selectedRawSize?.let(::formatSize) ?: "None"),
        DiagnosticField("canonicalFingerprint", context?.canonicalFingerprint ?: "None"),
        DiagnosticField("profileFingerprint", context?.profileFingerprint ?: "None"),
        DiagnosticField("openCameraId", context?.openCameraId ?: "None"),
        DiagnosticField("physicalTarget", context?.streamPhysicalCameraId ?: "None"),
        DiagnosticField("selectionGeneration", context?.selectionGeneration?.toString() ?: "None"),
        DiagnosticField("captureToken", context?.captureToken?.toString() ?: "None"),
        DiagnosticField("rawTimestamp", diagnostics.rawTimestamp?.toString() ?: "None"),
        DiagnosticField("resultTimestamp", diagnostics.resultTimestamp?.toString() ?: "None"),
        DiagnosticField("exposureTime", diagnostics.exposureTimeNs?.let { "$it ns" } ?: "None"),
        DiagnosticField("ISO", diagnostics.iso?.toString() ?: "None"),
        DiagnosticField("DNG width", diagnostics.dngWidth?.toString() ?: "None"),
        DiagnosticField("DNG height", diagnostics.dngHeight?.toString() ?: "None"),
        DiagnosticField("DNG bytes", diagnostics.dngBytes?.toString() ?: "None"),
        DiagnosticField("MediaStore URI", diagnostics.mediaStoreUri ?: "None"),
        DiagnosticField("captureDuration", diagnostics.captureDurationMs?.let { "$it ms" } ?: "None"),
        DiagnosticField("writeDuration", diagnostics.writeDurationMs?.let { "$it ms" } ?: "None"),
        DiagnosticField("lastRawError", diagnostics.lastRawError ?: "None"),
    )
}

private fun formatSize(size: Size2D): String = "${size.width}×${size.height}"

@Composable
private fun CameraPreview(viewModel: CameraViewModel) {
    val context = LocalContext.current
    val textureView = remember(context) { TextureView(context).apply { isOpaque = true } }
    AndroidView(factory = { textureView }, modifier = Modifier.fillMaxSize())
    DisposableEffect(textureView, viewModel) {
        viewModel.bindPreview(textureView)
        onDispose { viewModel.unbindPreview(textureView) }
    }
}
