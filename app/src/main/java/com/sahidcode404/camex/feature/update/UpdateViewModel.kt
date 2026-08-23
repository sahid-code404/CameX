package com.sahidcode404.camex.feature.update

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.sahidcode404.camex.core.update.AndroidApkInspector
import com.sahidcode404.camex.core.update.ApkVerifier
import com.sahidcode404.camex.core.update.DefaultUpdateNetworkClient
import com.sahidcode404.camex.core.update.InstallLaunchResult
import com.sahidcode404.camex.core.update.InstallResultBus
import com.sahidcode404.camex.core.update.InstallResultEvent
import com.sahidcode404.camex.core.update.InstalledAppInfo
import com.sahidcode404.camex.core.update.InstalledAppInfoReader
import com.sahidcode404.camex.core.update.PackageInstallerController
import com.sahidcode404.camex.core.update.UpdateCheckResult
import com.sahidcode404.camex.core.update.UpdateDownloader
import com.sahidcode404.camex.core.update.UpdateException
import com.sahidcode404.camex.core.update.UpdateFailureCode
import com.sahidcode404.camex.core.update.UpdateManifest
import com.sahidcode404.camex.core.update.UpdatePreferences
import com.sahidcode404.camex.core.update.UpdatePreferencesSnapshot
import com.sahidcode404.camex.core.update.UpdateRepository
import com.sahidcode404.camex.core.update.UpdateState
import com.sahidcode404.camex.core.update.UpdateStateTransitionPolicy
import com.sahidcode404.camex.core.update.UpdateUiState
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Development OTA orchestration only. Camera startup never calls this class's network methods.
 * The constructor reads local package/DataStore state only.
 */
class UpdateViewModel(application: Application) : AndroidViewModel(application) {
    private val appContext = application.applicationContext
    private val installed: InstalledAppInfo = InstalledAppInfoReader.read(appContext)
    private val preferencesStore = UpdatePreferences(appContext)
    private val preferences = preferencesStore.state.stateIn(
        viewModelScope,
        SharingStarted.Eagerly,
        UpdatePreferencesSnapshot(),
    )
    private val network = DefaultUpdateNetworkClient()
    private val repository = UpdateRepository(network)
    private val downloader = UpdateDownloader(File(appContext.cacheDir, "development-ota"), network)
    private val verifier = ApkVerifier(AndroidApkInspector(appContext), installed)
    private val installer = PackageInstallerController(appContext)
    private val mutableState = MutableStateFlow<UpdateState>(UpdateState.Idle)
    private var operationJob: Job? = null
    private var verifiedApk: File? = null

