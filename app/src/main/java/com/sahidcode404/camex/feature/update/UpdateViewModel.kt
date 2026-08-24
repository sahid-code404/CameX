package com.sahidcode404.camex.feature.update

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.sahidcode404.camex.BuildConfig
import com.sahidcode404.camex.core.update.AndroidApkInspector
import com.sahidcode404.camex.core.update.ApkInstaller
import com.sahidcode404.camex.core.update.ApkVerifier
import com.sahidcode404.camex.core.update.GitHubUpdateClient
import com.sahidcode404.camex.core.update.InstalledAppInfoReader
import com.sahidcode404.camex.core.update.UpdateAutoChecker
import com.sahidcode404.camex.core.update.UpdateChannel
import com.sahidcode404.camex.core.update.UpdateCheckResult
import com.sahidcode404.camex.core.update.UpdateException
import com.sahidcode404.camex.core.update.UpdateState
import com.sahidcode404.camex.core.update.UpdateUiState
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class UpdateViewModel(application: Application) : AndroidViewModel(application) {
    private val appContext = application.applicationContext
    private val installed = InstalledAppInfoReader.read(appContext)
    private val channel = UpdateChannel.fromBuildConfig(BuildConfig.OTA_CHANNEL)
    private val client = GitHubUpdateClient(appContext.cacheDir, channel = channel)
    private val autoChecker = UpdateAutoChecker(appContext, client, installed, channel)
    private val verifier = ApkVerifier(AndroidApkInspector(appContext), installed)
    private val mutableState = MutableStateFlow(
        UpdateUiState(
            installed = installed,
            channel = channel,
            installPermissionGranted = ApkInstaller.canRequestInstalls(appContext),
        ),
    )
    private var operationJob: Job? = null
    private var readyApk: File? = null

    val uiState: StateFlow<UpdateUiState> = mutableState.asStateFlow()

    fun refreshInstallPermission() {
        mutableState.update {
            it.copy(installPermissionGranted = ApkInstaller.canRequestInstalls(appContext))
        }
    }

    /**
     * Development builds check their rolling dev-latest channel on each app start/resume.
     * Stable builds keep the 12-hour gate. Background up-to-date/failure results stay quiet.
     */
    fun checkForUpdatesIfDue() {
        if (operationJob?.isActive == true) return
        operationJob = viewModelScope.launch {
            when (val result = autoChecker.checkIfDue()) {
                is UpdateCheckResult.Available -> {
                    readyApk = null
                    mutableState.update { it.copy(updateState = UpdateState.Available(result.update)) }
                }
                else -> Unit
            }
        }
    }

    fun checkForUpdates() {
        if (operationJob?.isActive == true) return
        operationJob = viewModelScope.launch {
            mutableState.update { it.copy(updateState = UpdateState.Checking) }
            when (val result = client.check(installed)) {
                UpdateCheckResult.UpToDate -> {
                    readyApk = null
                    mutableState.update { it.copy(updateState = UpdateState.UpToDate) }
                }
                is UpdateCheckResult.Available -> {
                    readyApk = null
                    mutableState.update { it.copy(updateState = UpdateState.Available(result.update)) }
                }
                is UpdateCheckResult.Failed -> {
                    mutableState.update { it.copy(updateState = UpdateState.Failed(result.message)) }
                }
            }
        }
    }

    fun downloadUpdate() {
        val update = (mutableState.value.updateState as? UpdateState.Available)?.update ?: return
        if (operationJob?.isActive == true) return
        operationJob = viewModelScope.launch {
            var partFile: File? = null
            try {
                mutableState.update {
                    it.copy(updateState = UpdateState.Downloading(update, 0L, null))
                }
                partFile = client.download(update) { downloaded, total ->
                    mutableState.update {
                        it.copy(updateState = UpdateState.Downloading(update, downloaded, total))
                    }
                }
                mutableState.update { it.copy(updateState = UpdateState.Verifying(update)) }
                verifier.verify(partFile, update.manifest)
                val verified = client.promoteVerified(partFile, update)
                partFile = null
                readyApk = verified
                refreshInstallPermission()
                mutableState.update {
                    it.copy(updateState = UpdateState.ReadyToInstall(update, verified.absolutePath))
                }
            } catch (cancelled: CancellationException) {
                client.discard(partFile)
                throw cancelled
            } catch (error: UpdateException) {
                client.discard(partFile)
                mutableState.update {
                    it.copy(updateState = UpdateState.Failed(error.message ?: error.code.name))
                }
            } catch (error: Throwable) {
                client.discard(partFile)
                mutableState.update {
                    it.copy(updateState = UpdateState.Failed("Update download failed: ${error.javaClass.simpleName}"))
                }
            }
        }
    }

    fun installUpdate() {
        val state = mutableState.value.updateState as? UpdateState.ReadyToInstall ?: return
        val apk = readyApk ?: File(state.apkPath)
        if (!ApkInstaller.canRequestInstalls(appContext)) {
            refreshInstallPermission()
            return
        }
        runCatching { ApkInstaller.install(appContext, apk) }
            .onFailure { error ->
                mutableState.update {
                    it.copy(updateState = UpdateState.Failed("Could not open Android installer: ${error.javaClass.simpleName}"))
                }
            }
    }

    fun openInstallPermissionSettings() {
        ApkInstaller.openUnknownSourcesSettings(appContext)
    }

    fun resetFailure() {
        if (mutableState.value.updateState is UpdateState.Failed) {
            mutableState.update { it.copy(updateState = UpdateState.Idle) }
        }
    }

    override fun onCleared() {
        operationJob?.cancel()
    }
}
