package com.sahidcode404.camex.core.camera.raw

import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureFailure
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.media.Image
import android.media.ImageReader
import android.os.Handler
import android.os.SystemClock
import com.sahidcode404.camex.core.model.Size2D
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout

internal data class ActiveRawSession(
    val device: CameraDevice,
    val session: CameraCaptureSession,
    val imageReader: ImageReader,
    val routingKey: String,
    val openCameraId: String,
    val physicalCameraId: String?,
    val rawSize: Size2D,
    val availableRawSizes: List<Size2D>,
    val continuousPictureAf: Boolean,
    val transportGeneration: Long,
    val callbackHandler: Handler,
)

internal data class RawFrameMetadata(
    val result: TotalCaptureResult,
    val sensorTimestampNs: Long,
    val exposureTimeNs: Long?,
    val iso: Int?,
)

internal class RawCaptureEngine(
    private val cameraManager: CameraManager,
    private val dngWriter: RawDngWriter,
) {
    suspend fun capture(
        active: ActiveRawSession,
        context: RawCaptureContext,
        timeoutMillis: Long,
        isStillCurrent: () -> Boolean,
        onSaving: (RawCaptureDiagnostics) -> Unit,
    ): RawCaptureResult {
        val startedNs = SystemClock.elapsedRealtimeNanos()
        val completion = CompletableDeferred<RawFramePairer.Pair<Image, RawFrameMetadata>>()
        val pairer = RawFramePairer<Image, RawFrameMetadata>(
            maxPending = MAX_PENDING_FRAMES,
            onDiscardImage = { image -> runCatching { image.close() } },
        )

        fun completePair(pair: RawFramePairer.Pair<Image, RawFrameMetadata>) {
            if (!completion.complete(pair)) runCatching { pair.image.close() }
        }

        active.imageReader.setOnImageAvailableListener({ reader ->
            while (true) {
                val image = runCatching { reader.acquireNextImage() }.getOrNull() ?: break
                pairer.offerImage(image.timestamp, image)?.let(::completePair)
            }
        }, active.callbackHandler)

        val callback = object : CameraCaptureSession.CaptureCallback() {
            override fun onCaptureCompleted(
                session: CameraCaptureSession,
                request: CaptureRequest,
                result: TotalCaptureResult,
            ) {
                val timestamp = result.get(CaptureResult.SENSOR_TIMESTAMP)
                if (timestamp == null || timestamp <= 0L) {
                    completion.completeExceptionally(
                        IllegalStateException("RAW capture result did not contain SENSOR_TIMESTAMP"),
                    )
                    return
                }
                val metadata = RawFrameMetadata(
                    result = result,
                    sensorTimestampNs = timestamp,
                    exposureTimeNs = result.get(CaptureResult.SENSOR_EXPOSURE_TIME),
                    iso = result.get(CaptureResult.SENSOR_SENSITIVITY),
                )
                pairer.offerResult(timestamp, metadata)?.let(::completePair)
            }

            override fun onCaptureFailed(
                session: CameraCaptureSession,
                request: CaptureRequest,
                failure: CaptureFailure,
            ) {
                completion.completeExceptionally(IllegalStateException("RAW still capture failed"))
            }

            override fun onCaptureSequenceAborted(session: CameraCaptureSession, sequenceId: Int) {
                completion.completeExceptionally(IllegalStateException("RAW capture sequence aborted"))
            }
        }

        var matchedImage: Image? = null
        return try {
            val request = try {
                active.device.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
                    addTarget(active.imageReader.surface)
                    set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)
                    if (active.continuousPictureAf) {
                        set(
                            CaptureRequest.CONTROL_AF_MODE,
                            CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_PICTURE,
                        )
                    }
                }.build()
            } catch (rejected: IllegalArgumentException) {
                return RawCaptureResult.Failed(
                    reason = "RAW capture request was rejected by this camera profile",
                    structural = true,
                    diagnostics = diagnostics(
                        context = context,
                        active = active,
                        startedNs = startedNs,
                        error = "capture request rejected",
                    ),
                    failureKind = RawFailureKind.CAPTURE_REQUEST_REJECTED,
                )
            }

            try {
                active.session.capture(request, callback, active.callbackHandler)
            } catch (rejected: IllegalArgumentException) {
                return RawCaptureResult.Failed(
                    reason = "RAW capture request target is unsupported by this camera profile",
                    structural = true,
                    diagnostics = diagnostics(
                        context = context,
                        active = active,
                        startedNs = startedNs,
                        error = "capture target rejected",
                    ),
                    failureKind = RawFailureKind.CAPTURE_REQUEST_REJECTED,
                )
            }

            val pair = withTimeout(timeoutMillis) { completion.await() }
            matchedImage = pair.image
            if (!isStillCurrent()) {
                return RawCaptureResult.Failed(
                    reason = "Camera selection changed before RAW capture completed",
                    structural = false,
                    diagnostics = diagnostics(
                        context = context,
                        active = active,
                        imageTimestamp = pair.timestampNs,
                        metadata = pair.result,
                        startedNs = startedNs,
                        error = "stale selection generation",
                    ),
                    failureKind = RawFailureKind.STALE_SELECTION,
                )
            }

            val preWrite = diagnostics(
                context = context,
                active = active,
                imageTimestamp = pair.timestampNs,
                metadata = pair.result,
                startedNs = startedNs,
            )
            onSaving(preWrite)
            val writeStartedNs = SystemClock.elapsedRealtimeNanos()
            val file = try {
                val characteristicsCameraId = active.physicalCameraId ?: active.openCameraId
                val characteristics = cameraManager.getCameraCharacteristics(characteristicsCameraId)
                dngWriter.write(characteristics, pair.result.result, pair.image)
            } catch (error: Throwable) {
                if (error is VirtualMachineError || error is ThreadDeath) throw error
                val detail = error.message?.take(160) ?: error.javaClass.simpleName
                return RawCaptureResult.Failed(
                    reason = detail,
                    structural = false,
                    diagnostics = preWrite.copy(
                        writeDurationMs = nanosToMillis(
                            SystemClock.elapsedRealtimeNanos() - writeStartedNs,
                        ),
                        lastRawError = detail,
                    ),
                    failureKind = RawFailureKind.OUTPUT_WRITE,
                )
            }
            val finalDiagnostics = preWrite.copy(
                dngWidth = file.width,
                dngHeight = file.height,
                dngBytes = file.bytes,
                mediaStoreUri = file.uri,
                writeDurationMs = nanosToMillis(SystemClock.elapsedRealtimeNanos() - writeStartedNs),
                lastRawError = null,
            )
            RawCaptureResult.Saved(file, finalDiagnostics)
        } catch (timeout: TimeoutCancellationException) {
            RawCaptureResult.Failed(
                reason = "RAW image/result pairing timed out",
                structural = false,
                diagnostics = diagnostics(
                    context = context,
                    active = active,
                    startedNs = startedNs,
                    error = "capture timeout",
                ),
                failureKind = RawFailureKind.TIMEOUT,
            )
        } catch (error: Throwable) {
            if (error is VirtualMachineError || error is ThreadDeath) throw error
            val detail = error.message?.take(160) ?: error.javaClass.simpleName
            RawCaptureResult.Failed(
                reason = detail,
                structural = false,
                diagnostics = diagnostics(
                    context = context,
                    active = active,
                    startedNs = startedNs,
                    error = detail,
                ),
                failureKind = RawFailureKind.CAPTURE_FAILED,
            )
        } finally {
            active.imageReader.setOnImageAvailableListener(null, null)
            pairer.clear()
            matchedImage?.let { runCatching { it.close() } }
        }
    }

    private fun diagnostics(
        context: RawCaptureContext,
        active: ActiveRawSession,
        imageTimestamp: Long? = null,
        metadata: RawFrameMetadata? = null,
        startedNs: Long,
        error: String? = null,
    ) = RawCaptureDiagnostics(
        context = context,
        rawSupported = RawSupportState.SUPPORTED,
        availableRawSizes = active.availableRawSizes,
        selectedRawSize = active.rawSize,
        rawTimestamp = imageTimestamp,
        resultTimestamp = metadata?.sensorTimestampNs,
        exposureTimeNs = metadata?.exposureTimeNs,
        iso = metadata?.iso,
        captureDurationMs = nanosToMillis(SystemClock.elapsedRealtimeNanos() - startedNs),
        lastRawError = error,
    )

    private fun nanosToMillis(value: Long): Long = (value / 1_000_000L).coerceAtLeast(0L)

    private companion object {
        const val MAX_PENDING_FRAMES = 4
    }
}
