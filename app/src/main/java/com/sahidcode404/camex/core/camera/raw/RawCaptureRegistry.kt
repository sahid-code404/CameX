package com.sahidcode404.camex.core.camera.raw

import android.content.Context
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.media.ImageReader
import android.os.Handler
import android.util.Size
import com.sahidcode404.camex.core.model.Size2D
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class PreparedRawOutput internal constructor(
    val routingKey: String,
    val openCameraId: String,
    val physicalCameraId: String?,
    val reader: ImageReader,
    val rawSize: Size2D,
    val availableRawSizes: List<Size2D>,
    val continuousPictureAf: Boolean,
    val transportGeneration: Long,
)

/**
 * Coordinates the extra RAW surface and one-shot capture on the session owned by
 * DefaultCameraSessionController. It never calls CameraManager.openCamera and never closes a
 * CameraDevice/CameraCaptureSession.
 */
object RawCaptureRegistry : RawCaptureController {
    private val mutableState = MutableStateFlow(RawCaptureState.Idle)
    override val rawCaptureState: StateFlow<RawCaptureState> = mutableState.asStateFlow()

    private val active = AtomicReference<ActiveRawSession?>(null)
    private val activeSelectionGeneration = AtomicLong(INVALID_GENERATION)
    private val activeSelectionRoutingKey = AtomicReference<String?>(null)
    private val transportGeneration = AtomicLong(0L)
    private val captureTokens = AtomicLong(0L)
    private val gate = RawCaptureGate()

    @Volatile
    private var cameraManager: CameraManager? = null

    @Volatile
    private var engine: RawCaptureEngine? = null

    fun initialize(context: Context) {
        if (cameraManager != null && engine != null) return
        synchronized(this) {
            if (cameraManager == null) {
                val appContext = context.applicationContext
                val manager = appContext.getSystemService(CameraManager::class.java)
                cameraManager = manager
                engine = RawCaptureEngine(manager, RawDngWriter(appContext))
            }
        }
    }

    internal fun prepareOutput(
        device: CameraDevice,
        physicalCameraId: String?,
        handler: Handler,
    ): PreparedRawOutput? {
        val manager = cameraManager ?: return null
        val route = routingKey(device.id, physicalCameraId)
        val characteristicsId = physicalCameraId ?: device.id
        val inspected = inspect(manager, characteristicsId, route)
        mutableState.value = RawCaptureState(
            phase = RawCapturePhase.IDLE,
            capability = inspected.capability,
            diagnostics = RawCaptureDiagnostics(
                rawSupported = inspected.capability.support,
                availableRawSizes = inspected.capability.availableSizes,
                selectedRawSize = inspected.capability.selectedSize,
            ),
        )
        val size = inspected.capability.selectedSize ?: return null
        if (!inspected.capability.canAttempt) return null

        val generation = transportGeneration.incrementAndGet()
        val reader = ImageReader.newInstance(
            size.width,
            size.height,
            ImageFormat.RAW_SENSOR,
            MAX_IMAGES,
        )
        return PreparedRawOutput(
            routingKey = route,
            openCameraId = device.id,
            physicalCameraId = physicalCameraId,
            reader = reader,
            rawSize = size,
            availableRawSizes = inspected.capability.availableSizes,
            continuousPictureAf = inspected.continuousPictureAf,
            transportGeneration = generation,
        )
    }

    internal fun attach(
        prepared: PreparedRawOutput,
        device: CameraDevice,
        session: android.hardware.camera2.CameraCaptureSession,
        handler: Handler,
    ) {
        val previous = active.getAndSet(
            ActiveRawSession(
                device = device,
                session = session,
                imageReader = prepared.reader,
                routingKey = prepared.routingKey,
                openCameraId = prepared.openCameraId,
                physicalCameraId = prepared.physicalCameraId,
                rawSize = prepared.rawSize,
                availableRawSizes = prepared.availableRawSizes,
                continuousPictureAf = prepared.continuousPictureAf,
                transportGeneration = prepared.transportGeneration,
                callbackHandler = handler,
            ),
        )
        previous?.imageReader?.let { runCatching { it.close() } }
        val priorCapability = mutableState.value.capability
        mutableState.value = RawCaptureState(
            phase = RawCapturePhase.IDLE,
            capability = priorCapability.copy(
                sessionReady = true,
                profileRoutingKey = prepared.routingKey,
                detail = priorCapability.detail,
            ),
            diagnostics = mutableState.value.diagnostics.copy(lastRawError = null),
        )
    }