    val uiState: StateFlow<UpdateUiState> = combine(mutableState, preferences) { state, prefs ->
        UpdateUiState(installed = installed, updateState = state, preferences = prefs)
    }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(stopTimeoutMillis = 5_000L),
        UpdateUiState(installed),
    )

    init {
        viewModelScope.launch {
            InstallResultBus.events.collect(::handleInstallResult)
        }
    }

    fun checkForUpdates() {
        if (operationJob?.isActive == true) return
        if (!installed.otaEnabled || installed.pinnedSigningCertificateSha256 == null) {
            fail(
                UpdateFailureCode.SIGNATURE_MISMATCH,
                "This APK is not a stable development-OTA build",
            )
            return
        }
        operationJob = viewModelScope.launch {
            try {
                transition(UpdateState.Checking)
                val result = repository.check(installed)
                val now = System.currentTimeMillis()
                when (result) {
                    UpdateCheckResult.NoRelease -> {
                        preferencesStore.markChecked(now, null)
                        transition(
                            UpdateState.Failed(
                                UpdateFailureCode.NO_RELEASE,
                                "No development OTA prerelease is published yet",
                            ),
                        )
                    }
                    UpdateCheckResult.UpToDate -> {
                        preferencesStore.markChecked(now, installed.versionCode)
                        transition(UpdateState.UpToDate(now))
                    }
                    is UpdateCheckResult.Available -> {
                        preferencesStore.markChecked(now, result.manifest.versionCode)
                        transition(UpdateState.UpdateAvailable(result.manifest))
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: UpdateException) {
                fail(error.code, error.message.orEmpty())
            } catch (error: Throwable) {
                fail(UpdateFailureCode.NETWORK, "Update check failed: ${error.javaClass.simpleName}")
            }
        }
    }

    fun downloadAndInstall() {
        val manifest = (mutableState.value as? UpdateState.UpdateAvailable)?.manifest ?: return
        if (operationJob?.isActive == true) return
        operationJob = viewModelScope.launch {
            var part: File? = null
            try {
                transition(UpdateState.Downloading(manifest, 0L, manifest.apk.size))
                part = downloader.download(manifest) { downloaded, total ->
                    transition(UpdateState.Downloading(manifest, downloaded, total ?: manifest.apk.size))
                }
                transition(UpdateState.Verifying(manifest))
                verifier.verify(part, manifest)
                val ready = downloader.promoteVerified(part, manifest)
                part = null
                verifiedApk = ready
                transition(UpdateState.ReadyToInstall(manifest, ready.absolutePath))
                installReady()
            } catch (cancelled: CancellationException) {
                downloader.discard(part)
                throw cancelled
            } catch (error: UpdateException) {
                downloader.discard(part)
                fail(error.code, error.message.orEmpty())
            } catch (error: Throwable) {
                downloader.discard(part)
                fail(UpdateFailureCode.NETWORK, "Update download failed: ${error.javaClass.simpleName}")
            }
        }
    }

    fun retryInstall() {
        val state = mutableState.value
        if (state !is UpdateState.ReadyToInstall && state !is UpdateState.AwaitingInstallPermission) return
        if (operationJob?.isActive == true) return
        operationJob = viewModelScope.launch {
            try {
                installReady()
            } catch (error: UpdateException) {
                fail(error.code, error.message.orEmpty())
            }
        }
    }

    fun resetFailure() {
        if (mutableState.value is UpdateState.Failed) transition(UpdateState.Idle)
    }

    fun canRequestPackageInstalls(): Boolean = installer.canRequestPackageInstalls()

    private suspend fun installReady() {
        val manifest = currentManifest() ?: return
        val file = verifiedApk?.takeIf(File::isFile)
            ?: (mutableState.value as? UpdateState.ReadyToInstall)?.apkPath?.let(::File)?.takeIf(File::isFile)
            ?: (mutableState.value as? UpdateState.AwaitingInstallPermission)?.apkPath?.let(::File)?.takeIf(File::isFile)
            ?: throw UpdateException(UpdateFailureCode.STORAGE, "Verified update APK is missing")
        verifiedApk = file
        when (val result = installer.install(file, manifest.packageName)) {
            InstallLaunchResult.PermissionRequired -> {
                val current = mutableState.value
                if (current !is UpdateState.AwaitingInstallPermission) {
                    transition(UpdateState.AwaitingInstallPermission(manifest, file.absolutePath))
                }
            }
            is InstallLaunchResult.Started -> transition(UpdateState.Installing(manifest))
        }
    }

    private fun handleInstallResult(event: InstallResultEvent) {
        val manifest = currentManifest() ?: return
        when (event) {
            is InstallResultEvent.AwaitingUserAction -> {
                val current = mutableState.value
                if (current is UpdateState.Installing) {
                    transition(UpdateState.AwaitingUserAction(manifest))
                }
            }
            is InstallResultEvent.Installed -> {
                val current = mutableState.value
                if (current is UpdateState.Installing || current is UpdateState.AwaitingUserAction) {
                    transition(UpdateState.Installed(manifest.versionCode))
                }
            }
            is InstallResultEvent.Failed -> fail(event.code, event.detail)
        }
    }

    private fun currentManifest(): UpdateManifest? = when (val state = mutableState.value) {
        is UpdateState.UpdateAvailable -> state.manifest
        is UpdateState.Downloading -> state.manifest
        is UpdateState.Verifying -> state.manifest
        is UpdateState.ReadyToInstall -> state.manifest
        is UpdateState.AwaitingInstallPermission -> state.manifest
        is UpdateState.AwaitingUserAction -> state.manifest
        is UpdateState.Installing -> state.manifest
        else -> null
    }

    private fun transition(next: UpdateState) {
        val current = mutableState.value
        if (!UpdateStateTransitionPolicy.isAllowed(current, next)) return
        mutableState.value = next
    }

    private fun fail(code: UpdateFailureCode, detail: String) {
        val next = UpdateState.Failed(code, detail.ifBlank { code.name })
        if (UpdateStateTransitionPolicy.isAllowed(mutableState.value, next)) {
            mutableState.value = next
        } else if (mutableState.value is UpdateState.Failed) {
            mutableState.value = next
        }
    }

    override fun onCleared() {
        operationJob?.cancel()
        super.onCleared()
    }
}
