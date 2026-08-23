package com.sahidcode404.camex.core.update

object UpdateCandidateValidator {
    fun validateManifest(manifest: UpdateManifest, installed: InstalledAppInfo) {
        if (manifest.packageName != DEVELOPMENT_PACKAGE_NAME ||
            manifest.packageName != installed.packageName
        ) fail(UpdateFailureCode.PACKAGE_MISMATCH, "Update package does not match the installed app")
        if (manifest.minimumSdk > installed.sdkInt) {
            fail(UpdateFailureCode.INVALID_MANIFEST, "Update requires Android API ${manifest.minimumSdk}")
        }
        if (manifest.versionCode == installed.versionCode) {
            fail(UpdateFailureCode.SAME_VERSION, "Candidate versionCode equals installed versionCode")
        }
        if (manifest.versionCode < installed.versionCode) {
            fail(UpdateFailureCode.DOWNGRADE, "Candidate versionCode is older than installed versionCode")
        }
        val pinned = installed.pinnedSigningCertificateSha256?.let(::normalizeSha256)
            ?: fail(UpdateFailureCode.SIGNATURE_MISMATCH, "Development signer is not pinned in this build")
        if (normalizeSha256(manifest.apk.signingCertificateSha256) != pinned) {
            fail(UpdateFailureCode.SIGNATURE_MISMATCH, "Manifest signer does not match the pinned development signer")
        }
    }

    fun validateDownloaded(
        manifest: UpdateManifest,
        installed: InstalledAppInfo,
        inspection: ApkInspection,
        actualSha256: String,
    ) {
        validateManifest(manifest, installed)
        if (inspection.packageName != manifest.packageName) {
            fail(UpdateFailureCode.PACKAGE_MISMATCH, "Downloaded APK has the wrong package name")
        }
        if (inspection.versionCode != manifest.versionCode) {
            val code = if (inspection.versionCode <= installed.versionCode) {
                if (inspection.versionCode == installed.versionCode) {
                    UpdateFailureCode.SAME_VERSION
                } else {
                    UpdateFailureCode.DOWNGRADE
                }
            } else {
                UpdateFailureCode.INVALID_MANIFEST
            }
            fail(code, "Downloaded APK versionCode does not match update metadata")
        }
        val expectedHash = normalizeSha256(manifest.apk.sha256)
        if (normalizeSha256(actualSha256) != expectedHash) {
            fail(UpdateFailureCode.HASH_MISMATCH, "Downloaded APK SHA-256 does not match update metadata")
        }
        val expectedSigner = normalizeSha256(manifest.apk.signingCertificateSha256)
        val actualSigner = normalizeSha256(inspection.signingCertificateSha256)
        val pinnedSigner = installed.pinnedSigningCertificateSha256?.let(::normalizeSha256)
            ?: fail(UpdateFailureCode.SIGNATURE_MISMATCH, "Development signer is not pinned")
        if (actualSigner != expectedSigner || actualSigner != pinnedSigner) {
            fail(UpdateFailureCode.SIGNATURE_MISMATCH, "Downloaded APK signer does not match the pinned development signer")
        }
    }

    private fun fail(code: UpdateFailureCode, message: String): Nothing =
        throw UpdateException(code, message)
}

object UpdateCheckPolicy {
    const val AUTOMATIC_INTERVAL_MS: Long = 24L * 60L * 60L * 1000L

    fun shouldCheckAutomatically(
        lastCheckEpochMs: Long?,
        nowEpochMs: Long,
        intervalMs: Long = AUTOMATIC_INTERVAL_MS,
    ): Boolean {
        if (lastCheckEpochMs == null) return true
        if (nowEpochMs < lastCheckEpochMs) return true
        return nowEpochMs - lastCheckEpochMs >= intervalMs
    }
}

enum class UpdateStateKind {
    IDLE,
    CHECKING,
    UP_TO_DATE,
    UPDATE_AVAILABLE,
    DOWNLOADING,
    VERIFYING,
    READY_TO_INSTALL,
    AWAITING_INSTALL_PERMISSION,
    AWAITING_USER_ACTION,
    INSTALLING,
    INSTALLED,
    FAILED,
}

fun UpdateState.kind(): UpdateStateKind = when (this) {
    UpdateState.Idle -> UpdateStateKind.IDLE
    UpdateState.Checking -> UpdateStateKind.CHECKING
    is UpdateState.UpToDate -> UpdateStateKind.UP_TO_DATE
    is UpdateState.UpdateAvailable -> UpdateStateKind.UPDATE_AVAILABLE
    is UpdateState.Downloading -> UpdateStateKind.DOWNLOADING
    is UpdateState.Verifying -> UpdateStateKind.VERIFYING
    is UpdateState.ReadyToInstall -> UpdateStateKind.READY_TO_INSTALL
    is UpdateState.AwaitingInstallPermission -> UpdateStateKind.AWAITING_INSTALL_PERMISSION
    is UpdateState.AwaitingUserAction -> UpdateStateKind.AWAITING_USER_ACTION
    is UpdateState.Installing -> UpdateStateKind.INSTALLING
    is UpdateState.Installed -> UpdateStateKind.INSTALLED
    is UpdateState.Failed -> UpdateStateKind.FAILED
}

object UpdateStateTransitionPolicy {
    fun isAllowed(from: UpdateState, to: UpdateState): Boolean {
        val left = from.kind()
        val right = to.kind()
        if (left == right && left == UpdateStateKind.DOWNLOADING) return true
        if (right == UpdateStateKind.FAILED) return left != UpdateStateKind.INSTALLED
        return right in allowed[left].orEmpty()
    }

    private val allowed: Map<UpdateStateKind, Set<UpdateStateKind>> = mapOf(
        UpdateStateKind.IDLE to setOf(UpdateStateKind.CHECKING),
        UpdateStateKind.CHECKING to setOf(
            UpdateStateKind.UP_TO_DATE,
            UpdateStateKind.UPDATE_AVAILABLE,
        ),
        UpdateStateKind.UP_TO_DATE to setOf(UpdateStateKind.CHECKING),
        UpdateStateKind.UPDATE_AVAILABLE to setOf(
            UpdateStateKind.CHECKING,
            UpdateStateKind.DOWNLOADING,
        ),
        UpdateStateKind.DOWNLOADING to setOf(UpdateStateKind.VERIFYING),
        UpdateStateKind.VERIFYING to setOf(UpdateStateKind.READY_TO_INSTALL),
        UpdateStateKind.READY_TO_INSTALL to setOf(
            UpdateStateKind.AWAITING_INSTALL_PERMISSION,
            UpdateStateKind.INSTALLING,
        ),
        UpdateStateKind.AWAITING_INSTALL_PERMISSION to setOf(
            UpdateStateKind.INSTALLING,
            UpdateStateKind.CHECKING,
        ),
        UpdateStateKind.AWAITING_USER_ACTION to setOf(
            UpdateStateKind.INSTALLING,
            UpdateStateKind.INSTALLED,
        ),
        UpdateStateKind.INSTALLING to setOf(
            UpdateStateKind.AWAITING_USER_ACTION,
            UpdateStateKind.INSTALLED,
        ),
        UpdateStateKind.INSTALLED to emptySet(),
        UpdateStateKind.FAILED to setOf(
            UpdateStateKind.IDLE,
            UpdateStateKind.CHECKING,
            UpdateStateKind.READY_TO_INSTALL,
        ),
    )
}
