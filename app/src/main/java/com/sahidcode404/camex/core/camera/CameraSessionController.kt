package com.sahidcode404.camex.core.camera

import android.content.Context
import android.graphics.SurfaceTexture
import android.hardware.display.DisplayManager
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureFailure
import android.hardware.camera2.CaptureRequest
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.Surface
import android.view.TextureView
import com.sahidcode404.camex.core.model.CapabilitySupport
import com.sahidcode404.camex.core.model.LensDescriptor
import com.sahidcode404.camex.core.model.LensFacing
import com.sahidcode404.camex.core.model.LensUsability
import com.sahidcode404.camex.core.model.ProbeFailureKind
import com.sahidcode404.camex.core.model.Size2D
import com.sahidcode404.camex.core.model.StreamConfiguration
import com.sahidcode404.camex.core.model.StreamFormat
import java.io.Closeable
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

sealed interface CameraSessionState {
    data object Idle : CameraSessionState
    data object PermissionRequired : CameraSessionState
    data object Discovering : CameraSessionState
    data class Probing(val candidateCount: Int) : CameraSessionState
    data class Ready(val lensCount: Int) : CameraSessionState
    data class AwaitingSurface(val routingKey: String) : CameraSessionState
    data class Opening(val routingKey: String) : CameraSessionState
    data class Previewing(val routingKey: String, val previewSize: Size2D) : CameraSessionState
    data class Switching(val fromRoutingKey: String?, val toRoutingKey: String) : CameraSessionState
    data class Closing(val routingKey: String?) : CameraSessionState
    data class Paused(val selectedRoutingKey: String?) : CameraSessionState
    data class ErrorRecoverable(val error: CameraRuntimeError) : CameraSessionState
    data object Closed : CameraSessionState
}

data class CameraRuntimeError(
    val kind: ProbeFailureKind,
    val detail: String,
    val routingKey: String? = null,
)

data class CameraRuntimeSnapshot(
    val discovery: CameraDiscoverySnapshot = CameraDiscoverySnapshot.Empty,
    val probe: CameraProbeSnapshot = CameraProbeSnapshot.Empty,
    val selectedRoutingKey: String? = null,
    val activePreviewSize: Size2D? = null,
    val lastError: CameraRuntimeError? = null,
    val livePreviewFailureMemory: Map<String, ProbeFailureMemoryEntry> = emptyMap(),
) {
    val lenses: List<LensDescriptor>
        get() = probe.lenses.ifEmpty { discovery.lenses }

    val selectedLens: LensDescriptor?
        get() = selectedRoutingKey?.let { key ->
            lenses.firstOrNull { it.identity.routingKey == key }
        }

    companion object {
        val Empty = CameraRuntimeSnapshot()
    }
}

/** Compose-neutral API intended to be owned by a lifecycle-aware ViewModel. */
interface CameraSessionController : Closeable {
    val state: StateFlow<CameraSessionState>
    val snapshot: StateFlow<CameraRuntimeSnapshot>

    suspend fun discoverAndProbe(retryRememberedFailures: Boolean = false): CameraRuntimeSnapshot
    suspend fun retryProbe(lens: LensDescriptor): LensDescriptor
    suspend fun bindPreview(textureView: TextureView)
    suspend fun unbindPreview()
    suspend fun open(lens: LensDescriptor)
    suspend fun switchTo(lens: LensDescriptor)
    suspend fun pause()
    suspend fun resume()
}

/**
 * Serialized Camera2 lifecycle implementation. All open/switch/close/probe operations share one
 * mutex, and camera callbacks run on one owned HandlerThread rather than the UI thread.
 */
