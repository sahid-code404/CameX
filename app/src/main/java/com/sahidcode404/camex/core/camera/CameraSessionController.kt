package com.sahidcode404.camex.core.camera

import android.content.Context
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureFailure
import android.hardware.camera2.CaptureRequest
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Range
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
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

sealed interface CameraSessionState {
    data object Idle : CameraSessionState
    data object PermissionRequired : CameraSessionState
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

sealed interface CameraSessionEvent {
    data class CameraOpened(val routingKey: String) : CameraSessionEvent
    data class SessionConfigured(val routingKey: String) : CameraSessionEvent
    data class PreviewVerified(val routingKey: String) : CameraSessionEvent
    data class PreviewFailed(
        val routingKey: String,
        val kind: ProbeFailureKind,
        val structural: Boolean,
        val detail: String,
    ) : CameraSessionEvent
}

data class CameraRuntimeError(
    val kind: ProbeFailureKind,
    val detail: String,
    val routingKey: String? = null,
)

data class CameraFailureMemoryEntry(
    val consecutiveFailures: Int,
    val lastFailureKind: ProbeFailureKind,
    val automaticRetrySuppressed: Boolean,
)

data class CameraRuntimeSnapshot(
    val lenses: List<LensDescriptor> = emptyList(),
    val selectedRoutingKey: String? = null,
    val activePreviewSize: Size2D? = null,
    val activePreviewFormat: StreamFormat? = null,
    val lastError: CameraRuntimeError? = null,
    val livePreviewFailureMemory: Map<String, CameraFailureMemoryEntry> = emptyMap(),
) {
    val selectedLens: LensDescriptor?
        get() = selectedRoutingKey?.let { key ->
            lenses.firstOrNull { it.identity.routingKey == key }
        }

    companion object {
        val Empty = CameraRuntimeSnapshot()
    }
}

interface CameraSessionController : Closeable {
    val state: StateFlow<CameraSessionState>
    val snapshot: StateFlow<CameraRuntimeSnapshot>
    val sessionEvents: SharedFlow<CameraSessionEvent>

