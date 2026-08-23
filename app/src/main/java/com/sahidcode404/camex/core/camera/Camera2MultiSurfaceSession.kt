package com.sahidcode404.camex.core.camera

import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.params.OutputConfiguration
import android.os.Build
import android.os.Handler
import android.view.Surface
import com.sahidcode404.camex.core.model.ProbeFailureKind
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

internal data class CameraSessionOutput(
    val surface: Surface,
    val physicalCameraId: String? = null,
)

/**
 * Multi-output sibling of the Phase 1 primitive. It configures preview + RAW on the already-open
 * CameraDevice and never opens a camera itself.
 */
internal suspend fun CameraDevice.awaitCaptureSession(
    outputs: List<CameraSessionOutput>,
    handler: Handler,
    timeoutMillis: Long,
): CaptureSessionLease = withTimeout(timeoutMillis) {
    require(outputs.isNotEmpty()) { "Capture session requires at least one output" }
    suspendCancellableCoroutine { continuation ->
        val configured = AtomicReference<CameraCaptureSession?>(null)
        val closedSignal = CompletableDeferred<Unit>()
        val resolved = AtomicBoolean(false)

        fun fail(error: CameraOperationException) {
            if (!resolved.compareAndSet(false, true)) return
            if (continuation.isActive) continuation.resumeWithException(error)
        }

        val callback = object : CameraCaptureSession.StateCallback() {
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
            configured.getAndSet(null)?.let { runCatching { it.close() } }
        }

        try {
            val usesPhysicalRouting = outputs.any { it.physicalCameraId != null }
            if (usesPhysicalRouting && Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
                fail(
                    CameraOperationException(
                        ProbeFailureKind.SESSION_CONFIGURATION,
                        "Physical output routing requires API 28",
                    ),
                )
            } else if (usesPhysicalRouting) {
                val configurations = outputs.map { output ->
                    OutputConfiguration(output.surface).apply {
                        output.physicalCameraId?.let(::setPhysicalCameraId)
                    }
                }
                createCaptureSessionByOutputConfigurations(configurations, callback, handler)
            } else {
                createCaptureSession(outputs.map { it.surface }, callback, handler)
            }
        } catch (error: Throwable) {
            if (error is VirtualMachineError || error is ThreadDeath) throw error
            fail(error.toCameraOperationException(ProbeFailureKind.SESSION_CONFIGURATION))
        }
    }
}
