package com.sahidcode404.camex.core.logic

import com.sahidcode404.camex.core.model.FingerprintFallbackContext
import com.sahidcode404.camex.core.model.FingerprintStrategy
import com.sahidcode404.camex.core.model.LensDescriptor
import com.sahidcode404.camex.core.model.LensFingerprint
import com.sahidcode404.camex.core.model.Size2D
import java.math.BigDecimal
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

/**
 * Creates a deterministic, versioned identity from normalized observable metadata.
 *
 * The stable path requires valid optics plus a hardware-route identity (physical ID when present,
 * otherwise the public ID). This intentionally keeps two different IDs with identical metadata
 * distinct: false duplicate removal is worse than losing a preference migration after a HAL
 * renumbering. Sparse fallbacks also include non-secret build/device context. Raw canonical input
 * is never retained in [LensFingerprint].
 */
object LensFingerprintGenerator {
    private const val SCHEMA = "lens-fingerprint-v2"

    fun generate(
        lens: LensDescriptor,
        fallbackContext: FingerprintFallbackContext = FingerprintFallbackContext(),
    ): LensFingerprint {
        val stableParts = stableParts(lens)
        val hasFocalLength = lens.capabilities.focalLengthsMm.orEmpty().any(::isPositiveFinite)
        val hasSensorGeometry = lens.capabilities.sensorPhysicalSize?.isValid == true ||
            lens.capabilities.pixelArraySize?.isValid == true ||
            lens.capabilities.activeArray?.isValid == true
        val strategy = if (hasFocalLength && hasSensorGeometry) {
            FingerprintStrategy.STABLE_METADATA
        } else {
            FingerprintStrategy.DEVICE_SCOPED_FALLBACK
        }

        val canonical = buildString {
            appendPart("schema", SCHEMA)
            appendPart("strategy", strategy.name)
            appendPart(
                "hardwareCameraId",
                lens.identity.physicalCameraId ?: lens.identity.publicCameraId,
            )
            stableParts.forEach { (name, value) -> appendPart(name, value) }
            if (strategy == FingerprintStrategy.DEVICE_SCOPED_FALLBACK) {
                appendPart("publicCameraId", lens.identity.publicCameraId)
                appendPart("physicalCameraId", lens.identity.physicalCameraId)
                appendPart("logicalParentCameraId", lens.identity.logicalParentCameraId)
                appendPart("buildFingerprint", fallbackContext.buildFingerprint)
                appendPart("deviceCodename", fallbackContext.deviceCodename)
                appendPart("model", fallbackContext.model)
            }
        }
        val prefix = if (strategy == FingerprintStrategy.STABLE_METADATA) "lm2_" else "lf2_"
        return LensFingerprint(prefix + sha256(canonical), strategy)
    }

    private fun stableParts(lens: LensDescriptor): List<Pair<String, String?>> {
        val capabilities = lens.capabilities
        val rawSizes = capabilities.portableRawConfigurations
            .map { it.size }
            .filter(Size2D::isValid)
            .distinct()
            .sortedWith(compareBy<Size2D>({ it.width }, { it.height }))
            .joinToString(",") { "${it.width}x${it.height}" }
            .ifEmpty { null }
        return listOf(
            "facing" to lens.facing.name,
            "nodeKind" to lens.identity.nodeKind.name,
            "sensorPhysicalSizeMm" to capabilities.sensorPhysicalSize
                ?.takeIf { it.isValid }
                ?.let { "${number(it.widthMm)}x${number(it.heightMm)}" },
            "pixelArray" to capabilities.pixelArraySize?.validText(),
            "activeArray" to capabilities.activeArray
                ?.takeIf { it.isValid }
                ?.let { "${it.left},${it.top},${it.right},${it.bottom}" },
            "maximumResolutionArray" to capabilities.maximumResolutionArray
                ?.takeIf { it.isValid }
                ?.let { "${it.left},${it.top},${it.right},${it.bottom}" },
            "focalLengthsMm" to capabilities.focalLengthsMm.normalizedNumbers(),
            "orientation" to capabilities.sensorOrientationDegrees
                ?.let { Math.floorMod(it, 360).toString() },
            "apertures" to capabilities.apertures.normalizedNumbers(),
            "rawSizes" to rawSizes,
            "cfa" to capabilities.colorFilterArrangement?.name,
            "rawAccess" to capabilities.rawAccess.name,
        )
    }

    private fun StringBuilder.appendPart(name: String, value: String?) {
        val normalized = value ?: "?"
        append(name.length).append(':').append(name)
        append('=').append(normalized.length).append(':').append(normalized).append(';')
    }

    private fun Size2D.validText(): String? = takeIf { isValid }?.let { "${it.width}x${it.height}" }

    private fun List<Double>?.normalizedNumbers(): String? = this.orEmpty()
        .filter(::isPositiveFinite)
        .map(::number)
        .distinct()
        .sorted()
        .joinToString(",")
        .ifEmpty { null }

    private fun number(value: Double): String =
        BigDecimal.valueOf(value).stripTrailingZeros().toPlainString()

    private fun isPositiveFinite(value: Double): Boolean = value.isFinite() && value > 0.0

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(StandardCharsets.UTF_8))
        .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
}
