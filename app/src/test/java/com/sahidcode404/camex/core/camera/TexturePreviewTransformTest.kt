package com.sahidcode404.camex.core.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TexturePreviewTransformTest {
    @Test
    fun relativeRotationUsesFacingAwarePlatformFormula() {
        assertEquals(
            90,
            TexturePreviewTransform.relativeRotationDegrees(90, 0, frontFacing = false),
        )
        assertEquals(
            270,
            TexturePreviewTransform.relativeRotationDegrees(270, 0, frontFacing = true),
        )
        assertEquals(
            180,
            TexturePreviewTransform.relativeRotationDegrees(90, 90, frontFacing = false),
        )
        assertEquals(
            180,
            TexturePreviewTransform.relativeRotationDegrees(270, 90, frontFacing = true),
        )
    }

    @Test
    fun portraitTransformMatchesHardwareValidatedPhaseOneGeometry() {
        val transform = requireNotNull(
            TexturePreviewTransform.calculate(
                viewWidth = 1080,
                viewHeight = 2400,
                bufferWidth = 1920,
                bufferHeight = 1080,
                sensorOrientationDegrees = 90,
                displayRotationDegrees = 0,
                frontFacing = false,
                mirrorHorizontally = false,
            ),
        )

        assertEquals(1.25f, transform.scaleX, 0.0001f)
        assertEquals(1f, transform.scaleY, 0.0001f)
        assertEquals(0, transform.clockwiseDisplayCompensationDegrees)
        assertFalse(transform.mirrorHorizontally)
    }

    @Test
    fun calculationPreservesRequestedMirrorAndRejectsInvalidGeometry() {
        val front = TexturePreviewTransform.calculate(
            1080,
            2400,
            1920,
            1080,
            270,
            0,
            frontFacing = true,
            mirrorHorizontally = true,
        )

        assertNotNull(front)
        assertTrue(front?.mirrorHorizontally == true)
        assertNull(TexturePreviewTransform.calculate(0, 10, 10, 10, 0, 0, false, false))
        assertNull(TexturePreviewTransform.calculate(10, 10, 10, 10, 45, 0, false, false))
    }
}
