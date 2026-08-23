package com.sahidcode404.camex.core.diagnostics

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.annotation.RequiresApi
import com.sahidcode404.camex.BuildConfig
import com.sahidcode404.camex.nativebridge.NativeBridge
import kotlinx.serialization.Serializable

@Serializable
data class DeviceSnapshot(
    val manufacturer: String,
    val brand: String,
    val model: String,
    val device: String,
    val product: String,
    val hardware: String,
    val buildFingerprint: String,
)

@Serializable
data class AndroidSnapshot(
    val sdkInt: Int,
    val release: String,
    val securityPatch: String?,
)

@Serializable
data class GraphicsSnapshot(
    val vulkanHardwareLevel: Int?,
    val vulkanHardwareVersion: Int?,
    val vulkanVersionReadable: String?,
    val openGlEsVersion: String?,
)

@Serializable
data class AppBuildSnapshot(
    val applicationId: String,
    val versionName: String,
    val versionCode: Int,
    val gitSha: String,
    val buildType: String,
    val buildTimestampUtc: String,
    val nativeLoaded: Boolean,
    val nativeVersion: String?,
    val nativeSelfTestPassed: Boolean,
)

data class PlatformDiagnosticsSnapshot(
    val device: DeviceSnapshot,
    val android: AndroidSnapshot,
    val graphics: GraphicsSnapshot,
    val app: AppBuildSnapshot,
)

object PlatformDiagnostics {
    fun collect(context: Context): PlatformDiagnosticsSnapshot {
        val packageManager = context.packageManager
        val native = NativeBridge.status()
        val packageInfo = @Suppress("DEPRECATION")
        packageManager.getPackageInfo(context.packageName, 0)

        return PlatformDiagnosticsSnapshot(
            device = DeviceSnapshot(
                manufacturer = Build.MANUFACTURER.sanitizedBuildValue(),
                brand = Build.BRAND.sanitizedBuildValue(),
                model = Build.MODEL.sanitizedBuildValue(),
                device = Build.DEVICE.sanitizedBuildValue(),
                product = Build.PRODUCT.sanitizedBuildValue(),
                hardware = Build.HARDWARE.sanitizedBuildValue(),
                buildFingerprint = Build.FINGERPRINT.sanitizedBuildValue(maxLength = 512),
            ),
            android = AndroidSnapshot(
                sdkInt = Build.VERSION.SDK_INT,
                release = Build.VERSION.RELEASE.sanitizedBuildValue(),
                securityPatch = Build.VERSION.SECURITY_PATCH
                    .sanitizedBuildValue()
                    .takeIf(String::isNotBlank),
            ),
            graphics = packageManager.graphicsSnapshot(),
            app = AppBuildSnapshot(
                applicationId = BuildConfig.APPLICATION_ID,
                versionName = BuildConfig.VERSION_NAME,
                versionCode = if (Build.VERSION.SDK_INT >= 28) {
                    packageInfo.longVersionCode.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
                } else {
                    @Suppress("DEPRECATION") packageInfo.versionCode
                },
                gitSha = BuildConfig.GIT_SHA,
                buildType = BuildConfig.BUILD_TYPE,
                buildTimestampUtc = BuildConfig.BUILD_TIMESTAMP_UTC,
                nativeLoaded = native.loaded,
                nativeVersion = native.version,
                nativeSelfTestPassed = native.selfTestPassed,
            ),
        )
    }

    private fun PackageManager.graphicsSnapshot(): GraphicsSnapshot {
        val features = systemAvailableFeatures.orEmpty()
        val (vulkanLevel, vulkanVersion) = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            vulkanFeatureVersions(features)
        } else {
            null to null
        }
        return GraphicsSnapshot(
            vulkanHardwareLevel = vulkanLevel,
            vulkanHardwareVersion = vulkanVersion,
            vulkanVersionReadable = vulkanVersion?.toVulkanVersion(),
            openGlEsVersion = systemAvailableFeatures.orEmpty()
                .firstOrNull { it.name == null && it.reqGlEsVersion > 0 }
                ?.glEsVersion,
        )
    }

    @RequiresApi(Build.VERSION_CODES.N)
    private fun vulkanFeatureVersions(
        features: Array<out android.content.pm.FeatureInfo>,
    ): Pair<Int?, Int?> {
        val level = features.firstOrNull {
            it.name == PackageManager.FEATURE_VULKAN_HARDWARE_LEVEL
        }?.version?.takeIf { it > 0 }
        val version = features.firstOrNull {
            it.name == PackageManager.FEATURE_VULKAN_HARDWARE_VERSION
        }?.version?.takeIf { it > 0 }
        return level to version
    }

    private fun Int.toVulkanVersion(): String {
        val major = this ushr 22
        val minor = (this ushr 12) and 0x3ff
        val patch = this and 0xfff
        return "$major.$minor.$patch"
    }

    private fun String?.sanitizedBuildValue(maxLength: Int = 128): String = this
        .orEmpty()
        .filterNot(Char::isISOControl)
        .take(maxLength)
}
