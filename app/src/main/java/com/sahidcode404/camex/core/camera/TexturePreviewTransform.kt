package com.sahidcode404.camex.core.camera

import android.graphics.Matrix
import android.view.TextureView
import kotlin.math.max

/**
 * TextureView already compensates for sensor mounting orientation. The application transform
 * removes TextureView's non-uniform fill, center-crops uniformly, and compensates display rotation.
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
     * Corrects TextureView's default non-uniform fill into one uniform center-crop.
     *
     * The old transform had several sensor-orientation-specific scale branches. During lens/facing
     * switches that could leave X and Y derived from different buffer axes and intermittently
     * stretch the preview. This version first resolves the buffer dimensions as they are presented
     * to the display, then derives a single center-crop scale. One axis therefore remains exactly
     * 1x and the other only crops; neither axis can independently distort the image.
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
        val displayedBufferWidth = if (axesSwapped) bufferHeight else bufferWidth
        val displayedBufferHeight = if (axesSwapped) bufferWidth else bufferHeight

        val uniformScale = max(
            viewWidth.toFloat() / displayedBufferWidth.toFloat(),
            viewHeight.toFloat() / displayedBufferHeight.toFloat(),
        )
        val renderedWidth = displayedBufferWidth * uniformScale
        val renderedHeight = displayedBufferHeight * uniformScale
        val scaleX = renderedWidth / viewWidth.toFloat()
        val scaleY = renderedHeight / viewHeight.toFloat()
        if (!scaleX.isFinite() || !scaleY.isFinite() || scaleX <= 0f || scaleY <= 0f) return null

        return PreviewTransform(
            scaleX = scaleX,
            scaleY = scaleY,
            // Display.getRotation is counter-clockwise from the user's point of view.
            clockwiseDisplayCompensationDegrees = -displayRotation,
            mirrorHorizontally = mirrorHorizontally,
            pivotX = viewWidth / 2f,
            pivotY = viewHeight / 2f,
        )
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
        // Camera preview should match captured sensor orientation. Keep front preview unmirrored.
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
            // Never keep a stale transform from the previous lens/orientation when geometry is not
            // currently valid. Identity is safer than showing a stretched transform from old data.
            textureView.setTransform(Matrix())
            return
        }
        textureView.setTransform(transform.toMatrix())
    }
}
