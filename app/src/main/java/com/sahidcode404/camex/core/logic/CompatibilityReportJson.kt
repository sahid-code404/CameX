package com.sahidcode404.camex.core.logic

import com.sahidcode404.camex.core.model.CompatibilityReport
import com.sahidcode404.camex.core.model.FieldOfView
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
        cameras = report.cameras.map { camera ->
            camera.copy(
                fieldOfView = camera.fieldOfView?.takeIf { it.isFiniteAndValid() },
                capabilities = camera.capabilities.sanitized(),
                maximumRawSize = camera.maximumRawSize?.takeIf { it.isValid },
                estimatedMaximumRawFps = camera.estimatedMaximumRawFps?.takeIf(::positiveFinite),
            )
        },
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
}
