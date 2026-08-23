package com.sahidcode404.camex.core.logic

import com.sahidcode404.camex.core.model.CapabilityFlags
import com.sahidcode404.camex.core.model.CapabilitySupport
import com.sahidcode404.camex.core.model.FieldOfView
import com.sahidcode404.camex.core.model.LensCapabilities
import com.sahidcode404.camex.core.model.LensCategory
import com.sahidcode404.camex.core.model.LensFacing
import com.sahidcode404.camex.core.model.LensUsability
import com.sahidcode404.camex.core.model.PhysicalSize
import com.sahidcode404.camex.core.model.SensorRect
import com.sahidcode404.camex.core.model.Size2D
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LensMathAndClassifierTest {
    @Test
    fun calculatesKnownFullFrameFov() {
        val result = LensMath.fieldOfView(
            LensCapabilities(
                focalLengthsMm = listOf(50.0),
                sensorPhysicalSize = PhysicalSize(36.0, 24.0),
            ),
        )!!

        assertEquals(39.598, result.horizontalDegrees, 0.001)
        assertEquals(26.991, result.verticalDegrees, 0.001)
        assertEquals(46.793, result.diagonalDegrees, 0.001)
    }

    @Test
    fun activeArrayCropReducesFovAndShortestFocalWins() {
        val uncropped = LensMath.fieldOfView(
            LensCapabilities(
                focalLengthsMm = listOf(8.0, 4.0),
                sensorPhysicalSize = PhysicalSize(8.0, 6.0),
            ),
        )!!
        val cropped = LensMath.fieldOfView(
            LensCapabilities(
                focalLengthsMm = listOf(8.0, 4.0),
                sensorPhysicalSize = PhysicalSize(8.0, 6.0),
                pixelArraySize = Size2D(4000, 3000),
                activeArray = SensorRect(1000, 750, 3000, 2250),
            ),
        )!!

        assertEquals(4.0, cropped.focalLengthMm, 0.0)
        assertTrue(cropped.diagonalDegrees < uncropped.diagonalDegrees)
    }

    @Test
    fun invalidOrMissingOpticsReturnUnknown() {
        assertNull(LensMath.fieldOfView(LensCapabilities()))
        assertNull(
            LensMath.fieldOfView(
                LensCapabilities(
                    focalLengthsMm = listOf(0.0, Double.NaN),
                    sensorPhysicalSize = PhysicalSize(5.0, 4.0),
                ),
            ),
        )
    }

    @Test
    fun relativeZoomUsesOpticalAngle() {
        val reference = LensMath.fieldOfView(testCapabilities(focalMm = 4.0))
        val tele = LensMath.fieldOfView(testCapabilities(focalMm = 8.0))
        val ultra = LensMath.fieldOfView(testCapabilities(focalMm = 2.0))

        assertEquals(2.0, LensMath.relativeZoom(reference, tele)!!, 0.0001)
        assertEquals(0.5, LensMath.relativeZoom(reference, ultra)!!, 0.0001)
        assertEquals("2×", LensMath.relativeZoomLabel(reference, tele))
        assertEquals("0.5×", LensMath.relativeZoomLabel(reference, ultra))
        assertNull(LensMath.relativeZoom(null, tele))
    }

    @Test
    fun classifiesFovBandsAndDoesNotInventMissingClass() {
        val lens = testLens("any")
        fun fov(diagonal: Double) = FieldOfView(diagonal, diagonal, diagonal, 4.0)

        assertEquals(LensCategory.PHOTOGRAPHIC_ULTRAWIDE, LensClassifier.classify(lens, fov(90.0)))
        assertEquals(LensCategory.PHOTOGRAPHIC_WIDE, LensClassifier.classify(lens, fov(55.0)))
        assertEquals(LensCategory.PHOTOGRAPHIC_TELEPHOTO, LensClassifier.classify(lens, fov(25.0)))
        assertEquals(LensCategory.PHOTOGRAPHIC_SUPER_TELEPHOTO, LensClassifier.classify(lens, fov(24.99)))
        assertEquals(
            LensCategory.PHOTOGRAPHIC_UNKNOWN,
            LensClassifier.classify(lens.copy(capabilities = LensCapabilities()), null),
        )
    }

    @Test
    fun facingAndAuxiliaryEvidenceOverrideFov() {
        val front = testLens("front", facing = LensFacing.FRONT)
        assertEquals(LensCategory.PHOTOGRAPHIC_UNKNOWN, LensClassifier.classify(front, null))

        val depth = testLens("depth").copy(
            usability = LensUsability.DEPTH_AUXILIARY,
            capabilities = LensCapabilities(
                flags = CapabilityFlags(
                    depthOutput = CapabilitySupport.SUPPORTED,
                    backwardCompatible = CapabilitySupport.UNSUPPORTED,
                ),
            ),
        )
        assertEquals(LensCategory.NON_PHOTO_DEPTH, LensClassifier.classify(depth, null))
    }
}