    internal fun combinedSessionRejected(prepared: PreparedRawOutput, detail: String) {
        runCatching { prepared.reader.close() }
        transportGeneration.incrementAndGet()
        active.compareAndSet(active.get()?.takeIf { it.routingKey == prepared.routingKey }, null)
        val capability = mutableState.value.capability
        mutableState.value = RawCaptureState(
            phase = RawCapturePhase.IDLE,
            capability = capability.copy(
                sessionReady = false,
                profileRoutingKey = prepared.routingKey,
                detail = detail.take(160),
            ),
            diagnostics = mutableState.value.diagnostics.copy(lastRawError = detail.take(160)),
        )
    }

    internal fun onSessionClosing(session: android.hardware.camera2.CameraCaptureSession) {
        val current = active.get()
        if (current?.session !== session) return
        if (active.compareAndSet(current, null)) {
            transportGeneration.incrementAndGet()
            current.imageReader.setOnImageAvailableListener(null, null)
            runCatching { current.imageReader.close() }
            mutableState.value = mutableState.value.copy(
                phase = RawCapturePhase.IDLE,
                capability = mutableState.value.capability.copy(sessionReady = false),
            )
        }
    }

    override fun updateActiveSelection(selectionGeneration: Long?, routingKey: String?) {
        if (selectionGeneration == null || routingKey.isNullOrBlank()) {
            activeSelectionGeneration.set(INVALID_GENERATION)
            activeSelectionRoutingKey.set(null)
        } else {
            activeSelectionGeneration.set(selectionGeneration)
            activeSelectionRoutingKey.set(routingKey)
        }
    }

    override suspend fun captureRaw(request: RawCaptureRequest): RawCaptureResult {
        if (!gate.tryBegin()) {
            val diagnostics = mutableState.value.diagnostics.copy(lastRawError = "capture already in progress")
            return RawCaptureResult.Failed(
                reason = "RAW capture already in progress",
                structural = false,
                diagnostics = diagnostics,
            )
        }

        try {
            val current = active.get()
            val capability = mutableState.value.capability
            if (current == null || !capability.sessionReady) {
                val reason = capability.detail ?: when (capability.support) {
                    RawSupportState.UNSUPPORTED -> "Selected camera profile does not support RAW_SENSOR"
                    RawSupportState.UNKNOWN -> "RAW_SENSOR availability is unknown for this profile"
                    RawSupportState.SUPPORTED -> "RAW_SENSOR session is not available for this profile"
                }
                val diagnostics = mutableState.value.diagnostics.copy(lastRawError = reason)
                mutableState.value = mutableState.value.copy(
                    phase = RawCapturePhase.FAILED,
                    diagnostics = diagnostics,
                )
                return RawCaptureResult.Failed(
                    reason = reason,
                    structural = capability.canAttempt && !capability.sessionReady,
                    diagnostics = diagnostics,
                )
            }

            if (activeSelectionGeneration.get() != request.selectionGeneration ||
                activeSelectionRoutingKey.get() != current.routingKey
            ) {
                val diagnostics = mutableState.value.diagnostics.copy(lastRawError = "stale selection generation")
                mutableState.value = mutableState.value.copy(
                    phase = RawCapturePhase.FAILED,
                    diagnostics = diagnostics,
                )
                return RawCaptureResult.Failed(
                    reason = "Camera selection changed before RAW capture started",
                    structural = false,
                    diagnostics = diagnostics,
                )
            }

            val context = RawCaptureContext(
                selectionGeneration = request.selectionGeneration,
                canonicalFingerprint = request.canonicalFingerprint,
                profileFingerprint = request.profileFingerprint,
                routingKey = current.routingKey,
                openCameraId = current.openCameraId,
                streamPhysicalCameraId = current.physicalCameraId,
                rawSize = current.rawSize,
                captureToken = captureTokens.incrementAndGet(),
                transportGeneration = current.transportGeneration,
            )
            val initialDiagnostics = RawCaptureDiagnostics(
                context = context,
                rawSupported = capability.support,
                availableRawSizes = current.availableRawSizes,
                selectedRawSize = current.rawSize,
            )
            mutableState.value = RawCaptureState(
                phase = RawCapturePhase.CAPTURING,
                capability = capability,
                diagnostics = initialDiagnostics,
            )

            val captureEngine = engine ?: error("RAW capture engine is not initialized")
            val result = captureEngine.capture(
                active = current,
                context = context,
                timeoutMillis = CAPTURE_TIMEOUT_MILLIS,
                isStillCurrent = {
                    RawCaptureGenerationGuard.isCurrent(
                        requestedSelectionGeneration = request.selectionGeneration,
                        activeSelectionGeneration = activeSelectionGeneration.get()
                            .takeUnless { it == INVALID_GENERATION },
                        capturedTransportGeneration = current.transportGeneration,
                        activeTransportGeneration = transportGeneration.get(),
                        capturedRoutingKey = current.routingKey,
                        activeRoutingKey = activeSelectionRoutingKey.get(),
                    ) && active.get() === current
                },
                onSaving = { diagnostics ->
                    mutableState.value = mutableState.value.copy(
                        phase = RawCapturePhase.SAVING,
                        diagnostics = diagnostics,
                    )
                },
            )
            mutableState.value = when (result) {
                is RawCaptureResult.Saved -> mutableState.value.copy(
                    phase = RawCapturePhase.SAVED,
                    diagnostics = result.diagnostics,
                )
                is RawCaptureResult.Failed -> mutableState.value.copy(
                    phase = RawCapturePhase.FAILED,
                    diagnostics = result.diagnostics,
                )
            }
            return result
        } finally {
            gate.end()
        }
    }

