package com.sahidcode404.camex.core.update

import kotlinx.serialization.Serializable

const val UPDATE_MANIFEST_SCHEMA = 1
const val UPDATE_PACKAGE_NAME = "com.sahidcode404.camex"

@Serializable
data class ReleaseManifest(
    val schema: Int,
    val versionCode: Long,
    val versionName: String,
    val minSdk: Int,
    val apkAssetName: String,
    val sha256: String,
    val signingCertSha256: String,
    val changelog: String = "",
    val mandatory: Boolean = false,
)

data class AvailableUpdate(
    val manifest: ReleaseManifest,
    val apkUrl: String,
)

data class InstalledAppInfo(
    val packageName: String,
    val versionCode: Long,
    val versionName: String,
    val gitSha: String,
    val signingCertificateSha256: String?,
    val sdkInt: Int,
)

data class ApkInspection(
    val packageName: String,
    val versionCode: Long,
    val signingCertificateSha256: String,
)

enum class UpdateFailureCode {
    NETWORK,
    INVALID_MANIFEST,
    HASH_MISMATCH,
    SIGNATURE_MISMATCH,
    PACKAGE_MISMATCH,
    SAME_VERSION,
    DOWNGRADE,
    STORAGE,
}

class UpdateException(
    val code: UpdateFailureCode,
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)

sealed interface UpdateCheckResult {
    data object UpToDate : UpdateCheckResult
    data class Available(val update: AvailableUpdate) : UpdateCheckResult
    data class Failed(val message: String) : UpdateCheckResult
}

sealed interface UpdateState {
    data object Idle : UpdateState
    data object Checking : UpdateState
    data object UpToDate : UpdateState
    data class Available(val update: AvailableUpdate) : UpdateState
    data class Downloading(
        val update: AvailableUpdate,
        val downloadedBytes: Long,
        val totalBytes: Long?,
    ) : UpdateState
    data class Verifying(val update: AvailableUpdate) : UpdateState
    data class ReadyToInstall(val update: AvailableUpdate, val apkPath: String) : UpdateState
    data class Failed(val message: String) : UpdateState
}

data class UpdateUiState(
    val installed: InstalledAppInfo,
    val updateState: UpdateState = UpdateState.Idle,
    val installPermissionGranted: Boolean = true,
)
