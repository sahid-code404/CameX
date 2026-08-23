package com.sahidcode404.camex.core.update

import kotlinx.serialization.Serializable

const val DEVELOPMENT_UPDATE_SCHEMA_VERSION = 1
const val DEVELOPMENT_PACKAGE_NAME = "com.sahidcode404.camex"
const val DEVELOPMENT_RELEASE_TAG_PREFIX = "dev-ota-v"

@Serializable
data class UpdateManifest(
    val schemaVersion: Int,
    val channel: String,
    val packageName: String,
    val versionCode: Int,
    val versionName: String,
    val gitSha: String,
    val apk: UpdateApkManifest,
    val minimumSdk: Int,
    val publishedAt: String,
    val mandatory: Boolean = false,
    val releaseNotes: String = "",
)

@Serializable
data class UpdateApkManifest(
    val name: String,
    val downloadUrl: String,
    val sha256: String,
    val size: Long,
    val signingCertificateSha256: String,
)

data class InstalledAppInfo(
    val packageName: String,
    val versionCode: Int,
    val versionName: String,
    val gitSha: String,
    val channel: String,
    val pinnedSigningCertificateSha256: String?,
    val installedSigningCertificateSha256: String?,
    val sdkInt: Int,
    val otaEnabled: Boolean,
)

data class ApkInspection(
    val packageName: String,
    val versionCode: Int,
    val signingCertificateSha256: String,
)

enum class UpdateFailureCode {
    NETWORK,
    NO_RELEASE,
    INVALID_MANIFEST,
    HASH_MISMATCH,
    SIGNATURE_MISMATCH,
    PACKAGE_MISMATCH,
    SAME_VERSION,
    DOWNGRADE,
    STORAGE,
    INSTALL_PERMISSION_REQUIRED,
    INSTALL_CANCELLED,
    INSTALL_FAILED,
}

class UpdateException(
    val code: UpdateFailureCode,
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)

sealed interface UpdateState {
    data object Idle : UpdateState
    data object Checking : UpdateState
    data class UpToDate(val checkedAtEpochMs: Long) : UpdateState
    data class UpdateAvailable(val manifest: UpdateManifest) : UpdateState
    data class Downloading(
        val manifest: UpdateManifest,
        val downloadedBytes: Long,
        val totalBytes: Long?,
    ) : UpdateState
    data class Verifying(val manifest: UpdateManifest) : UpdateState
    data class ReadyToInstall(val manifest: UpdateManifest, val apkPath: String) : UpdateState
    data class AwaitingInstallPermission(
        val manifest: UpdateManifest,
        val apkPath: String,
    ) : UpdateState
    data class AwaitingUserAction(val manifest: UpdateManifest) : UpdateState
    data class Installing(val manifest: UpdateManifest) : UpdateState
    data class Installed(val versionCode: Int) : UpdateState
    data class Failed(
        val code: UpdateFailureCode,
        val detail: String,
    ) : UpdateState
}

data class UpdatePreferencesSnapshot(
    val lastCheckEpochMs: Long? = null,
    val lastKnownVersionCode: Int? = null,
    val dismissedVersionCode: Int? = null,
)

data class UpdateUiState(
    val installed: InstalledAppInfo,
    val updateState: UpdateState = UpdateState.Idle,
    val preferences: UpdatePreferencesSnapshot = UpdatePreferencesSnapshot(),
)
