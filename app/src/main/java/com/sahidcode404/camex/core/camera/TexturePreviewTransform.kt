package com.sahidcode404.camex.core.camera

import android.graphics.Matrix
import android.view.TextureView
import kotlin.math.max

/**
 * TextureView already compensates for sensor mounting orientation. The application transform
 * removes TextureView's non-uniform fill, center-crops uniformly, and compensates display rotation.
 *
 * Keep this math aligned with the hardware-validated Phase 1 preview behaviour. Lens switches may
 * replace the producer buffer geometry, so callers should reset the previous matrix before binding
 * a new buffer and then apply this transform using the new stream's exact dimensions.
 */
data class PreviewTransform(
    val scaleX: Float,
    val scaleY: Float,
    val clockwiseDisplayCompensationDegrees: Int,
    val mirrorHorizontally: Boolean,
    val pivotX: Float,
    val pivotY: Float,
) {
    fun toMatrix(): Matrix = Matrix().apply {
        setScale(scaleX, scaleY, pivotX, pivotY)
        postRotate(clockwiseDisplayCompensationDegrees.toFloat(), pivotX, pivotY)
        if (mirrorHorizontally) postScale(-1f, 1f, pivotX, pivotY)
    }
}

object TexturePreviewTransform {
    /** Android's documented Camera2 sensor-to-display relative rotation formula. */
    fun relativeRotationDegrees(
        sensorOrientationDegrees: Int,
        displayRotationDegrees: Int,
        frontFacing: Boolean,
    ): Int {
        val sign = if (frontFacing) 1 else -1
        return Math.floorMod(sensorOrientationDegrees - displayRotationDegrees * sign, 360)
    }

    /**
     * Hardware-validated Phase 1 resizable-TextureView transform.
     *
     * Relative rotation determines axis swapping. Only display rotation is applied here because the
     * TextureView producer transform already accounts for the sensor's mounting orientation.
     */
    fun calculate(
        viewWidth: Int,
        viewHeight: Int,
        bufferWidth: Int,
        bufferHeight: Int,
        sensorOrientationDegrees: Int,
        displayRotationDegrees: Int,
        frontFacing: Boolean,
        mirrorHorizontally: Boolean,
    ): PreviewTransform? {
        if (viewWidth <= 0 || viewHeight <= 0 || bufferWidth <= 0 || bufferHeight <= 0) return null
        val sensorOrientation = Math.floorMod(sensorOrientationDegrees, 360)
        val displayRotation = Math.floorMod(displayRotationDegrees, 360)
        if (sensorOrientation % 90 != 0 || displayRotation % 90 != 0) return null

        val relativeRotation = relativeRotationDegrees(
            sensorOrientation,
            displayRotation,
            frontFacing,
        )
        val axesSwapped = relativeRotation % 180 != 0
        val sensorNaturalAxes = sensorOrientation % 180 == 0

        val undoScaleX = when {
            sensorNaturalAxes && !axesSwapped -> viewWidth.toFloat() / bufferHeight
            sensorNaturalAxes -> viewWidth.toFloat() / bufferWidth
            axesSwapped -> viewWidth.toFloat() / bufferHeight
            else -> viewWidth.toFloat() / bufferWidth
        }
        val undoScaleY = when {
            sensorNaturalAxes && !axesSwapped -> viewHeight.toFloat() / bufferWidth
            sensorNaturalAxes -> viewHeight.toFloat() / bufferHeight
            axesSwapped -> viewHeight.toFloat() / bufferWidth
            else -> viewHeight.toFloat() / bufferHeight
        }
        if (undoScaleX <= 0f || undoScaleY <= 0f) return null
        val centerCropScale = max(undoScaleX, undoScaleY)
        val scaleX: Float
        val scaleY: Float
        if (axesSwapped) {
            scaleX = centerCropScale / undoScaleX
            scaleY = centerCropScale / undoScaleY
        } else {
            scaleX = viewHeight.toFloat() / viewWidth / undoScaleY * centerCropScale
            scaleY = viewWidth.toFloat() / viewHeight / undoScaleX * centerCropScale
        }
        return PreviewTransform(
            scaleX = scaleX,
            scaleY = scaleY,
            clockwiseDisplayCompensationDegrees = -displayRotation,
            mirrorHorizontally = mirrorHorizontally,
            pivotX = viewWidth / 2f,
            pivotY = viewHeight / 2f,
        )
    }

    fun reset(textureView: TextureView) {
        textureView.setTransform(Matrix())
    }

    fun apply(
        textureView: TextureView,
        bufferWidth: Int,
        bufferHeight: Int,
        sensorOrientationDegrees: Int,
        displayRotationDegrees: Int,
        frontFacing: Boolean,
        mirrorHorizontally: Boolean,
    ) {
        // Saved RAW/DNG frames are sensor-oriented and not selfie-mirrored. Keep the live front
        // preview unmirrored so preview geometry agrees with the saved image.
        val effectiveMirror = mirrorHorizontally && !frontFacing
        val transform = calculate(
            viewWidth = textureView.width,
            viewHeight = textureView.height,
            bufferWidth = bufferWidth,
            bufferHeight = bufferHeight,
            sensorOrientationDegrees = sensorOrientationDegrees,
            displayRotationDegrees = displayRotationDegrees,
            frontFacing = frontFacing,
            mirrorHorizontally = effectiveMirror,
        ) ?: run {
            reset(textureView)
            return
        }
        textureView.setTransform(transform.toMatrix())
    }
}
