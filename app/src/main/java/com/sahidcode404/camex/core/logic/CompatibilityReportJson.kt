package com.sahidcode404.camex.core.logic

import com.sahidcode404.camex.core.model.CompatibilityReport
import com.sahidcode404.camex.core.model.CameraRouteFailureReport
import com.sahidcode404.camex.core.model.CameraStartupTraceReport
import com.sahidcode404.camex.core.model.DiscoveryBackendFailureReport
import com.sahidcode404.camex.core.model.DiscoveryBackendReport
import com.sahidcode404.camex.core.model.FieldOfView
import com.sahidcode404.camex.core.model.FailureCountReport
import com.sahidcode404.camex.core.model.LensCapabilities
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

object CompatibilityReportJson {
    private val json = Json {
        prettyPrint = true
        encodeDefaults = true
        ignoreUnknownKeys = true
    }

    fun encode(report: CompatibilityReport): String = json.encodeToString(sanitize(report))

    fun decode(value: String): CompatibilityReport = json.decodeFromString(value)

    /** Broken HAL floating-point values are omitted so the export remains strict, portable JSON. */
    private fun sanitize(report: CompatibilityReport): CompatibilityReport = report.copy(
        startupTrace = report.startupTrace.sanitized(),
        javaDiscovery = report.javaDiscovery.sanitized(),
        ndkDiscovery = report.ndkDiscovery.sanitized(),
        deepDiscovery = report.deepDiscovery.sanitized(),
        canonicalTopology = report.canonicalTopology.copy(
            routeCount = report.canonicalTopology.routeCount.coerceAtLeast(0),
        ),
        cameras = report.cameras.map { camera ->
            camera.copy(
                routeFailure = camera.routeFailure?.sanitized(),
                metadataEvidence = camera.metadataEvidence?.copy(
                    rawSizes = camera.metadataEvidence.rawSizes.filter { it.isValid },
                    privatePreviewSizes = camera.metadataEvidence.privatePreviewSizes
                        .filter { it.isValid },
                    yuvPreviewSizes = camera.metadataEvidence.yuvPreviewSizes.filter { it.isValid },
                ),
                fieldOfView = camera.fieldOfView?.takeIf { it.isFiniteAndValid() },
                capabilities = camera.capabilities.sanitized(),
                maximumRawSize = camera.maximumRawSize?.takeIf { it.isValid },
                estimatedMaximumRawFps = camera.estimatedMaximumRawFps?.takeIf(::positiveFinite),
            )
        },
        discoveryFailures = report.discoveryFailures.map { failure ->
            failure.copy(detail = failure.detail.trim().take(MAX_DIAGNOSTIC_DETAIL_LENGTH))
        },
        trustState = report.trustState.map { trust ->
            trust.copy(failure = trust.failure?.sanitized())
        },
        failureReasons = report.failureReasons.map { summary ->
            summary.copy(count = summary.count.coerceAtLeast(0))
        },
    )

    private fun CameraStartupTraceReport.sanitized() = copy(
        offsetsNs = offsetsNs.filterValues { it >= 0L },
        cacheToLensesMs = cacheToLensesMs?.takeIf(::finiteNonNegative),
        appToCameraRequestMs = appToCameraRequestMs?.takeIf(::finiteNonNegative),
        appToFirstPreviewFrameMs = appToFirstPreviewFrameMs?.takeIf(::finiteNonNegative),
        advertisedScanMs = advertisedScanMs?.takeIf(::finiteNonNegative),
        deepAuxScanMs = deepAuxScanMs?.takeIf(::finiteNonNegative),
    )

    private fun DiscoveryBackendReport.sanitized() = copy(
        candidateCount = candidateCount.coerceAtLeast(0),
        durationMs = durationMs?.takeIf { it >= 0L },
        failureCount = failureCount.coerceAtLeast(0),
        failuresByReason = failuresByReason.map { it.sanitized() },
        failures = failures.map { it.sanitized() },
    )

    private fun FailureCountReport.sanitized() = copy(count = count.coerceAtLeast(0))

    private fun DiscoveryBackendFailureReport.sanitized() = copy(
        detail = detail?.trim()?.take(MAX_DIAGNOSTIC_DETAIL_LENGTH),
    )

    private fun CameraRouteFailureReport.sanitized() = copy(
        detail = detail?.trim()?.take(MAX_DIAGNOSTIC_DETAIL_LENGTH),
    )

    private fun LensCapabilities.sanitized(): LensCapabilities = copy(
        focalLengthsMm = focalLengthsMm?.filter(::positiveFinite)?.takeIf(List<Double>::isNotEmpty),
        sensorPhysicalSize = sensorPhysicalSize?.takeIf { it.isValid },
        apertures = apertures?.filter(::positiveFinite)?.takeIf(List<Double>::isNotEmpty),
        minimumFocusDistanceDiopters = minimumFocusDistanceDiopters
            ?.takeIf { it.isFinite() && it >= 0.0 },
    )

    private fun FieldOfView.isFiniteAndValid(): Boolean =
        horizontalDegrees.validAngle() && verticalDegrees.validAngle() &&
            diagonalDegrees.validAngle() && positiveFinite(focalLengthMm)

    private fun Double.validAngle(): Boolean = isFinite() && this > 0.0 && this < 180.0
    private fun positiveFinite(value: Double): Boolean = value.isFinite() && value > 0.0
    private fun finiteNonNegative(value: Double): Boolean = value.isFinite() && value >= 0.0

    private const val MAX_DIAGNOSTIC_DETAIL_LENGTH = 256
}
