package com.sahidcode404.camex.core.logic

import com.sahidcode404.camex.core.model.FieldOfView
import com.sahidcode404.camex.core.model.LensCapabilities
import java.util.Locale
import kotlin.math.atan
import kotlin.math.hypot
import kotlin.math.round
import kotlin.math.tan

object LensMath {
    /**
     * Calculates optical FOV, accounting for an active-array crop when both arrays are valid.
     * For a variable-focal-length lens, the shortest reported focal length represents its widest
     * native view. Invalid or incomplete metadata returns null instead of inventing an angle.
     */
    fun fieldOfView(capabilities: LensCapabilities): FieldOfView? {
        val sensor = capabilities.sensorPhysicalSize?.takeIf { it.isValid } ?: return null
        val focalLength = capabilities.focalLengthsMm.orEmpty()
            .filter { it.isFinite() && it > 0.0 }
            .minOrNull() ?: return null

        var sensorWidth = sensor.widthMm
        var sensorHeight = sensor.heightMm
        val pixelArray = capabilities.pixelArraySize
        val activeArray = capabilities.activeArray
        if (pixelArray?.isValid == true && activeArray?.isValid == true &&
            activeArray.width <= pixelArray.width && activeArray.height <= pixelArray.height
        ) {
            sensorWidth *= activeArray.width.toDouble() / pixelArray.width
            sensorHeight *= activeArray.height.toDouble() / pixelArray.height
        }
        if (sensorWidth <= 0.0 || sensorHeight <= 0.0) return null

        fun angle(dimension: Double): Double = Math.toDegrees(2.0 * atan(dimension / (2.0 * focalLength)))
        return FieldOfView(
            horizontalDegrees = angle(sensorWidth),
            verticalDegrees = angle(sensorHeight),
            diagonalDegrees = angle(hypot(sensorWidth, sensorHeight)),
            focalLengthMm = focalLength,
        )
    }

    /** Returns optical zoom relative to [reference]; narrower FOV produces a value above 1x. */
    fun relativeZoom(reference: FieldOfView?, lens: FieldOfView?): Double? {
        val referenceAngle = reference?.diagonalDegrees?.takeIf(::validAngle) ?: return null
        val lensAngle = lens?.diagonalDegrees?.takeIf(::validAngle) ?: return null
        val ratio = tan(Math.toRadians(referenceAngle) / 2.0) /
            tan(Math.toRadians(lensAngle) / 2.0)
        return ratio.takeIf { it.isFinite() && it > 0.0 }
    }

    fun relativeZoomLabel(reference: FieldOfView?, lens: FieldOfView?): String? =
        relativeZoom(reference, lens)?.let(::formatZoom)

    fun formatZoom(zoom: Double): String? {
        if (!zoom.isFinite() || zoom <= 0.0) return null
        val decimals = if (zoom < 1.0) 2 else 1
        val scale = if (decimals == 2) 100.0 else 10.0
        val rounded = round(zoom * scale) / scale
        val formatted = String.format(Locale.ROOT, if (decimals == 2) "%.2f" else "%.1f", rounded)
            .trimEnd('0')
            .trimEnd('.')
        return "$formatted×"
    }

    private fun validAngle(value: Double): Boolean = value.isFinite() && value > 0.0 && value < 180.0
}
