package com.sahidcode404.camex

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
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
import com.sahidcode404.camex.feature.camera.CameraScreen
import com.sahidcode404.camex.feature.diagnostics.DiagnosticsScreen
import com.sahidcode404.camex.feature.lenssettings.LensSettingsScreen
import com.sahidcode404.camex.ui.theme.CameraTheme
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val viewModel: CameraViewModel by viewModels()

    override fun onCreate(savedInstanceState: android.os.Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        viewModel.onCameraPermission(hasCameraPermission())
        setContent {
            CameraTheme(darkTheme = true) {
                CameraApplication(viewModel, this@MainActivity)
            }
        }
    }

    override fun onStart() {
        super.onStart()
        viewModel.onCameraPermission(hasCameraPermission())
    }

    override fun onStop() {
        viewModel.onBackground()
        super.onStop()
    }

    private fun hasCameraPermission(): Boolean = ContextCompat.checkSelfPermission(
        this,
        Manifest.permission.CAMERA,
    ) == PackageManager.PERMISSION_GRANTED
}

private enum class AppScreen { CAMERA, LENS_SETTINGS, DIAGNOSTICS }

@Composable
private fun CameraApplication(
    viewModel: CameraViewModel,
    activity: ComponentActivity,
) {
    val context = LocalContext.current
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var screen by rememberSaveable { mutableStateOf(AppScreen.CAMERA) }
    var pendingReport by remember { mutableStateOf<String?>(null) }
    var permissionRequested by rememberSaveable { mutableStateOf(false) }

    BackHandler(enabled = screen != AppScreen.CAMERA) { screen = AppScreen.CAMERA }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted -> viewModel.onCameraPermission(granted) }

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
            previewContent = { CameraPreview(viewModel) },
            permissionPermanentlyDenied = !state.camera.permissionGranted &&
                permissionRequested &&
                !activity.shouldShowRequestPermissionRationale(
                    Manifest.permission.CAMERA,
                ),
            onRequestPermission = {
                permissionRequested = true
                permissionLauncher.launch(Manifest.permission.CAMERA)
            },
            onOpenAppSettings = {
                context.startActivity(
                    Intent(
                        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.fromParts("package", context.packageName, null),
                    ),
                )
            },
            onSelectLens = viewModel::selectLens,
            onSwitchFacing = viewModel::switchFacing,
            onOpenLensSettings = { screen = AppScreen.LENS_SETTINGS },
            onOpenDiagnostics = { screen = AppScreen.DIAGNOSTICS },
            onRetry = viewModel::rescanCameras,
        )
        AppScreen.LENS_SETTINGS -> LensSettingsScreen(
            lenses = state.lensSettings,
            onBack = { screen = AppScreen.CAMERA },
            onSetVisible = viewModel::setLensVisible,
            onRename = viewModel::renameLens,
            onMove = viewModel::moveLens,
            onSetOneXReference = viewModel::setOneXReference,
        )
        AppScreen.DIAGNOSTICS -> DiagnosticsScreen(
            state = state.diagnostics,
            onBack = { screen = AppScreen.CAMERA },
            onNormalRescan = viewModel::rescanCameras,
            onDeepRescan = viewModel::deepRescanCameras,
            onResetDiscoveryCache = viewModel::resetDiscoveryCache,
            onExport = {
                val report = runCatching(viewModel::compatibilityReportJson).getOrNull()
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
    }
}

@Composable
private fun CameraPreview(viewModel: CameraViewModel) {
    val context = LocalContext.current
    val textureView = remember(context) {
        TextureView(context).apply { isOpaque = true }
    }
    AndroidView(
        factory = { textureView },
        modifier = Modifier.fillMaxSize(),
    )
    DisposableEffect(textureView, viewModel) {
        viewModel.bindPreview(textureView)
        onDispose { viewModel.unbindPreview() }
    }
}
