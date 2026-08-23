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
    suspend fun verify(file: File, manifest: UpdateManifest): ApkInspection = withContext(Dispatchers.IO) {
        if (!file.isFile || file.length() <= 0L) {
            throw UpdateException(UpdateFailureCode.STORAGE, "Downloaded APK is missing")
        }
        val actualHash = sha256(file)
        val inspection = inspector.inspect(file)
        UpdateCandidateValidator.validateDownloaded(
            manifest = manifest,
            installed = installed,
            inspection = inspection,
            actualSha256 = actualHash,
        )
        inspection
    }
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
        val pinned = BuildConfig.OTA_SIGNING_CERT_SHA256
            .trim()
            .takeUnless { it.isBlank() || it.equals("UNPINNED", ignoreCase = true) }
            ?.let { runCatching { normalizeSha256(it) }.getOrNull() }
        return InstalledAppInfo(
            packageName = appContext.packageName,
            versionCode = info.versionCodeCompat(),
            versionName = info.versionName.orEmpty(),
            gitSha = BuildConfig.GIT_SHA,
            channel = BuildConfig.OTA_CHANNEL,
            pinnedSigningCertificateSha256 = pinned,
            installedSigningCertificateSha256 = info.signerSha256(),
            sdkInt = Build.VERSION.SDK_INT,
            otaEnabled = BuildConfig.OTA_ENABLED,
        )
    }
}

fun sha256(file: File): String {
    val digest = MessageDigest.getInstance("SHA-256")
    FileInputStream(file).use { input ->
        val buffer = ByteArray(64 * 1024)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            digest.update(buffer, 0, count)
        }
    }
    return digest.digest().joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
}

private fun PackageInfo.versionCodeCompat(): Int = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
    longVersionCode.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
} else {
    @Suppress("DEPRECATION")
    versionCode
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
