package com.sahidcode404.camex.core.diagnostics

import android.content.Context
import com.sahidcode404.camex.core.camera.CameraDiscoverySnapshot
import com.sahidcode404.camex.core.logic.CompatibilityReportJson
import com.sahidcode404.camex.core.logic.LensMath
import com.sahidcode404.camex.core.model.AndroidReport
import com.sahidcode404.camex.core.model.AppReport
import com.sahidcode404.camex.core.model.AppliedQuirkReport
import com.sahidcode404.camex.core.model.CameraCompatibilityEntry
import com.sahidcode404.camex.core.model.CompatibilityReport
import com.sahidcode404.camex.core.model.DeviceReport
import com.sahidcode404.camex.core.model.DiscoveryFailureReport
import com.sahidcode404.camex.core.model.GraphicsReport
import com.sahidcode404.camex.core.model.LensDescriptor
import com.sahidcode404.camex.core.model.LogicalRelationshipReport
import com.sahidcode404.camex.core.model.ProbeReportEntry
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

object CompatibilityReportFactory {
    fun create(
        context: Context,
        discovery: CameraDiscoverySnapshot,
        lenses: List<LensDescriptor> = discovery.lenses,
        quirks: List<AppliedQuirkReport> = emptyList(),
        generatedAtUtc: String = nowUtc(),
    ): CompatibilityReport {
        val platform = PlatformDiagnostics.collect(context.applicationContext)
        return CompatibilityReport(
            generatedAtUtc = generatedAtUtc,
            device = DeviceReport(
                manufacturer = platform.device.manufacturer,
                brand = platform.device.brand,
                model = platform.device.model,
                deviceCodename = platform.device.device,
                product = platform.device.product,
                buildFingerprint = platform.device.buildFingerprint,
            ),
            android = AndroidReport(
                sdkInt = platform.android.sdkInt,
                release = platform.android.release,
                securityPatch = platform.android.securityPatch,
            ),
            graphics = GraphicsReport(
                vulkanApiVersion = platform.graphics.vulkanVersionReadable,
                vulkanHardwareLevel = platform.graphics.vulkanHardwareLevel,
                vulkanHardwareVersion = platform.graphics.vulkanHardwareVersion,
                openGlEsVersion = platform.graphics.openGlEsVersion,
            ),
            cameras = lenses.map { lens ->
                CameraCompatibilityEntry(
                    identity = lens.identity,
                    fingerprint = lens.fingerprint,
                    facing = lens.facing,
                    usability = lens.usability,
                    category = lens.category,
                    fieldOfView = LensMath.fieldOfView(lens.capabilities),
                    capabilities = lens.capabilities,
                    maximumRawSize = lens.capabilities.maximumRawSize,
                    estimatedMaximumRawFps = lens.capabilities.estimatedMaximumRawFps,
                )
            },
            discoveryFailures = discovery.failures.map { failure ->
                DiscoveryFailureReport(
                    publicCameraId = failure.publicCameraId,
                    physicalCameraId = failure.physicalCameraId,
                    kind = failure.kind.name,
                    detail = failure.detail.take(256),
                )
            },
            logicalRelationships = discovery.logicalRelationships.map { relationship ->
                LogicalRelationshipReport(
                    logicalCameraId = relationship.logicalCameraId,
                    physicalCameraIds = relationship.physicalCameraIds,
                )
            },
            probeResults = lenses.mapNotNull { lens ->
                lens.probeResult?.let { result ->
                    ProbeReportEntry(
                        routingKey = lens.identity.routingKey,
                        fingerprint = lens.fingerprint,
                        result = result,
                    )
                }
            },
            quirks = quirks,
            app = AppReport(
                applicationId = platform.app.applicationId,
                versionName = platform.app.versionName,
                versionCode = platform.app.versionCode.toLong(),
                buildType = platform.app.buildType,
                buildTimestampUtc = platform.app.buildTimestampUtc,
                gitSha = platform.app.gitSha,
                nativeLoaded = platform.app.nativeLoaded,
                nativeVersion = platform.app.nativeVersion,
                nativeSelfTestPassed = platform.app.nativeSelfTestPassed,
            ),
        )
    }

    fun encode(
        context: Context,
        discovery: CameraDiscoverySnapshot,
        lenses: List<LensDescriptor> = discovery.lenses,
        quirks: List<AppliedQuirkReport> = emptyList(),
    ): String = CompatibilityReportJson.encode(create(context, discovery, lenses, quirks))

    private fun nowUtc(): String = SimpleDateFormat(
        "yyyy-MM-dd'T'HH:mm:ss'Z'",
        Locale.ROOT,
    ).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }.format(Date())
}
