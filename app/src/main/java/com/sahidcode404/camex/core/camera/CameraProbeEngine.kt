package com.sahidcode404.camex.core.camera

import android.content.Context
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureRequest
import android.media.ImageReader
import android.os.Build
import android.os.SystemClock
import com.sahidcode404.camex.core.logic.LensUsabilityClassifier
import com.sahidcode404.camex.core.logic.ProbeStateMachine
import com.sahidcode404.camex.core.model.CapabilitySupport
import com.sahidcode404.camex.core.model.LensDescriptor
import com.sahidcode404.camex.core.model.LensProbeResult
import com.sahidcode404.camex.core.model.LensUsability
import com.sahidcode404.camex.core.model.ProbeFailureKind
import com.sahidcode404.camex.core.model.ProbeOutcome
import com.sahidcode404.camex.core.model.ProbeStage
import com.sahidcode404.camex.core.model.ProbeStageResult
import com.sahidcode404.camex.core.model.StreamConfiguration
import com.sahidcode404.camex.core.model.StreamFormat
import java.io.Closeable
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout

data class ProbeFailureMemoryEntry(
    val consecutiveFailures: Int,
    val lastFailureKind: ProbeFailureKind,
    val automaticRetrySuppressed: Boolean,
)

data class CameraProbeSnapshot(
    val lenses: List<LensDescriptor>,
    val resultsByRoutingKey: Map<String, LensProbeResult>,
    val failureMemory: Map<String, ProbeFailureMemoryEntry>,
    val openFailureMemory: Map<String, ProbeFailureMemoryEntry> = emptyMap(),
    val rawFailureMemory: Map<String, ProbeFailureMemoryEntry> = emptyMap(),
) {
    companion object {
        val Empty = CameraProbeSnapshot(emptyList(), emptyMap(), emptyMap())
    }
}

/**
 * A conservative one-at-a-time Camera2 probe.
 *
 * It opens only [LensDescriptor.identity.openCameraId]. Physical-only lenses are selected through
 * OutputConfiguration on API 28+, never by attempting to open their physical ID directly.
 */