class DefaultCameraSessionController(
    context: Context,
    private val discoveryEngine: CameraDiscoveryEngine = CameraDiscoveryEngine(context),
    private val probeEngine: CameraProbeEngine = CameraProbeEngine(context),
    private val cameraManager: CameraManager = context.applicationContext
        .getSystemService(CameraManager::class.java),
    private val quirkRegistry: CameraQuirkRegistry = CameraQuirkRegistry(),
    private val environment: CameraRuntimeEnvironment = defaultEnvironment(),
    dispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val mainDispatcher: CoroutineDispatcher = Dispatchers.Main.immediate,
) : CameraSessionController {
    private val operationMutex = Mutex()
    private val callbackThread = CameraCallbackThread("camex-camera-preview")
    private val displayManager: DisplayManager = context.applicationContext
        .getSystemService(DisplayManager::class.java)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val closed = AtomicBoolean(false)
    private val activeDevice = AtomicReference<OpenCameraLease?>(null)
    private val activeSession = AtomicReference<CaptureSessionLease?>(null)
    private val activeSurface = AtomicReference<Surface?>(null)
    private val activeProbeJob = AtomicReference<Job?>(null)
    private val activeOpenJob = AtomicReference<Job?>(null)
    private val livePreviewFailures = mutableMapOf<String, LivePreviewFailure>()
    private val pendingTextureFrame = AtomicReference<PreviewFrameGate?>(null)
    private val lifecycleActive = AtomicBoolean(true)
    private val lifecycleEpoch = AtomicLong(0L)
    private val mutableState = MutableStateFlow<CameraSessionState>(CameraSessionState.Idle)
    private val mutableSnapshot = MutableStateFlow(CameraRuntimeSnapshot.Empty)

    override val state: StateFlow<CameraSessionState> = mutableState.asStateFlow()
    override val snapshot: StateFlow<CameraRuntimeSnapshot> = mutableSnapshot.asStateFlow()

    private var boundTextureView: TextureView? = null
    private var selectedLens: LensDescriptor? = null
    private var activeLens: LensDescriptor? = null
    private var activePreviewConfiguration: StreamConfiguration? = null
    private var resumed = true

    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) = Unit
        override fun onDisplayRemoved(displayId: Int) = Unit

        override fun onDisplayChanged(displayId: Int) {
            val view = boundTextureView ?: return
            if (view.display?.displayId == displayId) scope.launch { updateTransform() }
        }
    }

    init {
        // SurfaceTexture size callbacks are not guaranteed for a 180-degree display rotation.
        displayManager.registerDisplayListener(displayListener, mainHandler)
    }

    private val surfaceListener = object : TextureView.SurfaceTextureListener {
        override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
            scope.launch { handleSurfaceAvailable() }
        }

        override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) {
            scope.launch { updateTransform() }
        }

        override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
            pendingTextureFrame.getAndSet(null)?.fail(
                CancellationException("Preview surface destroyed before its first frame"),
            )
            scope.launch { handleSurfaceDestroyed() }
            return true
        }

        override fun onSurfaceTextureUpdated(surface: SurfaceTexture) {
            pendingTextureFrame.get()?.markTextureUpdated(surface)
        }
    }

    override suspend fun discoverAndProbe(
        retryRememberedFailures: Boolean,
    ): CameraRuntimeSnapshot {
        val callingJob = currentCoroutineContext()[Job]
        return try {
            operationMutex.withLock {
                ensureOpen()
                activeProbeJob.set(callingJob)
                ensureOperationActive()
                closePreviewLocked(updateState = false)
                mutableState.value = CameraSessionState.Discovering
                try {
                    if (retryRememberedFailures) {
                        probeEngine.clearFailureMemory()
                        livePreviewFailures.clear()
                        publishLivePreviewFailureMemory()
                    }
                    val discovery = discoveryEngine.discover()
                    ensureOperationActive()
                    mutableSnapshot.value = mutableSnapshot.value.copy(
                        discovery = discovery,
                        probe = CameraProbeSnapshot.Empty,
                        activePreviewSize = null,
                        lastError = null,
                    )
                    mutableState.value = CameraSessionState.Probing(discovery.lenses.size)
                    val probe = probeEngine.probeSequentially(discovery.lenses)
                    ensureOperationActive()
                    val selectedKey = selectedLens?.identity?.routingKey
                        ?.takeIf { key -> probe.lenses.any { it.identity.routingKey == key } }
                    selectedLens = selectedKey?.let { key ->
                        probe.lenses.firstOrNull { it.identity.routingKey == key }
                    }
                    mutableSnapshot.value = CameraRuntimeSnapshot(
                        discovery = discovery,
                        probe = probe,
                        selectedRoutingKey = selectedKey,
                        livePreviewFailureMemory = livePreviewFailures.mapValues {
                            (_, failure) -> failure.snapshot()
                        },
                    )
                    val permissionDenied = probe.resultsByRoutingKey.values.any { result ->
                        result.stages.any { it.failureKind == ProbeFailureKind.PERMISSION_DENIED }
                    }
                    mutableState.value = if (permissionDenied && probe.lenses.none {
                            it.probeResult?.previewVerified == true
                        }
                    ) {
                        CameraSessionState.PermissionRequired
                    } else {
                        CameraSessionState.Ready(
                            probe.lenses.count { it.usability.isSelectable },
                        )
                    }
                    mutableSnapshot.value
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Throwable) {
                    if (error is VirtualMachineError || error is ThreadDeath) throw error
                    if (closed.get()) return@withLock mutableSnapshot.value
                    val runtimeError = error.runtimeError(selectedLens)
                    mutableSnapshot.value = mutableSnapshot.value.copy(lastError = runtimeError)
                    mutableState.value = stateFor(runtimeError)
                    mutableSnapshot.value
                }
            }
        } finally {
            activeProbeJob.compareAndSet(callingJob, null)
        }
    }

    override suspend fun retryProbe(lens: LensDescriptor): LensDescriptor {
        val callingJob = currentCoroutineContext()[Job]
        return try {
            operationMutex.withLock {
                activeProbeJob.set(callingJob)
                ensureOperationActive()
                closePreviewLocked(updateState = false)
                livePreviewFailures.remove(lens.identity.routingKey)
                publishLivePreviewFailureMemory()
                probeEngine.clearFailureMemory(
                    routingKey = lens.identity.routingKey,
                    openCameraId = lens.identity.openCameraId,
                )
                mutableState.value = CameraSessionState.Probing(1)
                val updated = probeEngine.probe(lens)
                ensureOperationActive()
                val existing = mutableSnapshot.value
                val lenses = existing.lenses.map {
                    if (it.identity.routingKey == updated.identity.routingKey) updated else it
                }
                val probe = existing.probe.copy(
                    lenses = lenses,
                    resultsByRoutingKey = existing.probe.resultsByRoutingKey +
                        (updated.identity.routingKey to requireNotNull(updated.probeResult)),
                    failureMemory = probeEngine.failureMemorySnapshot(),
                    openFailureMemory = probeEngine.openFailureMemorySnapshot(),
                    rawFailureMemory = probeEngine.rawFailureMemorySnapshot(),
                )
                if (selectedLens?.identity?.routingKey == updated.identity.routingKey) {
                    selectedLens = updated
                }
                mutableSnapshot.value = existing.copy(probe = probe, activePreviewSize = null)
                mutableState.value = if (updated.probeResult.stages.any {
                        it.failureKind == ProbeFailureKind.PERMISSION_DENIED
                    }
                ) {
                    CameraSessionState.PermissionRequired
                } else {
                    CameraSessionState.Ready(lenses.count { it.usability.isSelectable })
                }
                updated
            }
        } finally {
            activeProbeJob.compareAndSet(callingJob, null)
        }
    }

    override suspend fun bindPreview(textureView: TextureView) = operationMutex.withLock {
        ensureOpen()
        if (boundTextureView !== textureView) {
            closePreviewLocked(updateState = false)
            withContext(mainDispatcher) {
                boundTextureView?.surfaceTextureListener = null
                boundTextureView = textureView
                textureView.surfaceTextureListener = surfaceListener
            }
        }
        val target = selectedLens
        if (resumed && lifecycleActive.get() && target != null) {
            if (isSurfaceAvailable(textureView)) openPreviewLocked(target, switching = false)
            else mutableState.value = CameraSessionState.AwaitingSurface(target.identity.routingKey)
        }
    }

    override suspend fun unbindPreview() = operationMutex.withLock {
        if (closed.get()) return@withLock
        closePreviewLocked(updateState = false)
        withContext(mainDispatcher) {
            boundTextureView?.surfaceTextureListener = null
            boundTextureView = null
        }
        mutableState.value = if (resumed && lifecycleActive.get()) {
            CameraSessionState.Ready(mutableSnapshot.value.lenses.count { it.usability.isSelectable })
        } else {
            CameraSessionState.Paused(selectedLens?.identity?.routingKey)
        }
    }

    override suspend fun open(lens: LensDescriptor) = operationMutex.withLock {
        ensureOpen()
        val target = resolveKnownLens(lens)
        ensureCanPreview(target)
        selectedLens = target
        mutableSnapshot.value = mutableSnapshot.value.copy(
            selectedRoutingKey = target.identity.routingKey,
            lastError = null,
        )
        if (!resumed || !lifecycleActive.get()) {
            mutableState.value = CameraSessionState.Paused(target.identity.routingKey)
            return@withLock
        }
        val view = boundTextureView
        if (view == null || !isSurfaceAvailable(view)) {
            mutableState.value = CameraSessionState.AwaitingSurface(target.identity.routingKey)
            return@withLock
        }
        openPreviewLocked(target, switching = activeLens != null)
    }

    override suspend fun switchTo(lens: LensDescriptor) = open(lens)

    override suspend fun pause() = applyLifecycleRequest(active = false)

    override suspend fun resume() = applyLifecycleRequest(active = true)

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        lifecycleEpoch.incrementAndGet()
        lifecycleActive.set(false)
        mutableState.value = CameraSessionState.Closing(activeLens?.identity?.routingKey)
        activeProbeJob.getAndSet(null)?.cancel(CancellationException("Camera controller closed"))
        activeOpenJob.getAndSet(null)?.cancel(CancellationException("Camera controller closed"))
        pendingTextureFrame.getAndSet(null)?.fail(
            CancellationException("Camera controller closed before its first preview frame"),
        )
        displayManager.unregisterDisplayListener(displayListener)
        scope.cancel()
        activeSession.getAndSet(null)?.close()
        activeSurface.getAndSet(null)?.let { runCatching { it.release() } }
        activeDevice.getAndSet(null)?.close()
        boundTextureView?.let { view ->
            view.post {
                if (view.surfaceTextureListener === surfaceListener) {
                    view.surfaceTextureListener = null
                }
            }
        }
        boundTextureView = null
        activeLens = null
        activePreviewConfiguration = null
        callbackThread.close()
        probeEngine.close()
        mutableSnapshot.value = mutableSnapshot.value.copy(activePreviewSize = null)
        mutableState.value = CameraSessionState.Closed
    }

    private suspend fun openPreviewLocked(lens: LensDescriptor, switching: Boolean) {
        val view = boundTextureView ?: return
        ensureOperationActive()
        if (activeLens?.identity?.routingKey == lens.identity.routingKey &&
            activeSession.get() != null
        ) {
            updateTransformLocked(view)
            return
        }
        val policy = policyFor(lens)
        val rememberedFailure = livePreviewFailures[lens.identity.routingKey]
        if (rememberedFailure != null &&
            rememberedFailure.count >= policy.maxAutomaticFailures
        ) {
            closePreviewLocked(updateState = false)
            return setRecoverableError(
                CameraRuntimeError(
                    kind = rememberedFailure.kind,
                    detail = "Automatic live preview retry suppressed after repeated failures",
                    routingKey = lens.identity.routingKey,
                ),
            )
        }
        val fromKey = activeLens?.identity?.routingKey
        mutableState.value = if (switching || activeLens != null) {
            CameraSessionState.Switching(fromKey, lens.identity.routingKey)
        } else {
            CameraSessionState.Opening(lens.identity.routingKey)
        }
        closePreviewLocked(updateState = false)
        ensureOperationActive()

        val dimensions = withContext(mainDispatcher) { view.width to view.height }
        val orientation = previewOrientation(lens, view)
        val configuration = selectPreviewConfiguration(
            lens,
            dimensions,
            orientation.relativeRotationDegrees,
            policy,
        )
            ?: return setRecoverableError(
                CameraRuntimeError(
                    ProbeFailureKind.INVALID_METADATA,
                    "No TextureView-compatible preview size was reported",
                    lens.identity.routingKey,
                ),
            )
        if (lens.identity.streamPhysicalCameraId != null &&
            (Build.VERSION.SDK_INT < Build.VERSION_CODES.P || !policy.allowPhysicalOutputRouting)
        ) {
            return setRecoverableError(
                CameraRuntimeError(
                    ProbeFailureKind.SESSION_CONFIGURATION,
                    "Physical preview routing is unavailable",
                    lens.identity.routingKey,
                ),
            )
        }

        val openingJob = currentCoroutineContext()[Job]
        activeOpenJob.set(openingJob)
        var device: OpenCameraLease? = null
        var session: CaptureSessionLease? = null
        var surface: Surface? = null
        var frameGate: PreviewFrameGate? = null
        try {
            ensureOperationActive()
            mutableState.value = if (switching || fromKey != null) {
                CameraSessionState.Switching(fromKey, lens.identity.routingKey)
            } else {
                CameraSessionState.Opening(lens.identity.routingKey)
            }
            val textureState = withContext(mainDispatcher) {
                view.surfaceTexture?.also {
                    it.setDefaultBufferSize(configuration.size.width, configuration.size.height)
                }?.let { it to it.timestamp }
            } ?: throw CameraOperationException(
                ProbeFailureKind.SESSION_CONFIGURATION,
                "Preview surface became unavailable",
            )
            val texture = textureState.first
            ensureOperationActive()
            applyTransformLocked(view, lens, configuration)
            surface = Surface(texture)
            activeSurface.set(surface)
            device = cameraManager.awaitOpenCamera(
                cameraId = lens.identity.openCameraId,
                handler = callbackThread.handler,
                timeoutMillis = policy.openTimeoutMillis,
                onTerminalFailure = { error -> handleTerminalFailure(lens, error) },
            )
            ensureOperationActive()
            activeDevice.set(device)
            session = device.device.awaitCaptureSession(
                surface = surface,
                physicalCameraId = lens.identity.streamPhysicalCameraId,
                handler = callbackThread.handler,
                timeoutMillis = policy.sessionTimeoutMillis,
            )
            ensureOperationActive()
            activeSession.set(session)
            val gate = PreviewFrameGate(
                expectedTexture = texture,
                baselineTimestampNs = textureState.second,
            )
            frameGate = gate
            pendingTextureFrame.set(gate)
            val request = device.device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                addTarget(surface)
                set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)
                if (lens.capabilities.afModes.orEmpty().contains("CONTINUOUS_PICTURE")) {
                    set(
                        CaptureRequest.CONTROL_AF_MODE,
                        CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_PICTURE,
                    )
                }
            }.build()
            ensureOperationActive()
            session.session.setRepeatingRequest(
                request,
                object : CameraCaptureSession.CaptureCallback() {
                    override fun onCaptureStarted(
                        session: CameraCaptureSession,
                        request: android.hardware.camera2.CaptureRequest,
                        timestamp: Long,
                        frameNumber: Long,
                    ) {
                        gate.markCaptureStarted()
                    }

                    override fun onCaptureFailed(
                        session: CameraCaptureSession,
                        request: android.hardware.camera2.CaptureRequest,
                        failure: CaptureFailure,
                    ) {
                        gate.fail(
                            CameraOperationException(
                                ProbeFailureKind.DEVICE_ERROR,
                                "Preview capture failed",
                            ),
                        )
                    }
                },
                callbackThread.handler,
            )
            // Capture-start alone only proves that the HAL accepted a request. Do not publish
            // Previewing until TextureView has latched a newer frame from this stream as well.
            withTimeout(policy.firstFrameTimeoutMillis) { gate.awaitFrame() }
            ensureOperationActive()
            activeLens = lens
            selectedLens = lens
            activePreviewConfiguration = configuration
            mutableSnapshot.value = mutableSnapshot.value.copy(
                selectedRoutingKey = lens.identity.routingKey,
                activePreviewSize = configuration.size,
                lastError = null,
            )
            updateTransformLocked(view)
            ensureOperationActive()
            livePreviewFailures.remove(lens.identity.routingKey)
            publishLivePreviewFailureMemory()
            mutableState.value = CameraSessionState.Previewing(
                lens.identity.routingKey,
                configuration.size,
            )
        } catch (error: TimeoutCancellationException) {
            session?.closeAndAwait(policy.closeSettleTimeoutMillis)
            activeSession.compareAndSet(session, null)
            surface?.let { runCatching { it.release() } }
            activeSurface.compareAndSet(surface, null)
            device?.closeAndAwait(policy.closeSettleTimeoutMillis)
            activeDevice.compareAndSet(device, null)
            activeLens = null
            activePreviewConfiguration = null
            rememberLivePreviewFailure(lens, ProbeFailureKind.TIMEOUT, policy)
            if (!closed.get() && lifecycleActive.get()) {
                val runtimeError = error.runtimeError(lens)
                mutableSnapshot.value = mutableSnapshot.value.copy(
                    activePreviewSize = null,
                    lastError = runtimeError,
                )
                mutableState.value = stateFor(runtimeError)
            }
        } catch (error: CancellationException) {
            session?.close()
            activeSession.compareAndSet(session, null)
            surface?.let { runCatching { it.release() } }
            activeSurface.compareAndSet(surface, null)
            device?.close()
            activeDevice.compareAndSet(device, null)
            activeLens = null
            activePreviewConfiguration = null
            throw error
        } catch (error: Throwable) {
            if (error is VirtualMachineError || error is ThreadDeath) throw error
            session?.closeAndAwait(policy.closeSettleTimeoutMillis)
            activeSession.compareAndSet(session, null)
            surface?.let { runCatching { it.release() } }
            activeSurface.compareAndSet(surface, null)
            device?.closeAndAwait(policy.closeSettleTimeoutMillis)
            activeDevice.compareAndSet(device, null)
            activeLens = null
            activePreviewConfiguration = null
            if (closed.get() || !lifecycleActive.get()) return
            val runtimeError = error.runtimeError(lens)
            rememberLivePreviewFailure(lens, runtimeError.kind, policy)
            mutableSnapshot.value = mutableSnapshot.value.copy(
                activePreviewSize = null,
                lastError = runtimeError,
            )
            mutableState.value = stateFor(runtimeError)
        } finally {
            frameGate?.let { pendingTextureFrame.compareAndSet(it, null) }
            activeOpenJob.compareAndSet(openingJob, null)
        }
    }

    private suspend fun closePreviewLocked(updateState: Boolean) {
        val closingKey = activeLens?.identity?.routingKey
        if (updateState) mutableState.value = CameraSessionState.Closing(closingKey)
        pendingTextureFrame.getAndSet(null)?.fail(
            CancellationException("Preview closed before its first frame"),
        )
        val policy = activeLens?.let(::policyFor) ?: CameraOperationPolicy()
        activeSession.getAndSet(null)?.closeAndAwait(policy.closeSettleTimeoutMillis)
        activeSurface.getAndSet(null)?.let { runCatching { it.release() } }
        activeDevice.getAndSet(null)?.closeAndAwait(policy.closeSettleTimeoutMillis)
        activeLens = null
        activePreviewConfiguration = null
        mutableSnapshot.value = mutableSnapshot.value.copy(activePreviewSize = null)
    }

    private suspend fun handleSurfaceAvailable() = operationMutex.withLock {
        if (closed.get() || !resumed || !lifecycleActive.get()) return@withLock
        val target = selectedLens ?: return@withLock
        val view = boundTextureView ?: return@withLock
        if (isSurfaceAvailable(view)) openPreviewLocked(target, switching = false)
    }

    private suspend fun handleSurfaceDestroyed() = operationMutex.withLock {
        if (closed.get()) return@withLock
        closePreviewLocked(updateState = false)
        val target = selectedLens
        mutableState.value = when {
            !resumed || !lifecycleActive.get() ->
                CameraSessionState.Paused(target?.identity?.routingKey)
            target != null -> CameraSessionState.AwaitingSurface(target.identity.routingKey)
            else -> CameraSessionState.Ready(
                mutableSnapshot.value.lenses.count { it.usability.isSelectable },
            )
        }
    }

    private suspend fun updateTransform() = operationMutex.withLock {
        val view = boundTextureView ?: return@withLock
        updateTransformLocked(view)
    }

    private suspend fun updateTransformLocked(view: TextureView) {
        val configuration = activePreviewConfiguration ?: return
        val lens = activeLens ?: return
        applyTransformLocked(view, lens, configuration)
    }

    private suspend fun applyTransformLocked(
        view: TextureView,
        lens: LensDescriptor,
        configuration: StreamConfiguration,
    ) {
        val orientation = previewOrientation(lens, view)
        withContext(mainDispatcher) {
            TexturePreviewTransform.apply(
                textureView = view,
                bufferWidth = configuration.size.width,
                bufferHeight = configuration.size.height,
                sensorOrientationDegrees = orientation.sensorOrientationDegrees,
                displayRotationDegrees = orientation.displayRotationDegrees,
                frontFacing = lens.facing == LensFacing.FRONT,
                mirrorHorizontally = lens.facing == LensFacing.FRONT,
            )
        }
    }

    private fun handleTerminalFailure(lens: LensDescriptor, error: CameraOperationException) {
        scope.launch {
            operationMutex.withLock {
                if (activeLens?.identity?.routingKey != lens.identity.routingKey || closed.get()) {
                    return@withLock
                }
                closePreviewLocked(updateState = false)
                rememberLivePreviewFailure(lens, error.failureKind, policyFor(lens))
                setRecoverableError(
                    CameraRuntimeError(
                        error.failureKind,
                        error.message.orEmpty().take(160),
                        lens.identity.routingKey,
                    ),
                )
            }
        }
    }

    private fun selectPreviewConfiguration(
        lens: LensDescriptor,
        viewDimensions: Pair<Int, Int>,
        rotationDegrees: Int,
        policy: CameraOperationPolicy,
    ): StreamConfiguration? {
        val candidates = lens.capabilities.configurations(StreamFormat.PRIVATE)
            .filter { !it.maximumResolution && it.size.isValid }
        val swapsAxes = rotationDegrees == 90 || rotationDegrees == 270
        val targetWidth = if (swapsAxes) viewDimensions.second else viewDimensions.first
        val targetHeight = if (swapsAxes) viewDimensions.first else viewDimensions.second
        val selected = PreviewSizeSelector.select(
            candidates.map {
                PreviewStreamCandidate(it.size.width, it.size.height, it.minFrameDurationNs)
            },
            PreviewSelectionRequest(
                targetWidth.coerceAtLeast(1),
                targetHeight.coerceAtLeast(1),
                maximumArea = policy.maximumPreviewArea,
                maximumLongEdge = policy.maximumPreviewLongEdge,
            ),
        ) ?: return null
        return candidates.firstOrNull {
            it.size.width == selected.width && it.size.height == selected.height
        }
    }

    private suspend fun previewOrientation(
        lens: LensDescriptor,
        view: TextureView,
    ): PreviewOrientation {
        val displayRotation = withContext(mainDispatcher) { view.display?.rotation ?: Surface.ROTATION_0 }
        val displayDegrees = when (displayRotation) {
            Surface.ROTATION_90 -> 90
            Surface.ROTATION_180 -> 180
            Surface.ROTATION_270 -> 270
            else -> 0
        }
        val sensor = lens.capabilities.sensorOrientationDegrees ?: 0
        return PreviewOrientation(
            sensorOrientationDegrees = sensor,
            displayRotationDegrees = displayDegrees,
            relativeRotationDegrees = TexturePreviewTransform.relativeRotationDegrees(
                sensorOrientationDegrees = sensor,
                displayRotationDegrees = displayDegrees,
                frontFacing = lens.facing == LensFacing.FRONT,
            ),
        )
    }

    private fun resolveKnownLens(requested: LensDescriptor): LensDescriptor {
        val known = mutableSnapshot.value.lenses.firstOrNull {
            it.identity.routingKey == requested.identity.routingKey
        }
        return known ?: requested
    }

    private fun ensureCanPreview(lens: LensDescriptor) {
        val excluded = lens.usability == LensUsability.SYSTEM_ONLY ||
            lens.usability == LensUsability.DEPTH_AUXILIARY ||
            lens.usability == LensUsability.INACCESSIBLE ||
            lens.usability == LensUsability.BROKEN ||
            lens.usability == LensUsability.DISABLED_BY_USER
        require(!excluded) { "Lens is not eligible for photographic preview" }
    }

    private fun policyFor(lens: LensDescriptor): CameraOperationPolicy = quirkRegistry.resolve(
        environment,
        CameraRouteFacts(
            cameraFingerprint = lens.fingerprint?.value,
            opensThroughLogicalParent = lens.identity.streamPhysicalCameraId != null,
            reportsRaw = lens.capabilities.flags.raw == CapabilitySupport.SUPPORTED,
            reportsBackwardCompatible = lens.capabilities.flags.backwardCompatible ==
                CapabilitySupport.SUPPORTED,
        ),
    ).policy

    private fun rememberLivePreviewFailure(
        lens: LensDescriptor,
        kind: ProbeFailureKind,
        policy: CameraOperationPolicy,
    ) {
        // Runtime permission can change immediately; it must never poison a camera route.
        if (kind == ProbeFailureKind.PERMISSION_DENIED) return
        val key = lens.identity.routingKey
        val prior = livePreviewFailures[key]
        livePreviewFailures[key] = LivePreviewFailure(
            count = (prior?.count ?: 0) + 1,
            kind = kind,
            threshold = policy.maxAutomaticFailures,
        )
        publishLivePreviewFailureMemory()
    }

    private fun publishLivePreviewFailureMemory() {
        mutableSnapshot.value = mutableSnapshot.value.copy(
            livePreviewFailureMemory = livePreviewFailures.mapValues { (_, failure) ->
                failure.snapshot()
            },
        )
    }

    private suspend fun isSurfaceAvailable(view: TextureView): Boolean =
        withContext(mainDispatcher) { view.isAvailable && view.surfaceTexture != null }

    private fun ensureOpen() = check(!closed.get()) { "CameraSessionController is closed" }

    private fun ensureOperationActive() {
        if (closed.get()) throw CancellationException("Camera controller closed")
        if (!lifecycleActive.get()) throw CancellationException("Camera lifecycle paused")
    }

    private suspend fun applyLifecycleRequest(active: Boolean) {
        val requestEpoch = lifecycleEpoch.incrementAndGet()
        lifecycleActive.set(active)
        if (!active) {
            // Preempt long Camera2 waits before joining the serialized state machine. The epoch
            // check below prevents this older request from overwriting a newer resume/pause.
            activeProbeJob.getAndSet(null)?.cancel(CancellationException("Camera lifecycle paused"))
            activeOpenJob.getAndSet(null)?.cancel(CancellationException("Camera lifecycle paused"))
        }
        operationMutex.withLock {
            if (closed.get() || requestEpoch != lifecycleEpoch.get() ||
                lifecycleActive.get() != active
            ) {
                return@withLock
            }
            resumed = active
            if (!active) {
                closePreviewLocked(updateState = true)
                if (requestEpoch != lifecycleEpoch.get() || lifecycleActive.get()) {
                    return@withLock
                }
                mutableState.value = CameraSessionState.Paused(selectedLens?.identity?.routingKey)
                return@withLock
            }

            val target = selectedLens
            val view = boundTextureView
            when {
                target == null -> mutableState.value = CameraSessionState.Ready(
                    mutableSnapshot.value.lenses.count { it.usability.isSelectable },
                )
                view == null || !isSurfaceAvailable(view) ->
                    mutableState.value = CameraSessionState.AwaitingSurface(target.identity.routingKey)
                else -> openPreviewLocked(target, switching = false)
            }
            if (requestEpoch != lifecycleEpoch.get() || !lifecycleActive.get()) {
                return@withLock
            }
        }
    }

    private fun setRecoverableError(error: CameraRuntimeError) {
        mutableSnapshot.value = mutableSnapshot.value.copy(
            activePreviewSize = null,
            lastError = error,
        )
        mutableState.value = stateFor(error)
    }

    private fun stateFor(error: CameraRuntimeError): CameraSessionState =
        if (error.kind == ProbeFailureKind.PERMISSION_DENIED) {
            CameraSessionState.PermissionRequired
        } else {
            CameraSessionState.ErrorRecoverable(error)
        }

    private fun Throwable.runtimeError(lens: LensDescriptor?): CameraRuntimeError {
        val cameraError = when (this) {
            is TimeoutCancellationException -> CameraOperationException(
                ProbeFailureKind.TIMEOUT,
                "Camera operation timed out",
                this,
            )
            else -> toCameraOperationException(ProbeFailureKind.UNKNOWN)
        }
        return CameraRuntimeError(
            kind = cameraError.failureKind,
            detail = cameraError.message.orEmpty().take(160),
            routingKey = lens?.identity?.routingKey,
        )
    }

    private companion object {
        fun defaultEnvironment() = CameraRuntimeEnvironment(
            manufacturer = Build.MANUFACTURER.orEmpty(),
            model = Build.MODEL.orEmpty(),
            sdkInt = Build.VERSION.SDK_INT,
            buildFingerprint = Build.FINGERPRINT.orEmpty(),
        )
    }

    private data class PreviewOrientation(
        val sensorOrientationDegrees: Int,
        val displayRotationDegrees: Int,
        val relativeRotationDegrees: Int,
    )

    private data class LivePreviewFailure(
        val count: Int,
        val kind: ProbeFailureKind,
        val threshold: Int,
    ) {
        fun snapshot() = ProbeFailureMemoryEntry(
            consecutiveFailures = count,
            lastFailureKind = kind,
            automaticRetrySuppressed = count >= threshold,
        )
    }

    private class PreviewFrameGate(
        private val expectedTexture: SurfaceTexture,
        private val baselineTimestampNs: Long,
    ) {
        private val captureStarted = AtomicBoolean(false)
        private val textureUpdated = AtomicBoolean(false)
        private val completion = CompletableDeferred<Unit>()

        fun markCaptureStarted() {
            captureStarted.set(true)
            completeWhenReady()
        }

        fun markTextureUpdated(texture: SurfaceTexture) {
            if (texture !== expectedTexture) return
            val timestamp = runCatching { texture.timestamp }.getOrNull() ?: return
            if (timestamp == baselineTimestampNs) return
            textureUpdated.set(true)
            completeWhenReady()
        }

        fun fail(error: Throwable) {
            completion.completeExceptionally(error)
        }

        suspend fun awaitFrame() = completion.await()

        private fun completeWhenReady() {
            if (captureStarted.get() && textureUpdated.get()) completion.complete(Unit)
        }
    }
}
