package com.sahidcode404.camex.core.camera

import kotlin.math.abs
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
    fun portraitTransformUndoesStretchAndCenterCropsWithoutDoubleSensorRotation() {
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
    fun renderedPreviewKeepsBufferAspectAcrossLensStyleOrientations() {
        data class Case(
            val sensor: Int,
            val display: Int,
            val front: Boolean,
            val bufferWidth: Int,
            val bufferHeight: Int,
        )

        val viewWidth = 1080
        val viewHeight = 2400
        listOf(
            Case(90, 0, false, 1920, 1080),
            Case(270, 0, true, 1920, 1080),
            Case(90, 90, false, 1440, 1080),
            Case(0, 0, false, 1280, 720),
        ).forEach { case ->
            val transform = requireNotNull(
                TexturePreviewTransform.calculate(
                    viewWidth,
                    viewHeight,
                    case.bufferWidth,
                    case.bufferHeight,
                    case.sensor,
                    case.display,
                    case.front,
                    mirrorHorizontally = false,
                ),
            )
            val relative = TexturePreviewTransform.relativeRotationDegrees(
                case.sensor,
                case.display,
                case.front,
            )
            val swapped = relative % 180 != 0
            val displayedBufferWidth = if (swapped) case.bufferHeight else case.bufferWidth
            val displayedBufferHeight = if (swapped) case.bufferWidth else case.bufferHeight
            val renderedAspect =
                (viewWidth * transform.scaleX) / (viewHeight * transform.scaleY)
            val expectedAspect = displayedBufferWidth.toFloat() / displayedBufferHeight
            assertTrue(abs(renderedAspect - expectedAspect) < 0.0001f)
            assertTrue(transform.scaleX >= 1f)
            assertTrue(transform.scaleY >= 1f)
        }
    }

    @Test
    fun frontMirrorIsRetainedAndInvalidGeometryIsRejected() {
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