    private data class Inspection(
        val capability: RawCapabilityInfo,
        val continuousPictureAf: Boolean,
    )

    private fun inspect(manager: CameraManager, cameraId: String, routingKey: String): Inspection {
        return try {
            val characteristics = manager.getCameraCharacteristics(cameraId)
            val capabilities = characteristics.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)
            val rawAdvertised = capabilities?.contains(
                CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_RAW,
            )
            val map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            val sizes = runCatching { map?.getOutputSizes(ImageFormat.RAW_SENSOR).orEmpty().toList() }
                .getOrDefault(emptyList())
                .filter { it.width > 0 && it.height > 0 }
                .distinctBy { it.width to it.height }
                .sortedByDescending(::area)
                .map { Size2D(it.width, it.height) }
            val support = when {
                rawAdvertised == true && sizes.isNotEmpty() -> RawSupportState.SUPPORTED
                rawAdvertised == false && sizes.isEmpty() -> RawSupportState.UNSUPPORTED
                else -> RawSupportState.UNKNOWN
            }
            val detail = when {
                rawAdvertised == true && sizes.isEmpty() ->
                    "RAW capability advertised but RAW_SENSOR sizes are absent"
                rawAdvertised == false && sizes.isNotEmpty() ->
                    "RAW_SENSOR sizes exist without the RAW capability bit"
                rawAdvertised == null -> "RAW capability metadata is unavailable"
                else -> null
            }
            val afModes = characteristics.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES).orEmpty()
            Inspection(
                capability = RawCapabilityInfo(
                    support = support,
                    availableSizes = sizes,
                    selectedSize = sizes.firstOrNull(),
                    sessionReady = false,
                    profileRoutingKey = routingKey,
                    detail = detail,
                ),
                continuousPictureAf = afModes.contains(CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_PICTURE),
            )
        } catch (error: Throwable) {
            if (error is VirtualMachineError || error is ThreadDeath) throw error
            Inspection(
                capability = RawCapabilityInfo(
                    support = RawSupportState.UNKNOWN,
                    profileRoutingKey = routingKey,
                    detail = "RAW metadata query failed: ${error.javaClass.simpleName}",
                ),
                continuousPictureAf = false,
            )
        }
    }

    private fun routingKey(openCameraId: String, physicalCameraId: String?): String =
        if (physicalCameraId.isNullOrBlank()) openCameraId else "$openCameraId/$physicalCameraId"

    private fun area(size: Size): Long = size.width.toLong() * size.height.toLong()

    private const val INVALID_GENERATION = -1L
    private const val MAX_IMAGES = 2
    private const val CAPTURE_TIMEOUT_MILLIS = 5_000L
}