class CameraProbeEngine(
    private val cameraManager: CameraManager,
    private val quirkRegistry: CameraQuirkRegistry = CameraQuirkRegistry(),
    private val environment: CameraRuntimeEnvironment = defaultEnvironment(),
) : Closeable {
    constructor(
        context: Context,
        quirkRegistry: CameraQuirkRegistry = CameraQuirkRegistry(),
    ) : this(
        context.applicationContext.getSystemService(CameraManager::class.java),
        quirkRegistry,
        defaultEnvironment(),
    )

    private val callbackThread = CameraCallbackThread("camex-camera-probe")
    private val probeMutex = Mutex()
    private val closed = AtomicBoolean(false)
    private val activeDevice = AtomicReference<OpenCameraLease?>(null)
    private val activeSession = AtomicReference<CaptureSessionLease?>(null)
    private val activeReader = AtomicReference<ImageReader?>(null)
    private val failureCounts = linkedMapOf<String, MutableFailure>()
    private val openFailureCounts = linkedMapOf<String, MutableFailure>()
    private val rawFailureCounts = linkedMapOf<String, MutableFailure>()
    private val attempts = mutableMapOf<String, Int>()

    suspend fun probeSequentially(lenses: List<LensDescriptor>): CameraProbeSnapshot =
        probeMutex.withLock {
            check(!closed.get()) { "CameraProbeEngine is closed" }
            val probed = ArrayList<LensDescriptor>(lenses.size)
            val results = linkedMapOf<String, LensProbeResult>()
            lenses.forEach { lens ->
                val policy = policyFor(lens)
                val result = probeOneLocked(lens, policy)
                val updated = lens.copy(
                    probeResult = result,
                    usability = LensUsabilityClassifier.classify(lens.capabilities, result),
                )
                probed += updated
                results[lens.identity.routingKey] = result
            }
            CameraProbeSnapshot(
                lenses = probed,
                resultsByRoutingKey = results,
                failureMemory = failureMemorySnapshot(),
                openFailureMemory = openFailureMemorySnapshot(),
                rawFailureMemory = rawFailureMemorySnapshot(),
            )
        }

    suspend fun probe(lens: LensDescriptor): LensDescriptor = probeMutex.withLock {
        check(!closed.get()) { "CameraProbeEngine is closed" }
        val result = probeOneLocked(lens, policyFor(lens))
        lens.copy(
            probeResult = result,
            usability = LensUsabilityClassifier.classify(lens.capabilities, result),
        )
    }

    /** Clears temporary session-local suppression so diagnostics can explicitly retry. */
    suspend fun clearFailureMemory(
        routingKey: String? = null,
        openCameraId: String? = null,
    ) = probeMutex.withLock {
        if (routingKey == null && openCameraId == null) {
            failureCounts.clear()
            openFailureCounts.clear()
            rawFailureCounts.clear()
        } else {
            routingKey?.let {
                failureCounts.remove(it)
                rawFailureCounts.remove(it)
            }
            openCameraId?.let(openFailureCounts::remove)
        }
    }

    fun failureMemorySnapshot(): Map<String, ProbeFailureMemoryEntry> = synchronized(failureCounts) {
        failureCounts.mapValues { (_, value) -> value.snapshot() }
    }

    fun openFailureMemorySnapshot(): Map<String, ProbeFailureMemoryEntry> =
        synchronized(openFailureCounts) {
            openFailureCounts.mapValues { (_, value) -> value.snapshot() }
        }

    fun rawFailureMemorySnapshot(): Map<String, ProbeFailureMemoryEntry> =
        synchronized(rawFailureCounts) {
            rawFailureCounts.mapValues { (_, value) -> value.snapshot() }
        }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        activeSession.getAndSet(null)?.close()
        activeReader.getAndSet(null)?.let { runCatching { it.close() } }
        activeDevice.getAndSet(null)?.close()
        callbackThread.close()
    }

    private suspend fun probeOneLocked(
        lens: LensDescriptor,
        policy: CameraOperationPolicy,
    ): LensProbeResult {
        val routingKey = lens.identity.routingKey
        val attempt = (attempts[routingKey] ?: 0) + 1
        attempts[routingKey] = attempt
        var result = LensProbeResult(attempt = attempt)
        result = result.add(success(ProbeStage.DISCOVERED))

        if (lens.usability == LensUsability.INACCESSIBLE) {
            return result.add(
                ProbeStageResult(
                    stage = ProbeStage.METADATA_VALID,
                    outcome = ProbeOutcome.SKIPPED,
                    detail = "Camera metadata is inaccessible",
                    failureKind = ProbeFailureKind.ACCESS_DENIED,
                ),
            )
        }

        val rememberedFailure = synchronized(failureCounts) { failureCounts[routingKey] }
        if (rememberedFailure != null && rememberedFailure.count >= policy.maxAutomaticFailures) {
            return result.add(
                ProbeStageResult(
                    stage = ProbeStage.METADATA_VALID,
                    outcome = ProbeOutcome.SKIPPED,
                    detail = "Automatic retry suppressed after repeated session failures",
                    failureKind = rememberedFailure.kind,
                ),
            )
        }
        val rememberedOpenFailure = synchronized(openFailureCounts) {
            openFailureCounts[lens.identity.openCameraId]
        }
        if (rememberedOpenFailure != null &&
            rememberedOpenFailure.count >= policy.maxAutomaticFailures
        ) {
            return result.add(
                ProbeStageResult(
                    stage = ProbeStage.METADATA_VALID,
                    outcome = ProbeOutcome.SKIPPED,
                    detail = "Logical/public camera open retry suppressed after repeated failures",
                    failureKind = rememberedOpenFailure.kind,
                ),
            )
        }

        val metadataStart = SystemClock.elapsedRealtime()
        val previewConfiguration = choosePreviewConfiguration(lens, policy)
        val metadataValid = lens.identity.openCameraId.isNotBlank() &&
            lens.capabilities.streamConfigurations != null &&
            previewConfiguration != null
        result = if (metadataValid) {
            result.add(success(ProbeStage.METADATA_VALID, elapsed(metadataStart)))
        } else {
            val failure = failure(
                ProbeStage.METADATA_VALID,
                ProbeOutcome.FAILURE,
                ProbeFailureKind.INVALID_METADATA,
                "No valid processed preview stream metadata",
                elapsed(metadataStart),
            )
            rememberFailure(
                routingKey = routingKey,
                openCameraId = lens.identity.openCameraId,
                stage = ProbeStage.METADATA_VALID,
                kind = failure.failureKind!!,
                policy = policy,
            )
            return result.add(failure)
        }

        if (lens.capabilities.flags.systemCamera == CapabilitySupport.SUPPORTED) {
            return result.add(
                failure(
                    ProbeStage.SESSION_CONFIGURATION_SUPPORTED,
                    ProbeOutcome.UNSUPPORTED,
                    ProbeFailureKind.SYSTEM_RESTRICTED,
                    "System camera is not available to ordinary applications",
                ),
            )
        }
        if (lens.capabilities.flags.depthOutput == CapabilitySupport.SUPPORTED &&
            lens.capabilities.flags.backwardCompatible == CapabilitySupport.UNSUPPORTED
        ) {
            return result.add(
                ProbeStageResult(
                    stage = ProbeStage.SESSION_CONFIGURATION_SUPPORTED,
                    outcome = ProbeOutcome.UNSUPPORTED,
                    detail = "Depth-only endpoint is excluded from photographic preview",
                ),
            )
        }
        val physicalId = lens.identity.streamPhysicalCameraId
        if (physicalId != null &&
            (Build.VERSION.SDK_INT < Build.VERSION_CODES.P || !policy.allowPhysicalOutputRouting)
        ) {
            return result.add(
                ProbeStageResult(
                    stage = ProbeStage.SESSION_CONFIGURATION_SUPPORTED,
                    outcome = ProbeOutcome.UNSUPPORTED,
                    detail = "Physical stream routing is unavailable",
                ),
            )
        }
        result = result.add(success(ProbeStage.SESSION_CONFIGURATION_SUPPORTED))

        var device: OpenCameraLease? = null
        var session: CaptureSessionLease? = null
        var reader: ImageReader? = null
        try {
            val openStart = SystemClock.elapsedRealtime()
            device = cameraManager.awaitOpenCamera(
                cameraId = lens.identity.openCameraId,
                handler = callbackThread.handler,
                timeoutMillis = policy.openTimeoutMillis,
            )
            activeDevice.set(device)
            synchronized(openFailureCounts) {
                openFailureCounts.remove(lens.identity.openCameraId)
            }
            result = result.add(success(ProbeStage.OPEN_SUCCESS, elapsed(openStart)))

            val previewStart = SystemClock.elapsedRealtime()
            reader = createReader(previewConfiguration)
            activeReader.set(reader)
            val firstFrame = CompletableDeferred<Unit>()
            reader.setOnImageAvailableListener({ source ->
                runCatching { source.acquireLatestImage()?.close() }
                firstFrame.complete(Unit)
            }, callbackThread.handler)
            session = device.device.awaitCaptureSession(
                surface = reader.surface,
                physicalCameraId = physicalId,
                handler = callbackThread.handler,
                timeoutMillis = policy.sessionTimeoutMillis,
            )
            activeSession.set(session)
            val request = device.device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                addTarget(reader.surface)
                set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)
                if (lens.capabilities.afModes.orEmpty().contains("CONTINUOUS_PICTURE")) {
                    set(
                        CaptureRequest.CONTROL_AF_MODE,
                        CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_PICTURE,
                    )
                }
            }.build()
            session.session.setRepeatingRequest(
                request,
                object : CameraCaptureSession.CaptureCallback() {},
                callbackThread.handler,
            )
            withTimeout(policy.firstFrameTimeoutMillis) { firstFrame.await() }
            result = result.add(success(ProbeStage.PREVIEW_SUCCESS, elapsed(previewStart)))

            session.closeAndAwait(policy.closeSettleTimeoutMillis)
            activeSession.compareAndSet(session, null)
            session = null
            reader.close()
            activeReader.compareAndSet(reader, null)
            reader = null

            result = probeRawConfiguration(
                current = result,
                lens = lens,
                device = device,
                policy = policy,
            )
            result = result.add(success(ProbeStage.USABLE))
            synchronized(failureCounts) { failureCounts.remove(routingKey) }
            return result
        } catch (error: TimeoutCancellationException) {
            val stage = when {
                device == null -> ProbeStage.OPEN_SUCCESS
                else -> ProbeStage.PREVIEW_SUCCESS
            }
            val failed = failure(
                stage,
                ProbeOutcome.TIMEOUT,
                ProbeFailureKind.TIMEOUT,
                "${stage.name.lowercase()} timed out",
            )
            rememberFailure(
                routingKey = routingKey,
                openCameraId = lens.identity.openCameraId,
                stage = stage,
                kind = failed.failureKind!!,
                policy = policy,
            )
            return appendIfLegal(result, failed)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            if (error is VirtualMachineError || error is ThreadDeath) throw error
            val cameraError = error.toCameraOperationException(
                if (device == null) ProbeFailureKind.DEVICE_ERROR
                else ProbeFailureKind.SESSION_CONFIGURATION,
            )
            val stage = if (device == null) ProbeStage.OPEN_SUCCESS else ProbeStage.PREVIEW_SUCCESS
            val failed = failure(
                stage,
                ProbeOutcome.EXCEPTION,
                cameraError.failureKind,
                cameraError.message ?: "Camera operation failed",
            )
            rememberFailure(
                routingKey = routingKey,
                openCameraId = lens.identity.openCameraId,
                stage = stage,
                kind = failed.failureKind!!,
                policy = policy,
            )
            return appendIfLegal(result, failed)
        } finally {
            session?.closeAndAwait(policy.closeSettleTimeoutMillis)
            activeSession.compareAndSet(session, null)
            reader?.let { runCatching { it.close() } }
            activeReader.compareAndSet(reader, null)
            device?.closeAndAwait(policy.closeSettleTimeoutMillis)
            activeDevice.compareAndSet(device, null)
        }
    }

    private suspend fun probeRawConfiguration(
        current: LensProbeResult,
        lens: LensDescriptor,
        device: OpenCameraLease,
        policy: CameraOperationPolicy,
    ): LensProbeResult {
        val raw = chooseRawConfiguration(lens)
        if (lens.capabilities.flags.raw != CapabilitySupport.SUPPORTED || raw == null) {
            return current
                .add(
                    ProbeStageResult(
                        stage = ProbeStage.RAW_CONFIGURATION_VALID,
                        outcome = ProbeOutcome.UNSUPPORTED,
                        detail = "No portable RAW stream reported",
                    ),
                )
        }
        if (!policy.allowRawSessionProbe) {
            return current.add(
                ProbeStageResult(
                    stage = ProbeStage.RAW_CONFIGURATION_VALID,
                    outcome = ProbeOutcome.SKIPPED,
                    detail = "RAW session probe disabled by active policy",
                ),
            )
        }
        val rawFailure = synchronized(rawFailureCounts) {
            rawFailureCounts[lens.identity.routingKey]
        }
        if (rawFailure != null && rawFailure.count >= policy.maxAutomaticFailures) {
            return current.add(
                ProbeStageResult(
                    stage = ProbeStage.RAW_CONFIGURATION_VALID,
                    outcome = ProbeOutcome.SKIPPED,
                    detail = "RAW session retry suppressed after repeated failures",
                    failureKind = rawFailure.kind,
                ),
            )
        }

        var rawReader: ImageReader? = null
        var rawSession: CaptureSessionLease? = null
        val start = SystemClock.elapsedRealtime()
        var result = current
        try {
            rawReader = createReader(raw, maxImages = 1)
            activeReader.set(rawReader)
            rawSession = device.device.awaitCaptureSession(
                surface = rawReader.surface,
                physicalCameraId = lens.identity.streamPhysicalCameraId,
                maximumResolution = raw.maximumResolution,
                handler = callbackThread.handler,
                timeoutMillis = policy.sessionTimeoutMillis,
            )
            activeSession.set(rawSession)
            result = result.add(
                success(ProbeStage.RAW_CONFIGURATION_VALID, elapsed(start)),
            )
            synchronized(rawFailureCounts) {
                rawFailureCounts.remove(lens.identity.routingKey)
            }
            // Phase 1 proves that the HAL accepts the RAW output. Capturing a frame is deliberately
            // opt-in later because some devices stall or allocate substantial memory at this point.
            result = result.add(
                ProbeStageResult(
                    stage = ProbeStage.RAW_TEST_SUCCESS,
                    outcome = ProbeOutcome.SKIPPED,
                    detail = "RAW session configured; frame acquisition not requested",
                ),
            )
        } catch (error: TimeoutCancellationException) {
            rememberRawFailure(
                lens.identity.routingKey,
                ProbeFailureKind.TIMEOUT,
                policy,
            )
            result = result.add(
                failure(
                    ProbeStage.RAW_CONFIGURATION_VALID,
                    ProbeOutcome.TIMEOUT,
                    ProbeFailureKind.TIMEOUT,
                    "RAW session configuration timed out",
                    elapsed(start),
                ),
            )
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            if (error is VirtualMachineError || error is ThreadDeath) throw error
            val cameraError = error.toCameraOperationException(ProbeFailureKind.SESSION_CONFIGURATION)
            rememberRawFailure(lens.identity.routingKey, cameraError.failureKind, policy)
            result = result.add(
                failure(
                    ProbeStage.RAW_CONFIGURATION_VALID,
                    ProbeOutcome.EXCEPTION,
                    cameraError.failureKind,
                    cameraError.message ?: "RAW session configuration failed",
                    elapsed(start),
                ),
            )
        } finally {
            rawSession?.closeAndAwait(policy.closeSettleTimeoutMillis)
            activeSession.compareAndSet(rawSession, null)
            rawReader?.let { runCatching { it.close() } }
            activeReader.compareAndSet(rawReader, null)
        }
        return result
    }

    private fun choosePreviewConfiguration(
        lens: LensDescriptor,
        policy: CameraOperationPolicy,
    ): StreamConfiguration? = ProbePreviewStreamSelector.select(
        streams = lens.capabilities.streamConfigurations.orEmpty(),
        policy = policy,
    )

    private fun chooseRawConfiguration(lens: LensDescriptor): StreamConfiguration? {
        val preference = mapOf(
            StreamFormat.RAW_SENSOR to 0,
            StreamFormat.RAW14 to 1,
            StreamFormat.RAW12 to 2,
            StreamFormat.RAW10 to 3,
        )
        return lens.capabilities.portableRawConfigurations
            .filter { it.format in preference.keys }
            .minWithOrNull(
                compareBy<StreamConfiguration> { if (it.maximumResolution) 1 else 0 }
                    .thenBy { preference[it.format] ?: Int.MAX_VALUE }
                    .thenBy { it.size.area ?: Long.MAX_VALUE },
            )
    }

    private fun createReader(
        configuration: StreamConfiguration,
        maxImages: Int = 2,
    ): ImageReader =
        ImageReader.newInstance(
            configuration.size.width,
            configuration.size.height,
            configuration.format.toImageFormat(),
            maxImages,
        )

    private fun StreamFormat.toImageFormat(): Int = when (this) {
        StreamFormat.PRIVATE -> ImageFormat.PRIVATE
        StreamFormat.YUV_420_888 -> ImageFormat.YUV_420_888
        StreamFormat.RAW_SENSOR -> ImageFormat.RAW_SENSOR
        StreamFormat.RAW10 -> ImageFormat.RAW10
        StreamFormat.RAW12 -> ImageFormat.RAW12
        StreamFormat.RAW14 -> if (Build.VERSION.SDK_INT >= 37) {
            ImageFormat.RAW14
        } else {
            throw IllegalArgumentException("RAW14 requires API 37")
        }
        else -> throw IllegalArgumentException("Unsupported probe stream format")
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

    private fun rememberFailure(
        routingKey: String,
        openCameraId: String,
        stage: ProbeStage,
        kind: ProbeFailureKind,
        policy: CameraOperationPolicy,
    ) {
        // Permission is external state, not evidence that the endpoint or HAL is broken. Once the
        // user grants access the same route must be immediately probeable without a process restart.
        if (kind == ProbeFailureKind.PERMISSION_DENIED) return
        synchronized(failureCounts) {
            val prior = failureCounts[routingKey]
            failureCounts[routingKey] = MutableFailure(
                count = (prior?.count ?: 0) + 1,
                kind = kind,
                threshold = policy.maxAutomaticFailures,
            )
        }
        if (stage == ProbeStage.OPEN_SUCCESS && kind in parentOpenFailureKinds) {
            synchronized(openFailureCounts) {
                val prior = openFailureCounts[openCameraId]
                openFailureCounts[openCameraId] = MutableFailure(
                    count = (prior?.count ?: 0) + 1,
                    kind = kind,
                    threshold = policy.maxAutomaticFailures,
                )
            }
        }
    }

    private fun rememberRawFailure(
        routingKey: String,
        kind: ProbeFailureKind,
        policy: CameraOperationPolicy,
    ) = synchronized(rawFailureCounts) {
        val prior = rawFailureCounts[routingKey]
        rawFailureCounts[routingKey] = MutableFailure(
            count = (prior?.count ?: 0) + 1,
            kind = kind,
            threshold = policy.maxAutomaticFailures,
        )
    }

    private fun LensProbeResult.add(stage: ProbeStageResult): LensProbeResult =
        ProbeStateMachine.transition(this, stage)

    private fun appendIfLegal(
        current: LensProbeResult,
        result: ProbeStageResult,
    ): LensProbeResult = if (ProbeStateMachine.canTransition(current, result)) {
        ProbeStateMachine.transition(current, result)
    } else {
        current
    }

    private fun success(stage: ProbeStage, durationMs: Long? = null) = ProbeStageResult(
        stage = stage,
        outcome = ProbeOutcome.SUCCESS,
        durationMs = durationMs,
    )

    private fun failure(
        stage: ProbeStage,
        outcome: ProbeOutcome,
        kind: ProbeFailureKind,
        detail: String,
        durationMs: Long? = null,
    ) = ProbeStageResult(
        stage = stage,
        outcome = outcome,
        durationMs = durationMs,
        detail = detail.take(160),
        failureKind = kind,
    )

    private fun elapsed(start: Long): Long = (SystemClock.elapsedRealtime() - start).coerceAtLeast(0L)

    private data class MutableFailure(
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

    private companion object {
        val parentOpenFailureKinds = setOf(
            ProbeFailureKind.ACCESS_DENIED,
            ProbeFailureKind.DISCONNECTED,
            ProbeFailureKind.SERVICE_ERROR,
            ProbeFailureKind.DEVICE_ERROR,
            ProbeFailureKind.TIMEOUT,
            ProbeFailureKind.UNKNOWN,
        )

        fun defaultEnvironment() = CameraRuntimeEnvironment(
            manufacturer = Build.MANUFACTURER.orEmpty(),
            model = Build.MODEL.orEmpty(),
            sdkInt = Build.VERSION.SDK_INT,
            buildFingerprint = Build.FINGERPRINT.orEmpty(),
        )
    }
}

/** Uses the exact PRIVATE class of stream consumed by TextureView live preview. */
internal object ProbePreviewStreamSelector {
    fun select(
        streams: List<StreamConfiguration>,
        policy: CameraOperationPolicy,
    ): StreamConfiguration? {
        val eligible = streams.filter {
            !it.maximumResolution && it.format == StreamFormat.PRIVATE && it.size.isValid
        }
        val selected = PreviewSizeSelector.select(
            candidates = eligible.map {
                PreviewStreamCandidate(
                    it.size.width,
                    it.size.height,
                    it.minFrameDurationNs,
                )
            },
            request = PreviewSelectionRequest(
                targetWidth = 1_280,
                targetHeight = 720,
                maximumArea = policy.maximumPreviewArea.coerceAtMost(2_073_600L),
                maximumLongEdge = policy.maximumPreviewLongEdge.coerceAtMost(1_920),
            ),
        ) ?: return null
        return eligible.firstOrNull {
            it.size.width == selected.width && it.size.height == selected.height
        }
    }
}
