package com.sahidcode404.camex.core.model

import kotlinx.serialization.Serializable

/** Integer dimensions reported by a camera implementation. Invalid values are retained for diagnostics. */
@Serializable
data class Size2D(
    val width: Int,
    val height: Int,
) {
    val isValid: Boolean get() = width > 0 && height > 0
    val area: Long? get() = if (isValid) width.toLong() * height.toLong() else null
}

/** Sensor dimensions in millimetres. */
@Serializable
data class PhysicalSize(
    val widthMm: Double,
    val heightMm: Double,
) {
    val isValid: Boolean
        get() = widthMm.isFinite() && heightMm.isFinite() && widthMm > 0.0 && heightMm > 0.0
}

@Serializable
data class SensorRect(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
) {
    val width: Int get() = right - left
    val height: Int get() = bottom - top
    val isValid: Boolean get() = width > 0 && height > 0
    val size: Size2D get() = Size2D(width, height)
}

@Serializable
data class IntValueRange(
    val min: Int,
    val max: Int,
) {
    val isValid: Boolean get() = min <= max
}

@Serializable
data class LongValueRange(
    val min: Long,
    val max: Long,
) {
    val isValid: Boolean get() = min <= max
}

@Serializable
data class FpsRange(
    val min: Int,
    val max: Int,
) {
    val isValid: Boolean get() = min >= 0 && min <= max
}
