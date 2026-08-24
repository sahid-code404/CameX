package com.sahidcode404.camex.core.camera.raw

/** Framework-neutral RAW/DNG orientation math. */
internal object RawOrientation {
    /**
     * Clockwise rotation needed to display the sensor-native RAW frame upright for the current
     * display rotation. Front RAW is not mirrored, so only the facing-aware sensor/display formula
     * is applied.
     */
    fun requiredClockwiseRotationDegrees(
        sensorOrientationDegrees: Int,
        displayRotationDegrees: Int,
        frontFacing: Boolean,
    ): Int {
        val sensor = Math.floorMod(sensorOrientationDegrees, 360)
        val display = Math.floorMod(displayRotationDegrees, 360)
        return if (frontFacing) {
            Math.floorMod(sensor + display, 360)
        } else {
            Math.floorMod(sensor - display, 360)
        }
    }

    /** EXIF/TIFF Orientation tag value expected by DngCreator.setOrientation. */
    fun exifOrientationForClockwiseRotation(rotationDegrees: Int): Int = when (
        Math.floorMod(rotationDegrees, 360)
    ) {
        0 -> EXIF_NORMAL
        90 -> EXIF_ROTATE_90
        180 -> EXIF_ROTATE_180
        270 -> EXIF_ROTATE_270
        else -> EXIF_NORMAL
    }

    private const val EXIF_NORMAL = 1
    private const val EXIF_ROTATE_90 = 6
    private const val EXIF_ROTATE_180 = 3
    private const val EXIF_ROTATE_270 = 8
}
