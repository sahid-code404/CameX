package com.sahidcode404.camex.core.camera

import android.annotation.SuppressLint
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.params.OutputConfiguration
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.view.Surface
import androidx.annotation.RequiresApi
import com.sahidcode404.camex.core.camera.raw.RawCaptureRegistry
import com.sahidcode404.camex.core.model.ProbeFailureKind
import java.io.Closeable
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

internal class CameraCallbackThread(name: String) : Closeable {
    private val closed = AtomicBoolean(false)
    private val thread = HandlerThread(name).apply { start() }
    val handler: Handler = Handler(thread.looper)

    override fun close() {
        if (closed.compareAndSet(false, true)) thread.quitSafely()
    }
}

internal class CameraOperationException(
    val failureKind: ProbeFailureKind,
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)

internal class OpenCameraLease internal constructor(
    val device: CameraDevice,
    private val closedSignal: CompletableDeferred<Unit>,
) : Closeable {
    private val closeRequested = AtomicBoolean(false)

    override fun close() {
        if (closeRequested.compareAndSet(false, true)) runCatching { device.close() }
    }

    suspend fun closeAndAwait(timeoutMillis: Long) {
        close()
        runCatching { withTimeout(timeoutMillis) { closedSignal.await() } }
    }
}

internal class CaptureSessionLease internal constructor(
    val session: CameraCaptureSession,
    private val closedSignal: CompletableDeferred<Unit>,
) : Closeable {
    private val closeRequested = AtomicBoolean(false)

    override fun close() {
        if (closeRequested.compareAndSet(false, true)) {
            RawCaptureRegistry.onSessionClosing(session)
            runCatching { session.stopRepeating() }.also {
                runCatching { session.abortCaptures() }
                runCatching { session.close() }
            }
        }
    }

    suspend fun closeAndAwait(timeoutMillis: Long) {
        close()
        runCatching { withTimeout(timeoutMillis) { closedSignal.await() } }
    }
}

@SuppressLint("MissingPermission")
internal suspend fun CameraManager.awaitOpenCamera(
    cameraId: String,
    handler: Handler,
    timeoutMillis: Long,
    onTerminalFailure: (CameraOperationException) -> Unit = {},
): OpenCameraLease = withTimeout(timeoutMillis) {
    suspendCancellableCoroutine { continuation ->
        val opened = AtomicReference<CameraDevice?>(null)
        val closedSignal = CompletableDeferred<Unit>()
        val resolved = AtomicBoolean(false)

        fun fail(error: CameraOperationException) {
            if (resolved.compareAndSet(false, true)) {
                if (continuation.isActive) continuation.resumeWithException(error)
            } else {
                onTerminalFailure(error)
            }
        }

        val callback = object : CameraDevice.StateCallback() {
            override fun onOpened(camera: CameraDevice) {
                opened.set(camera)
                val lease = OpenCameraLease(camera, closedSignal)
                if (resolved.compareAndSet(false, true)) {
                    if (continuation.isActive) continuation.resume(lease) else lease.close()
                } else {
                    lease.close()
                }
            }

            override fun onDisconnected(camera: CameraDevice) {
                opened.compareAndSet(camera, null)
                runCatching { camera.close() }
                fail(
                    CameraOperationException(
                        ProbeFailureKind.DISCONNECTED,
                        "Camera disconnected",
                    ),
                )
            }

            override fun onError(camera: CameraDevice, error: Int) {
                opened.compareAndSet(camera, null)
                runCatching { camera.close() }
                fail(cameraDeviceError(error))
            }

            override fun onClosed(camera: CameraDevice) {
                opened.compareAndSet(camera, null)
                closedSignal.complete(Unit)
            }
        }

        continuation.invokeOnCancellation {
            opened.getAndSet(null)?.let { camera -> runCatching { camera.close() } }
        }

        try {
            openCamera(cameraId, callback, handler)
        } catch (error: Throwable) {
            if (error is VirtualMachineError || error is ThreadDeath) throw error
            fail(error.toCameraOperationException())
        }
    }
}

