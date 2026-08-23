package com.sahidcode404.camex.core.camera.discovery

import com.sahidcode404.camex.core.model.CapabilitySupport
import com.sahidcode404.camex.core.model.FingerprintFallbackContext
import com.sahidcode404.camex.core.model.HardwareLevel
import com.sahidcode404.camex.core.model.LensFacing
import com.sahidcode404.camex.core.model.LensIdentity
import com.sahidcode404.camex.core.model.LensUsability
import com.sahidcode404.camex.core.model.PhysicalSize
import com.sahidcode404.camex.core.model.RawAccess
import com.sahidcode404.camex.core.model.Size2D
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class JavaMinimalCameraMetadataTest {
    private val fallback = FingerprintFallbackContext("build", "device", "model")

    @Test
    fun incompleteOpticalAuxWithPrivatePreviewRemainsSelectableCandidate() {
        val metadata = candidate(
            backwardCompatible = CapabilitySupport.UNKNOWN,
            focalLengthsMm = listOf(4.72),
        )

        assertTrue(metadata.crediblePhotographicCandidate)
        assertEquals(
            LensUsability.PREVIEW_ONLY,
            metadata.toLensDescriptor(0, fallback).usability,
        )
    }

    @Test
    fun rawStreamIsEvidenceEvenWhenCapabilityBitIsMissing() {
        val metadata = candidate(
            rawCapabilityAdvertised = false,
            rawStreamActuallyDeclared = true,
        )

        val lens = metadata.toLensDescriptor(0, fallback)

        assertEquals(CapabilitySupport.SUPPORTED, lens.capabilities.flags.raw)
        assertEquals(RawAccess.DIRECT, lens.capabilities.rawAccess)
        assertTrue(metadata.crediblePhotographicCandidate)
    }

    @Test
    fun physicalCandidateKeepsLogicalParentRouting() {
        val metadata = candidate(
            identity = LensIdentity(
                publicCameraId = "logical",
                physicalCameraId = "aux",
                logicalParentCameraId = "logical",
            ),
            rawStreamActuallyDeclared = true,
        )

        val lens = metadata.toLensDescriptor(0, fallback)

        assertEquals("logical", lens.identity.openCameraId)
        assertEquals("aux", lens.identity.streamPhysicalCameraId)
        assertEquals(RawAccess.PHYSICAL_STREAM, lens.capabilities.rawAccess)
    }

    @Test
    fun depthOnlyAndYuvOnlyRoutesAreNotNormalTextureCandidates() {
        val depth = candidate(depthOnly = true)
        val yuvOnly = candidate(
            privatePreviewSizes = emptyList(),
            yuvStreamActuallyDeclared = true,
            focalLengthsMm = listOf(3.2),
        )

        assertFalse(depth.crediblePhotographicCandidate)
        assertEquals(
            LensUsability.DEPTH_AUXILIARY,
            depth.toLensDescriptor(0, fallback).usability,
        )
        assertTrue(yuvOnly.crediblePhotographicCandidate)
        assertEquals(
            LensUsability.PHOTOGRAPHIC_CANDIDATE,
            yuvOnly.toLensDescriptor(0, fallback).usability,
        )
    }

    private fun candidate(
        identity: LensIdentity = LensIdentity("route"),
        backwardCompatible: CapabilitySupport = CapabilitySupport.SUPPORTED,
        focalLengthsMm: List<Double>? = null,
        rawCapabilityAdvertised: Boolean? = null,
        rawStreamActuallyDeclared: Boolean = false,
        privatePreviewSizes: List<Size2D> = listOf(Size2D(1280, 720)),
        yuvStreamActuallyDeclared: Boolean = false,
        depthOnly: Boolean = false,
    ) = JavaMinimalCameraMetadata(
        identity = identity,
        facing = LensFacing.BACK,
        focalLengthsMm = focalLengthsMm,
        sensorPhysicalSize = PhysicalSize(5.6, 4.2),
        activeArray = null,
        pixelArraySize = null,
        sensorOrientationDegrees = 90,
        hardwareLevel = HardwareLevel.LIMITED,
        backwardCompatible = backwardCompatible,
        rawCapabilityAdvertised = rawCapabilityAdvertised,
        rawStreamActuallyDeclared = rawStreamActuallyDeclared,
        privatePreviewSizes = privatePreviewSizes,
        yuvStreamActuallyDeclared = yuvStreamActuallyDeclared,
        depthOnly = depthOnly,
        systemOnly = false,
    )
}