    suspend fun updateAvailableLenses(lenses: List<LensDescriptor>)
    suspend fun clearTransientFailureMemory(routingKey: String? = null)
    suspend fun bindPreview(textureView: TextureView)
    suspend fun unbindPreview()
    suspend fun open(lens: LensDescriptor)
    suspend fun switchTo(lens: LensDescriptor)
    suspend fun pause()
    suspend fun resume()
}

/**
 * Serialized Camera2 lifecycle owner. PRIVATE/SurfaceTexture always remains the display transport.
 * A user-selected YUV_420_888 viewfinder creates one additional bounded ImageReader stream on this
 * same CameraDevice/session; unsupported combinations fall back to PRIVATE without poisoning the
 * optical lens/profile. No camera ID, device, vendor, resolution or FPS is hardcoded here.
 */
class DefaultCameraSessionController(
    context: Context,
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
    private val activeAuxiliaryViewfinder = AtomicReference<DrainingYuvViewfinderStream?>(null)
    private val activeOpenJob = AtomicReference<Job?>(null)
    private val livePreviewFailures = mutableMapOf<String, LivePreviewFailure>()
    private val pendingTextureFrame = AtomicReference<PreviewFrameGate?>(null)
    private val lifecycleActive = AtomicBoolean(true)
    private val lifecycleEpoch = AtomicLong(0L)
    private val mutableState = MutableStateFlow<CameraSessionState>(CameraSessionState.Idle)
    private val mutableSnapshot = MutableStateFlow(CameraRuntimeSnapshot.Empty)
    private val mutableSessionEvents = MutableSharedFlow<CameraSessionEvent>(
        replay = 0,
        extraBufferCapacity = SESSION_EVENT_BUFFER_CAPACITY,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    override val state: StateFlow<CameraSessionState> = mutableState.asStateFlow()
    override val snapshot: StateFlow<CameraRuntimeSnapshot> = mutableSnapshot.asStateFlow()
    override val sessionEvents: SharedFlow<CameraSessionEvent> = mutableSessionEvents.asSharedFlow()

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

    override suspend fun updateAvailableLenses(lenses: List<LensDescriptor>) =
        operationMutex.withLock {
            ensureOpen()
            val mirroredLenses = lenses.toList()
            val selectedKey = mutableSnapshot.value.selectedRoutingKey
                ?: selectedLens?.identity?.routingKey
            val refreshedSelected = selectedKey?.let { key ->
                mirroredLenses.firstOrNull { it.identity.routingKey == key }
            }
            if (refreshedSelected != null) selectedLens = refreshedSelected
            mutableSnapshot.value = mutableSnapshot.value.copy(
                lenses = mirroredLenses,
                selectedRoutingKey = selectedKey,
            )
            mutableState.value = when (mutableState.value) {
                CameraSessionState.Idle,
                is CameraSessionState.Ready,
                -> CameraSessionState.Ready(mirroredLenses.count { it.usability.isSelectable })
                else -> mutableState.value
            }
        }

    override suspend fun clearTransientFailureMemory(routingKey: String?) =
        operationMutex.withLock {
            ensureOpen()
            if (routingKey == null) livePreviewFailures.clear() else livePreviewFailures.remove(routingKey)
            publishLivePreviewFailureMemory()
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
        activeOpenJob.getAndSet(null)?.cancel(CancellationException("Camera controller closed"))
        pendingTextureFrame.getAndSet(null)?.fail(
            CancellationException("Camera controller closed before its first preview frame"),
        )
        displayManager.unregisterDisplayListener(displayListener)
        scope.cancel()
        activeSession.getAndSet(null)?.close()
        activeAuxiliaryViewfinder.getAndSet(null)?.close()
        activeSurface.getAndSet(null)?.let { runCatching { it.release() } }
        activeDevice.getAndSet(null)?.close()
        boundTextureView?.let { view ->
            view.post {
                if (view.surfaceTextureListener === surfaceListener) view.surfaceTextureListener = null
                TexturePreviewTransform.reset(view)
            }
        }
        boundTextureView = null
        activeLens = null
        activePreviewConfiguration = null
        callbackThread.close()
        mutableSnapshot.value = mutableSnapshot.value.copy(
            activePreviewSize = null,
            activePreviewFormat = null,
        )
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
        if (rememberedFailure != null && rememberedFailure.count >= policy.maxAutomaticFailures) {
            closePreviewLocked(updateState = false)
            return setRecoverableError(
                CameraRuntimeError(
                    kind = rememberedFailure.kind,
                    detail = "Automatic live preview retry suppressed after repeated failures",
                    routingKey = lens.identity.routingKey,
                ),
                publishValidationFailure = false,
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
        ) ?: return setRecoverableError(
            CameraRuntimeError(
                ProbeFailureKind.INVALID_METADATA,
                "No TextureView-compatible preview size was reported",
                lens.identity.routingKey,
            ),
        )
        val auxiliaryConfiguration = selectAuxiliaryViewfinderConfiguration(
            lens = lens,
            viewDimensions = dimensions,
            rotationDegrees = orientation.relativeRotationDegrees,
            policy = policy,
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
        var auxiliary: DrainingYuvViewfinderStream? = null
        var frameGate: PreviewFrameGate? = null
        try {
            ensureOperationActive()
            mutableState.value = if (switching || fromKey != null) {
                CameraSessionState.Switching(fromKey, lens.identity.routingKey)
            } else {
                CameraSessionState.Opening(lens.identity.routingKey)
            }
            val textureState = withContext(mainDispatcher) {
                TexturePreviewTransform.reset(view)
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
            activeDevice.set(device)
            mutableSessionEvents.tryEmit(CameraSessionEvent.CameraOpened(lens.identity.routingKey))
            ensureOperationActive()

            auxiliary = auxiliaryConfiguration?.let { selected ->
                DrainingYuvViewfinderStream.create(selected.size, callbackThread.handler)
            }
            session = configureViewfinderSessionWithFallback(
                device = device,
                displaySurface = surface,
                physicalCameraId = lens.identity.streamPhysicalCameraId,
                auxiliary = auxiliary,
                policy = policy,
            ).also { result ->
                if (!result.auxiliaryAccepted) {
                    auxiliary?.close()
                    auxiliary = null
                }
            }.lease
            activeAuxiliaryViewfinder.set(auxiliary)
            activeSession.set(session)
            mutableSessionEvents.tryEmit(CameraSessionEvent.SessionConfigured(lens.identity.routingKey))
            ensureOperationActive()

            val gate = PreviewFrameGate(
                expectedTexture = texture,
                baselineTimestampNs = textureState.second,
            )
            frameGate = gate
            pendingTextureFrame.set(gate)
            val activeFpsDuration = auxiliaryConfiguration
                ?.takeIf { auxiliary != null }
                ?.minFrameDurationNs
                ?.let { yuvDuration ->
                    listOfNotNull(configuration.minFrameDurationNs, yuvDuration).maxOrNull()
                }
                ?: configuration.minFrameDurationNs
            val previewFpsRange = PreviewFpsSelector.selectForStream(
                ranges = lens.capabilities.previewFpsRanges,
                minimumFrameDurationNanos = activeFpsDuration,
            )
            val request = device.device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                addTarget(surface)
                auxiliary?.surface?.let(::addTarget)
                set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)
                previewFpsRange?.let { fps ->
                    set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, Range(fps.min, fps.max))
                }
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
                        request: CaptureRequest,
                        timestamp: Long,
                        frameNumber: Long,
                    ) {
                        gate.markCaptureStarted()
                    }

                    override fun onCaptureFailed(
                        session: CameraCaptureSession,
                        request: CaptureRequest,
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
            withTimeout(policy.firstFrameTimeoutMillis) { gate.awaitFrame() }
            mutableSessionEvents.tryEmit(CameraSessionEvent.PreviewVerified(lens.identity.routingKey))
            ensureOperationActive()
            activeLens = lens
            selectedLens = lens
            activePreviewConfiguration = configuration
            mutableSnapshot.value = mutableSnapshot.value.copy(
                selectedRoutingKey = lens.identity.routingKey,
                activePreviewSize = configuration.size,
                activePreviewFormat = if (auxiliary != null) {
                    StreamFormat.YUV_420_888
                } else {
                    StreamFormat.PRIVATE
                },
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
            auxiliary?.close()
            activeAuxiliaryViewfinder.compareAndSet(auxiliary, null)
            surface?.let { runCatching { it.release() } }
            activeSurface.compareAndSet(surface, null)
            device?.closeAndAwait(policy.closeSettleTimeoutMillis)
            activeDevice.compareAndSet(device, null)
            activeLens = null
            activePreviewConfiguration = null
            rememberLivePreviewFailure(lens, ProbeFailureKind.TIMEOUT, policy)
            if (!closed.get() && lifecycleActive.get()) setRecoverableError(error.runtimeError(lens))
        } catch (error: CancellationException) {
            session?.close()
            activeSession.compareAndSet(session, null)
            auxiliary?.close()
            activeAuxiliaryViewfinder.compareAndSet(auxiliary, null)
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
            auxiliary?.close()
            activeAuxiliaryViewfinder.compareAndSet(auxiliary, null)
            surface?.let { runCatching { it.release() } }
            activeSurface.compareAndSet(surface, null)
            device?.closeAndAwait(policy.closeSettleTimeoutMillis)
            activeDevice.compareAndSet(device, null)
            activeLens = null
            activePreviewConfiguration = null
            if (closed.get() || !lifecycleActive.get()) return
            val runtimeError = error.runtimeError(lens)
            rememberLivePreviewFailure(lens, runtimeError.kind, policy)
            setRecoverableError(runtimeError)
        } finally {
            frameGate?.let { pendingTextureFrame.compareAndSet(it, null) }
            activeOpenJob.compareAndSet(openingJob, null)
        }
    }

    /**
     * An optional YUV stream is a user override, not a structural lens requirement. If the HAL
     * rejects the PRIVATE+YUV combination, retry PRIVATE once inside the same camera/profile and
     * keep the optical route healthy.
     */
    private suspend fun configureViewfinderSessionWithFallback(
        device: OpenCameraLease,
        displaySurface: Surface,
        physicalCameraId: String?,
        auxiliary: DrainingYuvViewfinderStream?,
        policy: CameraOperationPolicy,
    ): ConfiguredViewfinderSession {
        if (auxiliary == null) {
            return ConfiguredViewfinderSession(
                lease = device.device.awaitCaptureSession(
                    surface = displaySurface,
                    physicalCameraId = physicalCameraId,
                    handler = callbackThread.handler,
                    timeoutMillis = policy.sessionTimeoutMillis,
                ),
                auxiliaryAccepted = false,
            )
        }
        try {
            return ConfiguredViewfinderSession(
                lease = device.device.awaitCaptureSession(
                    surface = displaySurface,
                    physicalCameraId = physicalCameraId,
                    additionalPreviewSurface = auxiliary.surface,
                    handler = callbackThread.handler,
                    timeoutMillis = policy.sessionTimeoutMillis,
                ),
                auxiliaryAccepted = true,
            )
        } catch (timeout: TimeoutCancellationException) {
            auxiliary.close()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            if (error is VirtualMachineError || error is ThreadDeath) throw error
            auxiliary.close()
        }
        return ConfiguredViewfinderSession(
            lease = device.device.awaitCaptureSession(
                surface = displaySurface,
                physicalCameraId = physicalCameraId,
                handler = callbackThread.handler,
                timeoutMillis = policy.sessionTimeoutMillis,
            ),
            auxiliaryAccepted = false,
        )
    }

    private suspend fun closePreviewLocked(updateState: Boolean) {
        val closingKey = activeLens?.identity?.routingKey
        if (updateState) mutableState.value = CameraSessionState.Closing(closingKey)
        pendingTextureFrame.getAndSet(null)?.fail(
            CancellationException("Preview closed before its first frame"),
        )
        val policy = activeLens?.let(::policyFor) ?: CameraOperationPolicy()
        activeSession.getAndSet(null)?.closeAndAwait(policy.closeSettleTimeoutMillis)
        activeAuxiliaryViewfinder.getAndSet(null)?.close()
        activeSurface.getAndSet(null)?.let { runCatching { it.release() } }
        activeDevice.getAndSet(null)?.closeAndAwait(policy.closeSettleTimeoutMillis)
        activeLens = null
        activePreviewConfiguration = null
        mutableSnapshot.value = mutableSnapshot.value.copy(
            activePreviewSize = null,
            activePreviewFormat = null,
        )
        boundTextureView?.let { view ->
            withContext(mainDispatcher) { TexturePreviewTransform.reset(view) }
        }
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
            !resumed || !lifecycleActive.get() -> CameraSessionState.Paused(target?.identity?.routingKey)
            target != null -> CameraSessionState.AwaitingSurface(target.identity.routingKey)
            else -> CameraSessionState.Ready(mutableSnapshot.value.lenses.count { it.usability.isSelectable })
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
                        error.message.orEmpty().take(MAX_VALIDATION_DETAIL_LENGTH),
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
    ): StreamConfiguration? = selectConfiguration(
        candidates = lens.capabilities.configurations(StreamFormat.PRIVATE)
            .filter { !it.maximumResolution && it.size.isValid },
        lens = lens,
        viewDimensions = viewDimensions,
        rotationDegrees = rotationDegrees,
        policy = policy,
    )

    private fun selectAuxiliaryViewfinderConfiguration(
        lens: LensDescriptor,
        viewDimensions: Pair<Int, Int>,
        rotationDegrees: Int,
        policy: CameraOperationPolicy,
    ): StreamConfiguration? {
        if (lens.previewStreamFormat != StreamFormat.YUV_420_888) return null
        return selectConfiguration(
            candidates = lens.capabilities.configurations(StreamFormat.YUV_420_888)
                .filter { !it.maximumResolution && it.size.isValid },
            lens = lens,
            viewDimensions = viewDimensions,
            rotationDegrees = rotationDegrees,
            policy = policy,
        )
    }

    private fun selectConfiguration(
        candidates: List<StreamConfiguration>,
        lens: LensDescriptor,
        viewDimensions: Pair<Int, Int>,
        rotationDegrees: Int,
        policy: CameraOperationPolicy,
    ): StreamConfiguration? {
        if (candidates.isEmpty()) return null
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
                preferredMinimumFps = PreviewFpsSelector.preferredTargetFps(
                    lens.capabilities.previewFpsRanges,
                ),
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
            reportsBackwardCompatible = lens.capabilities.flags.backwardCompatible == CapabilitySupport.SUPPORTED,
        ),
    ).policy

    private fun rememberLivePreviewFailure(
        lens: LensDescriptor,
        kind: ProbeFailureKind,
        policy: CameraOperationPolicy,
    ) {
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
            activeOpenJob.getAndSet(null)?.cancel(CancellationException("Camera lifecycle paused"))
        }
        operationMutex.withLock {
            if (closed.get() || requestEpoch != lifecycleEpoch.get() || lifecycleActive.get() != active) {
                return@withLock
            }
            resumed = active
            if (!active) {
                closePreviewLocked(updateState = true)
                if (requestEpoch != lifecycleEpoch.get() || lifecycleActive.get()) return@withLock
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
            if (requestEpoch != lifecycleEpoch.get() || !lifecycleActive.get()) return@withLock
        }
    }

    private fun setRecoverableError(
        error: CameraRuntimeError,
        publishValidationFailure: Boolean = true,
    ) {
        mutableSnapshot.value = mutableSnapshot.value.copy(
            activePreviewSize = null,
            activePreviewFormat = null,
            lastError = error,
        )
        mutableState.value = stateFor(error)
        val routingKey = error.routingKey
        if (publishValidationFailure && routingKey != null) {
            mutableSessionEvents.tryEmit(
                CameraSessionEvent.PreviewFailed(
                    routingKey = routingKey,
                    kind = error.kind,
                    structural = error.kind.isStructuralPreviewFailure(),
                    detail = sanitizeValidationDetail(error.detail),
                ),
            )
        }
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
            detail = cameraError.message.orEmpty().take(MAX_VALIDATION_DETAIL_LENGTH),
            routingKey = lens?.identity?.routingKey,
        )
    }

    private companion object {
        const val SESSION_EVENT_BUFFER_CAPACITY = 32
        const val MAX_VALIDATION_DETAIL_LENGTH = 160

        fun defaultEnvironment() = CameraRuntimeEnvironment(
            manufacturer = Build.MANUFACTURER.orEmpty(),
            model = Build.MODEL.orEmpty(),
            sdkInt = Build.VERSION.SDK_INT,
            buildFingerprint = Build.FINGERPRINT.orEmpty(),
        )

        fun ProbeFailureKind.isStructuralPreviewFailure(): Boolean =
            this == ProbeFailureKind.INVALID_METADATA ||
                this == ProbeFailureKind.SESSION_CONFIGURATION

        fun sanitizeValidationDetail(detail: String): String = detail
            .map { character -> if (character.isISOControl()) ' ' else character }
            .joinToString(separator = "")
            .trim()
            .take(MAX_VALIDATION_DETAIL_LENGTH)
    }

    private data class PreviewOrientation(
        val sensorOrientationDegrees: Int,
        val displayRotationDegrees: Int,
        val relativeRotationDegrees: Int,
    )

    private data class ConfiguredViewfinderSession(
        val lease: CaptureSessionLease,
        val auxiliaryAccepted: Boolean,
    )

    private data class LivePreviewFailure(
        val count: Int,
        val kind: ProbeFailureKind,
        val threshold: Int,
    ) {
        fun snapshot() = CameraFailureMemoryEntry(
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