internal suspend fun CameraDevice.awaitCaptureSession(
    surface: Surface,
    physicalCameraId: String?,
    maximumResolution: Boolean = false,
    handler: Handler,
    timeoutMillis: Long,
): CaptureSessionLease {
    if (maximumResolution) {
        return awaitSingleCaptureSession(
            surface = surface,
            physicalCameraId = physicalCameraId,
            maximumResolution = true,
            handler = handler,
            timeoutMillis = timeoutMillis,
        )
    }

    val preparedRaw = RawCaptureRegistry.prepareOutput(this, physicalCameraId, handler)
    if (preparedRaw != null) {
        try {
            val lease = awaitCaptureSession(
                outputs = listOf(
                    CameraSessionOutput(surface, physicalCameraId),
                    CameraSessionOutput(preparedRaw.reader.surface, physicalCameraId),
                ),
                handler = handler,
                timeoutMillis = timeoutMillis,
            )
            RawCaptureRegistry.attach(preparedRaw, this, lease.session, handler)
            return lease
        } catch (timeout: TimeoutCancellationException) {
            RawCaptureRegistry.combinedSessionRejected(
                preparedRaw,
                "Preview + RAW session configuration timed out; preview-only fallback is active",
            )
        } catch (cancelled: CancellationException) {
            runCatching { preparedRaw.reader.close() }
            throw cancelled
        } catch (error: Throwable) {
            if (error is VirtualMachineError || error is ThreadDeath) throw error
            RawCaptureRegistry.combinedSessionRejected(
                preparedRaw,
                "Preview + RAW session unsupported; preview-only fallback is active",
            )
        }
    }

    return awaitCaptureSession(
        outputs = listOf(CameraSessionOutput(surface, physicalCameraId)),
        handler = handler,
        timeoutMillis = timeoutMillis,
    )
}

private suspend fun CameraDevice.awaitSingleCaptureSession(
    surface: Surface,
    physicalCameraId: String?,
    maximumResolution: Boolean,
    handler: Handler,
    timeoutMillis: Long,
): CaptureSessionLease = withTimeout(timeoutMillis) {
    suspendCancellableCoroutine { continuation ->
        val configured = AtomicReference<CameraCaptureSession?>(null)
        val closedSignal = CompletableDeferred<Unit>()
        val resolved = AtomicBoolean(false)

        fun fail(error: CameraOperationException) {
            if (!resolved.compareAndSet(false, true)) return
            if (continuation.isActive) continuation.resumeWithException(error)
        }

        val callback = object : CameraDevice.StateCallback(), CameraCaptureSession.StateCallback() {
            override fun onOpened(camera: CameraDevice) = Unit
            override fun onDisconnected(camera: CameraDevice) = Unit
            override fun onError(camera: CameraDevice, error: Int) = Unit

            override fun onConfigured(session: CameraCaptureSession) {
                configured.set(session)
                val lease = CaptureSessionLease(session, closedSignal)
                if (resolved.compareAndSet(false, true)) {
                    if (continuation.isActive) continuation.resume(lease) else lease.close()
                } else {
                    lease.close()
                }
            }

            override fun onConfigureFailed(session: CameraCaptureSession) {
                runCatching { session.close() }
                fail(
                    CameraOperationException(
                        ProbeFailureKind.SESSION_CONFIGURATION,
                        "Capture session configuration failed",
                    ),
                )
            }

            override fun onClosed(session: CameraCaptureSession) {
                configured.compareAndSet(session, null)
                closedSignal.complete(Unit)
            }
        }

        continuation.invokeOnCancellation {
            configured.getAndSet(null)?.let { session -> runCatching { session.close() } }
        }

        try {
            if (maximumResolution) {
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
                    fail(
                        CameraOperationException(
                            ProbeFailureKind.SESSION_CONFIGURATION,
                            "Maximum-resolution output requires API 31",
                        ),
                    )
                } else {
                    createMaximumResolutionCaptureSession(
                        surface,
                        physicalCameraId,
                        callback,
                        handler,
                    )
                }
            } else if (physicalCameraId != null) {
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
                    fail(
                        CameraOperationException(
                            ProbeFailureKind.SESSION_CONFIGURATION,
                            "Physical output routing requires API 28",
                        ),
                    )
                } else {
                    createPhysicalCaptureSession(surface, physicalCameraId, callback, handler)
                }
            } else {
                createCaptureSession(listOf(surface), callback, handler)
            }
        } catch (error: Throwable) {
            if (error is VirtualMachineError || error is ThreadDeath) throw error
            fail(error.toCameraOperationException(ProbeFailureKind.SESSION_CONFIGURATION))
        }
    }
}

