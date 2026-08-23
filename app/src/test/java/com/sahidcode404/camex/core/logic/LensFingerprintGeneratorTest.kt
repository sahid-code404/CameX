package com.sahidcode404.camex.core.logic

import com.sahidcode404.camex.core.model.FingerprintFallbackContext
import com.sahidcode404.camex.core.model.FingerprintStrategy
import com.sahidcode404.camex.core.model.LensCapabilities
import com.sahidcode404.camex.core.model.LensIdentity
import com.sahidcode404.camex.core.model.StreamConfiguration
import com.sahidcode404.camex.core.model.StreamFormat
import com.sahidcode404.camex.core.model.Size2D
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LensFingerprintGeneratorTest {
    @Test
    fun stableFingerprintIsDeterministicAndDistinguishesHardwareIds() {
        val first = testLens("arbitrary-alpha")
        val second = first.copy(identity = LensIdentity(publicCameraId = "different-runtime-id"))

        val firstFingerprint = LensFingerprintGenerator.generate(first)
        val secondFingerprint = LensFingerprintGenerator.generate(second)

        assertEquals(FingerprintStrategy.STABLE_METADATA, firstFingerprint.strategy)
        assertNotEquals(firstFingerprint, secondFingerprint)
        assertTrue(firstFingerprint.value.matches(Regex("lm2_[0-9a-f]{64}")))
    }

    @Test
    fun listAndStreamOrderingDoesNotChangeFingerprint() {
        val base = testLens("route")
        val capabilities = base.capabilities
        val reversed = capabilities.copy(
            focalLengthsMm = listOf(8.0, 4.0),
            apertures = listOf(2.4, 1.8),
            streamConfigurations = capabilities.streamConfigurations.orEmpty() +
                StreamConfiguration(StreamFormat.RAW12, Size2D(2000, 1500)),
        )
        val reordered = reversed.copy(
            focalLengthsMm = reversed.focalLengthsMm!!.reversed(),
            apertures = reversed.apertures!!.reversed(),
            streamConfigurations = reversed.streamConfigurations!!.reversed(),
        )

        assertEquals(
            LensFingerprintGenerator.generate(base.copy(capabilities = reversed)),
            LensFingerprintGenerator.generate(base.copy(capabilities = reordered)),
        )
    }

    @Test
    fun opticalChangeChangesStableFingerprint() {
        val wide = LensFingerprintGenerator.generate(testLens("same", focalMm = 4.0))
        val tele = LensFingerprintGenerator.generate(testLens("same", focalMm = 8.0))

        assertNotEquals(wide, tele)
    }

    @Test
    fun sparseMetadataUsesDeviceScopedFallback() {
        val sparse = testLens("route").copy(capabilities = LensCapabilities())
        val context = FingerprintFallbackContext("build-a", "device-a", "model-a")

        val fingerprint = LensFingerprintGenerator.generate(sparse, context)

        assertEquals(FingerprintStrategy.DEVICE_SCOPED_FALLBACK, fingerprint.strategy)
        assertTrue(fingerprint.value.startsWith("lf2_"))
        assertEquals(fingerprint, LensFingerprintGenerator.generate(sparse, context))
    }

    @Test
    fun fallbackIsScopedByBothRoutingAndBuildEvidence() {
        val sparse = testLens("route-a").copy(capabilities = LensCapabilities())
        val original = LensFingerprintGenerator.generate(
            sparse,
            FingerprintFallbackContext("build-a", "device", "model"),
        )
        val changedRoute = LensFingerprintGenerator.generate(
            sparse.copy(identity = LensIdentity("route-b")),
            FingerprintFallbackContext("build-a", "device", "model"),
        )
        val changedBuild = LensFingerprintGenerator.generate(
            sparse,
            FingerprintFallbackContext("build-b", "device", "model"),
        )

        assertNotEquals(original, changedRoute)
        assertNotEquals(original, changedBuild)
    }
}
