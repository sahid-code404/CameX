package com.sahidcode404.camex.core.model

import kotlinx.serialization.Serializable

@Serializable
data class FieldOfView(
    val horizontalDegrees: Double,
    val verticalDegrees: Double,
    val diagonalDegrees: Double,
    val focalLengthMm: Double,
)

@Serializable
data class FingerprintFallbackContext(
    /** OS build fingerprint, not a hardware serial number. */
    val buildFingerprint: String? = null,
    val deviceCodename: String? = null,
    val model: String? = null,
)
