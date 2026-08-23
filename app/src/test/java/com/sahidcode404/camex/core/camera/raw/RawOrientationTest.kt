package com.sahidcode404.camex.core.camera.raw

import org.junit.Assert.assertEquals
import org.junit.Test

class RawOrientationTest {
    @Test
    fun rearPortraitRotationUsesSensorMinusDisplay() {
        val rotation = RawOrientation.requiredClockwiseRotationDegrees(
            sensorOrientationDegrees = 90,
            displayRotationDegrees = 0,
            frontFacing = false,
        )

        assertEquals(90, rotation)
        assertEquals(6, RawOrientation.exifOrientationForClockwiseRotation(rotation))
    }

    @Test
    fun rearLandscapeRotationCanResolveToUpright() {
        val rotation = RawOrientation.requiredClockwiseRotationDegrees(
            sensorOrientationDegrees = 90,
            displayRotationDegrees = 90,
            frontFacing = false,
        )

        assertEquals(0, rotation)
        assertEquals(1, RawOrientation.exifOrientationForClockwiseRotation(rotation))
    }

    @Test
    fun frontPortraitRotationUsesFacingAwareFormulaWithoutMirrorTag() {
        val rotation = RawOrientation.requiredClockwiseRotationDegrees(
            sensorOrientationDegrees = 270,
            displayRotationDegrees = 0,
            frontFacing = true,
        )

        assertEquals(270, rotation)
        assertEquals(8, RawOrientation.exifOrientationForClockwiseRotation(rotation))
    }

    @Test
    fun frontLandscapeRotationCanResolveToUpright() {
        val rotation = RawOrientation.requiredClockwiseRotationDegrees(
            sensorOrientationDegrees = 270,
            displayRotationDegrees = 90,
            frontFacing = true,
        )

        assertEquals(0, rotation)
        assertEquals(1, RawOrientation.exifOrientationForClockwiseRotation(rotation))
    }

    @Test
    fun halfTurnMapsToExifRotate180() {
        assertEquals(3, RawOrientation.exifOrientationForClockwiseRotation(180))
    }

    @Test
    fun valuesAreNormalizedBeforeOrientationMapping() {
        assertEquals(
            270,
            RawOrientation.requiredClockwiseRotationDegrees(
                sensorOrientationDegrees = -90,
                displayRotationDegrees = 0,
                frontFacing = false,
            ),
        )
        assertEquals(6, RawOrientation.exifOrientationForClockwiseRotation(450))
    }
}