@RequiresApi(Build.VERSION_CODES.S)
private fun CameraDevice.createMaximumResolutionCaptureSession(
    surface: Surface,
    physicalCameraId: String?,
    callback: CameraCaptureSession.StateCallback,
    handler: Handler,
) {
    val output = OutputConfiguration(surface).apply {
        physicalCameraId?.let(::setPhysicalCameraId)
        addSensorPixelModeUsed(CameraMetadata.SENSOR_PIXEL_MODE_MAXIMUM_RESOLUTION)
    }
    createCaptureSessionByOutputConfigurations(listOf(output), callback, handler)
}

@RequiresApi(Build.VERSION_CODES.P)
private fun CameraDevice.createPhysicalCaptureSession(
    surface: Surface,
    physicalCameraId: String,
    callback: CameraCaptureSession.StateCallback,
    handler: Handler,
) {
    val output = OutputConfiguration(surface).apply { setPhysicalCameraId(physicalCameraId) }
    // This API was introduced in 24; setPhysicalCameraId in 28. It is intentionally used instead
    // of SessionConfiguration so the same callback Handler owns all camera work.
    createCaptureSessionByOutputConfigurations(listOf(output), callback, handler)
}

internal fun Throwable.toCameraOperationException(
    fallback: ProbeFailureKind = ProbeFailureKind.UNKNOWN,
): CameraOperationException = when (this) {
    is CameraOperationException -> this
    is TimeoutCancellationException -> CameraOperationException(
        ProbeFailureKind.TIMEOUT,
        "Camera operation timed out",
        this,
    )
    is SecurityException -> CameraOperationException(
        ProbeFailureKind.PERMISSION_DENIED,
        "Camera permission denied",
        this,
    )
    is CameraAccessException -> CameraOperationException(
        when (reason) {
            CameraAccessException.CAMERA_DISABLED -> ProbeFailureKind.ACCESS_DENIED
            CameraAccessException.CAMERA_DISCONNECTED -> ProbeFailureKind.DISCONNECTED
            CameraAccessException.CAMERA_ERROR -> ProbeFailureKind.SERVICE_ERROR
            CameraAccessException.CAMERA_IN_USE,
            CameraAccessException.MAX_CAMERAS_IN_USE,
            -> ProbeFailureKind.ACCESS_DENIED
            else -> fallback
        },
        "Camera access failed (${cameraAccessReasonName(reason)})",
        this,
    )
    is IllegalArgumentException -> CameraOperationException(
        fallback,
        "Camera rejected an argument",
        this,
    )
    is IllegalStateException -> CameraOperationException(
        fallback,
        "Camera entered an invalid state",
        this,
    )
    is CancellationException -> CameraOperationException(fallback, "Camera operation cancelled", this)
    else -> CameraOperationException(fallback, javaClass.simpleName.take(64), this)
}

private fun cameraDeviceError(error: Int): CameraOperationException = when (error) {
    CameraDevice.StateCallback.ERROR_CAMERA_DISABLED -> CameraOperationException(
        ProbeFailureKind.ACCESS_DENIED,
        "Camera disabled by policy",
    )
    CameraDevice.StateCallback.ERROR_CAMERA_IN_USE,
    CameraDevice.StateCallback.ERROR_MAX_CAMERAS_IN_USE,
    -> CameraOperationException(ProbeFailureKind.ACCESS_DENIED, "Camera temporarily unavailable")
    CameraDevice.StateCallback.ERROR_CAMERA_DEVICE -> CameraOperationException(
        ProbeFailureKind.DEVICE_ERROR,
        "Camera device error",
    )
    CameraDevice.StateCallback.ERROR_CAMERA_SERVICE -> CameraOperationException(
        ProbeFailureKind.SERVICE_ERROR,
        "Camera service error",
    )
    else -> CameraOperationException(ProbeFailureKind.UNKNOWN, "Unknown camera device error")
}

private fun cameraAccessReasonName(reason: Int): String = when (reason) {
    CameraAccessException.CAMERA_DISABLED -> "disabled"
    CameraAccessException.CAMERA_DISCONNECTED -> "disconnected"
    CameraAccessException.CAMERA_ERROR -> "service_error"
    CameraAccessException.CAMERA_IN_USE -> "in_use"
    CameraAccessException.MAX_CAMERAS_IN_USE -> "max_in_use"
    else -> "unknown"
}
