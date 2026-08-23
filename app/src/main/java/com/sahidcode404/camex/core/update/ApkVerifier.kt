package com.sahidcode404.camex.core.update

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import com.sahidcode404.camex.BuildConfig
import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

interface ApkInspector {
    suspend fun inspect(apkFile: File): ApkInspection
}

class AndroidApkInspector(context: Context) : ApkInspector {
    private val packageManager = context.applicationContext.packageManager

    override suspend fun inspect(apkFile: File): ApkInspection = withContext(Dispatchers.IO) {
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            PackageManager.GET_SIGNING_CERTIFICATES
        } else {
            @Suppress("DEPRECATION")
            PackageManager.GET_SIGNATURES
        }
        val info = @Suppress("DEPRECATION")
        packageManager.getPackageArchiveInfo(apkFile.absolutePath, flags)
            ?: throw UpdateException(UpdateFailureCode.PACKAGE_MISMATCH, "Downloaded file is not a readable APK")
        ApkInspection(
            packageName = info.packageName.orEmpty(),
            versionCode = info.versionCodeCompat(),
            signingCertificateSha256 = info.signerSha256()
                ?: throw UpdateException(UpdateFailureCode.SIGNATURE_MISMATCH, "Downloaded APK has no readable signer"),
        )
    }
}

class ApkVerifier(
    private val inspector: ApkInspector,
    private val installed: InstalledAppInfo,
) {
    suspend fun verify(file: File, manifest: ReleaseManifest): ApkInspection = withContext(Dispatchers.IO) {
        if (!file.isFile || file.length() <= 0L) {
            throw UpdateException(UpdateFailureCode.STORAGE, "Downloaded APK is missing")
        }
        val actualHash = sha256(file)
        val inspection = inspector.inspect(file)
        UpdateCandidateValidator.validate(
            manifest = manifest,
            installed = installed,
            inspection = inspection,
            actualSha256 = actualHash,
        )
        inspection
    }
}

object UpdateCandidateValidator {
    fun validate(
        manifest: ReleaseManifest,
        installed: InstalledAppInfo,
        inspection: ApkInspection,
        actualSha256: String,
    ) {
        if (manifest.schema != UPDATE_MANIFEST_SCHEMA) {
            fail(UpdateFailureCode.INVALID_MANIFEST, "Unsupported update manifest schema")
        }
        if (installed.sdkInt < manifest.minSdk) {
            fail(UpdateFailureCode.INVALID_MANIFEST, "Update requires Android API ${manifest.minSdk}")
        }
        if (manifest.versionCode == installed.versionCode) {
            fail(UpdateFailureCode.SAME_VERSION, "Update version is the same as installed Camera")
        }
        if (manifest.versionCode < installed.versionCode) {
            fail(UpdateFailureCode.DOWNGRADE, "Update version is older than installed Camera")
        }
        if (inspection.packageName != UPDATE_PACKAGE_NAME || inspection.packageName != installed.packageName) {
            fail(UpdateFailureCode.PACKAGE_MISMATCH, "Downloaded APK package does not match Camera")
        }
        if (inspection.versionCode != manifest.versionCode) {
            fail(UpdateFailureCode.INVALID_MANIFEST, "Downloaded APK versionCode does not match the manifest")
        }
        if (!normalizeDigest(actualSha256).equals(normalizeDigest(manifest.sha256), ignoreCase = true)) {
            fail(UpdateFailureCode.HASH_MISMATCH, "Downloaded APK SHA-256 does not match the manifest")
        }
        val installedSigner = installed.signingCertificateSha256
            ?: fail(UpdateFailureCode.SIGNATURE_MISMATCH, "Installed Camera signer is unavailable")
        val actualSigner = normalizeDigest(inspection.signingCertificateSha256)
        val manifestSigner = normalizeDigest(manifest.signingCertSha256)
        val trustedSigner = normalizeDigest(installedSigner)
        if (actualSigner != manifestSigner || actualSigner != trustedSigner) {
            fail(UpdateFailureCode.SIGNATURE_MISMATCH, "Downloaded APK signer does not match installed Camera")
        }
    }

    private fun normalizeDigest(value: String): String = value
        .trim()
        .replace(":", "")
        .lowercase()
        .also { normalized ->
            if (!normalized.matches(Regex("^[0-9a-f]{64}$"))) {
                fail(UpdateFailureCode.INVALID_MANIFEST, "Invalid SHA-256 digest")
            }
        }

    private fun fail(code: UpdateFailureCode, message: String): Nothing =
        throw UpdateException(code, message)
}

object InstalledAppInfoReader {
    fun read(context: Context): InstalledAppInfo {
        val appContext = context.applicationContext
        val packageManager = appContext.packageManager
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            PackageManager.GET_SIGNING_CERTIFICATES
        } else {
            @Suppress("DEPRECATION")
            PackageManager.GET_SIGNATURES
        }
        val info = @Suppress("DEPRECATION")
        packageManager.getPackageInfo(appContext.packageName, flags)
        return InstalledAppInfo(
            packageName = appContext.packageName,
            versionCode = info.versionCodeCompat(),
            versionName = info.versionName.orEmpty(),
            gitSha = BuildConfig.GIT_SHA,
            signingCertificateSha256 = info.signerSha256(),
            sdkInt = Build.VERSION.SDK_INT,
        )
    }
}

fun sha256(file: File): String {
    val digest = MessageDigest.getInstance("SHA-256")
    FileInputStream(file).use { input ->
        val buffer = ByteArray(128 * 1024)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            digest.update(buffer, 0, count)
        }
    }
    return digest.digest().joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
}

private fun PackageInfo.versionCodeCompat(): Long = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
    longVersionCode
} else {
    @Suppress("DEPRECATION")
    versionCode.toLong()
}

private fun PackageInfo.signerSha256(): String? {
    val bytes = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        signingInfo?.apkContentsSigners?.firstOrNull()?.toByteArray()
    } else {
        @Suppress("DEPRECATION")
        signatures?.firstOrNull()?.toByteArray()
    } ?: return null
    return MessageDigest.getInstance("SHA-256")
        .digest(bytes)
        .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
}
