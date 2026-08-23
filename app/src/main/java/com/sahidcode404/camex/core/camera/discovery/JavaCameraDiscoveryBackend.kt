package com.sahidcode404.camex.core.camera.discovery

import android.content.Context
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.params.StreamConfigurationMap
import android.os.Build
import android.os.SystemClock
import android.util.Size
import androidx.annotation.RequiresApi
import com.sahidcode404.camex.core.camera.CameraCharacteristicsMapper
import com.sahidcode404.camex.core.camera.CameraDiscoveryFailure
import com.sahidcode404.camex.core.camera.DiscoveryFailureKind
import com.sahidcode404.camex.core.camera.LogicalCameraRelationship
import com.sahidcode404.camex.core.logic.CapabilityEvidence
import com.sahidcode404.camex.core.logic.CapabilityMapper
import com.sahidcode404.camex.core.logic.LensClassifier
import com.sahidcode404.camex.core.logic.LensFingerprintGenerator
import com.sahidcode404.camex.core.model.CameraCapability
import com.sahidcode404.camex.core.model.CapabilitySupport
import com.sahidcode404.camex.core.model.FingerprintFallbackContext
import com.sahidcode404.camex.core.model.HardwareLevel
import com.sahidcode404.camex.core.model.LensCapabilities
import com.sahidcode404.camex.core.model.LensDescriptor
import com.sahidcode404.camex.core.model.LensFacing
import com.sahidcode404.camex.core.model.LensIdentity
import com.sahidcode404.camex.core.model.LensNodeKind
import com.sahidcode404.camex.core.model.LensUsability
import com.sahidcode404.camex.core.model.PhysicalSize
import com.sahidcode404.camex.core.model.RawAccess
import com.sahidcode404.camex.core.model.SensorRect
import com.sahidcode404.camex.core.model.Size2D
import com.sahidcode404.camex.core.model.StreamConfiguration
import com.sahidcode404.camex.core.model.StreamFormat
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

enum class JavaDiscoverySource {
    JAVA_PUBLIC,
    JAVA_PHYSICAL,
}

/** Small CameX-owned metadata record used on the startup/discovery hot path. */
data class JavaMinimalCameraMetadata(
    val identity: LensIdentity,
    val facing: LensFacing,
    val focalLengthsMm: List<Double>?,
    val sensorPhysicalSize: PhysicalSize?,
    val activeArray: SensorRect?,
    val pixelArraySize: Size2D?,
    val sensorOrientationDegrees: Int?,
    val hardwareLevel: HardwareLevel,
    val backwardCompatible: CapabilitySupport,
    val rawCapabilityAdvertised: Boolean?,
    val rawStreamActuallyDeclared: Boolean,
    val privatePreviewSizes: List<Size2D>,
    val yuvStreamActuallyDeclared: Boolean,
    val depthOnly: Boolean,
    val systemOnly: Boolean,
) {
    val previewStreamActuallyDeclared: Boolean
        get() = privatePreviewSizes.isNotEmpty() || yuvStreamActuallyDeclared

    val hasOpticalEvidence: Boolean
        get() = focalLengthsMm.orEmpty().isNotEmpty() || sensorPhysicalSize?.isValid == true ||
            activeArray?.isValid == true

    val crediblePhotographicCandidate: Boolean
        get() = !systemOnly && !depthOnly && previewStreamActuallyDeclared &&
            (backwardCompatible == CapabilitySupport.SUPPORTED || hasOpticalEvidence ||
                rawStreamActuallyDeclared)

    /** A TextureView route requires a declared PRIVATE size; YUV-only stays advanced/unknown. */
    fun toLensDescriptor(
        discoveryOrder: Int,
        fallbackContext: FingerprintFallbackContext,
    ): LensDescriptor {
        val rawSupport = when {
            rawStreamActuallyDeclared -> CapabilitySupport.SUPPORTED
            rawCapabilityAdvertised == false -> CapabilitySupport.UNSUPPORTED
            else -> CapabilitySupport.UNKNOWN
        }
        val rawAccess = when {
            rawSupport != CapabilitySupport.SUPPORTED -> if (
                rawSupport == CapabilitySupport.UNSUPPORTED
            ) RawAccess.NONE else RawAccess.UNKNOWN
            identity.streamPhysicalCameraId != null -> RawAccess.PHYSICAL_STREAM
            else -> RawAccess.DIRECT
        }
        val previewConfigurations = privatePreviewSizes.map { size ->
            StreamConfiguration(StreamFormat.PRIVATE, size)
        }
        val flags = CapabilityMapper.map(
            CapabilityEvidence(
                reportedCapabilities = when (rawCapabilityAdvertised) {
                    true -> setOf(CameraCapability.RAW).let { raw ->
                        if (backwardCompatible == CapabilitySupport.SUPPORTED) {
                            raw + CameraCapability.BACKWARD_COMPATIBLE
                        } else {
                            raw
                        }
                    }
                    false -> if (backwardCompatible == CapabilitySupport.SUPPORTED) {
                        setOf(CameraCapability.BACKWARD_COMPATIBLE)
                    } else {
                        emptySet()
                    }
                    null -> null
                },
                // Minimal metadata deliberately contains only TextureView sizes. Override RAW
                // below with the independently observed stream declaration.
                streamConfigurations = previewConfigurations,
            ),
        ).copy(raw = rawSupport, backwardCompatible = backwardCompatible)
        val capabilities = LensCapabilities(
            focalLengthsMm = focalLengthsMm,
            sensorPhysicalSize = sensorPhysicalSize,
            pixelArraySize = pixelArraySize,
            activeArray = activeArray,
            sensorOrientationDegrees = sensorOrientationDegrees,
            hardwareLevel = hardwareLevel,
            flags = flags,
            rawAccess = rawAccess,
            streamConfigurations = previewConfigurations.takeIf(List<StreamConfiguration>::isNotEmpty),
        )
        val usability = when {
            systemOnly -> LensUsability.SYSTEM_ONLY
            depthOnly -> LensUsability.DEPTH_AUXILIARY
            crediblePhotographicCandidate && privatePreviewSizes.isNotEmpty() -> LensUsability.PREVIEW_ONLY
            crediblePhotographicCandidate -> LensUsability.PHOTOGRAPHIC_CANDIDATE
            else -> LensUsability.UNKNOWN
        }
        val base = LensDescriptor(
            identity = identity,
            facing = facing,
            capabilities = capabilities,
            usability = usability,
            discoveryOrder = discoveryOrder,
        )
        val fingerprinted = base.copy(
            fingerprint = LensFingerprintGenerator.generate(base, fallbackContext),
        )
        return fingerprinted.copy(category = LensClassifier.classify(fingerprinted))
    }
}

sealed interface JavaCameraDiscoveryUpdate {
    data class AdvertisedIds(val ids: List<String>) : JavaCameraDiscoveryUpdate
    data class Relationship(val value: LogicalCameraRelationship) : JavaCameraDiscoveryUpdate
    data class Candidate(
        val metadata: JavaMinimalCameraMetadata,
        val source: JavaDiscoverySource,
    ) : JavaCameraDiscoveryUpdate
    data class Enriched(
        val lens: LensDescriptor,
        val source: JavaDiscoverySource,
    ) : JavaCameraDiscoveryUpdate
    data class Failure(val value: CameraDiscoveryFailure) : JavaCameraDiscoveryUpdate
    data class Finished(
        val advertisedIdCount: Int,
        val candidateCount: Int,
        val durationNs: Long,
    ) : JavaCameraDiscoveryUpdate
}

/** Camera2 metadata backend. It never opens a CameraDevice. */
class JavaCameraDiscoveryBackend(
    private val cameraManager: CameraManager,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
    metadataConcurrency: Int = DEFAULT_METADATA_CONCURRENCY,
    private val fallbackContext: FingerprintFallbackContext = defaultFingerprintContext(),
) {
    private val metadataConcurrencyLimit = metadataConcurrency.coerceIn(
        1,
        DEFAULT_METADATA_CONCURRENCY,
    )
    private val metadataSemaphore = Semaphore(metadataConcurrencyLimit)

    constructor(
        context: Context,
        dispatcher: CoroutineDispatcher = Dispatchers.Default,
        metadataConcurrency: Int = DEFAULT_METADATA_CONCURRENCY,
    ) : this(
        context.applicationContext.getSystemService(CameraManager::class.java),
        dispatcher,
        metadataConcurrency,
        defaultFingerprintContext(),
    )

    /** First-install seed: stop after the first credible rear route, then fall back gracefully. */
    suspend fun scanPrimaryCameraFast(): JavaMinimalCameraMetadata? = withContext(dispatcher) {
        val ids = advertisedIds().getOrNull().orEmpty()
        var fallback: JavaMinimalCameraMetadata? = null
        for (cameraId in ids) {
            val characteristics = readCharacteristics(cameraId).getOrNull() ?: continue
            val minimal = MinimalCharacteristicsMapper.map(
                LensIdentity(publicCameraId = cameraId),
                characteristics,
            )
            if (!minimal.crediblePhotographicCandidate || minimal.privatePreviewSizes.isEmpty()) continue
            if (fallback == null) fallback = minimal
            if (minimal.facing == LensFacing.BACK) return@withContext minimal
        }
        fallback
    }

    /**
     * Emits minimal candidates first. Full capability extraction is a separate background stage
     * after every advertised/physical route has had a chance to become a lens button.
     */
    fun discoverIncrementally(enrichCapabilities: Boolean = true): Flow<JavaCameraDiscoveryUpdate> =
        channelFlow {
            val startedNs = SystemClock.elapsedRealtimeNanos()
            val idsResult = withContext(dispatcher) { advertisedIds() }
            val ids = idsResult.getOrElse { error ->
                send(JavaCameraDiscoveryUpdate.Failure(error.toDiscoveryFailure()))
                send(JavaCameraDiscoveryUpdate.Finished(0, 0, elapsed(startedNs)))
                close()
                return@channelFlow
            }
            send(JavaCameraDiscoveryUpdate.AdvertisedIds(ids))
            val discovered = ConcurrentLinkedQueue<DiscoveredMinimal>()
            val order = AtomicInteger(0)

            ids.chunked(metadataConcurrencyLimit).forEach { batch ->
                supervisorScope {
                    batch.forEach { publicId ->
                        launch(dispatcher) {
                            metadataSemaphore.withPermit {
                            val parentCharacteristics = readCharacteristics(publicId).getOrElse { error ->
                                send(
                                    JavaCameraDiscoveryUpdate.Failure(
                                        error.toDiscoveryFailure(publicCameraId = publicId),
                                    ),
                                )
                                return@withPermit
                            }
                            val parentIdentity = LensIdentity(publicCameraId = publicId)
                            val parent = MinimalCharacteristicsMapper.map(
                                parentIdentity,
                                parentCharacteristics,
                            )
                            val parentRecord = DiscoveredMinimal(
                                parent,
                                JavaDiscoverySource.JAVA_PUBLIC,
                                order.getAndIncrement(),
                            )
                            discovered += parentRecord
                            send(JavaCameraDiscoveryUpdate.Candidate(parent, parentRecord.source))

                            val physicalIds = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                                readPhysicalIds(parentCharacteristics)
                            } else {
                                emptyList()
                            }
                            if (physicalIds.isNotEmpty()) {
                                send(
                                    JavaCameraDiscoveryUpdate.Relationship(
                                        LogicalCameraRelationship(publicId, physicalIds),
                                    ),
                                )
                            }
                            physicalIds.forEach physicalLoop@ { physicalId ->
                                val identity = LensIdentity(
                                    publicCameraId = publicId,
                                    physicalCameraId = physicalId,
                                    logicalParentCameraId = publicId,
                                    nodeKind = LensNodeKind.PHYSICAL,
                                )
                                val childCharacteristics = readCharacteristics(physicalId)
                                    .getOrElse { error ->
                                        send(
                                            JavaCameraDiscoveryUpdate.Failure(
                                                CameraDiscoveryFailure(
                                                    publicCameraId = publicId,
                                                    physicalCameraId = physicalId,
                                                    kind = DiscoveryFailureKind.PHYSICAL_METADATA_UNAVAILABLE,
                                                    detail = error.sanitizedType(),
                                                ),
                                            ),
                                        )
                                        return@physicalLoop
                                    }
                                val rawChild = MinimalCharacteristicsMapper.map(
                                    identity,
                                    childCharacteristics,
                                )
                                val child = if (rawChild.facing == LensFacing.UNKNOWN) {
                                    rawChild.copy(facing = parent.facing)
                                } else {
                                    rawChild
                                }
                                val childRecord = DiscoveredMinimal(
                                    child,
                                    JavaDiscoverySource.JAVA_PHYSICAL,
                                    order.getAndIncrement(),
                                )
                                discovered += childRecord
                                send(JavaCameraDiscoveryUpdate.Candidate(child, childRecord.source))
                            }
                            }
                        }
                    }
                }
            }

            if (enrichCapabilities) {
                discovered.toList().chunked(metadataConcurrencyLimit).forEach { batch ->
                    supervisorScope {
                        batch.forEach { record ->
                            launch(dispatcher) {
                                metadataSemaphore.withPermit {
                                val metadataId = record.metadata.identity.streamPhysicalCameraId
                                    ?: record.metadata.identity.publicCameraId
                                val characteristics = readCharacteristics(metadataId).getOrElse { error ->
                                    send(
                                        JavaCameraDiscoveryUpdate.Failure(
                                            error.toDiscoveryFailure(
                                                publicCameraId = record.metadata.identity.publicCameraId,
                                            ),
                                        ),
                                    )
                                    return@withPermit
                                }
                                val mapped = CameraCharacteristicsMapper.map(
                                    record.metadata.identity,
                                    characteristics,
                                    record.discoveryOrder,
                                ).let { lens ->
                                    val facing = if (
                                        lens.facing == LensFacing.UNKNOWN &&
                                        record.metadata.facing != LensFacing.UNKNOWN
                                    ) {
                                        record.metadata.facing
                                    } else {
                                        lens.facing
                                    }
                                    val withFacing = lens.copy(facing = facing)
                                    val withFingerprint = withFacing.copy(
                                        fingerprint = LensFingerprintGenerator.generate(
                                            withFacing,
                                            fallbackContext,
                                        ),
                                    )
                                    withFingerprint.copy(
                                        category = LensClassifier.classify(withFingerprint),
                                        // Metadata discovery may expose a credible route before a
                                        // session has validated it. Do not label that route broken.
                                        usability = when {
                                            withFingerprint.usability == LensUsability.SYSTEM_ONLY ->
                                                LensUsability.SYSTEM_ONLY
                                            withFingerprint.usability == LensUsability.DEPTH_AUXILIARY ->
                                                LensUsability.DEPTH_AUXILIARY
                                            record.metadata.crediblePhotographicCandidate &&
                                                withFingerprint.capabilities.privateResolutions.isNotEmpty() ->
                                                LensUsability.PREVIEW_ONLY
                                            record.metadata.crediblePhotographicCandidate ->
                                                LensUsability.PHOTOGRAPHIC_CANDIDATE
                                            else -> LensUsability.UNKNOWN
                                        },
                                    )
                                }
                                send(JavaCameraDiscoveryUpdate.Enriched(mapped, record.source))
                                }
                            }
                        }
                    }
                }
            }
            send(
                JavaCameraDiscoveryUpdate.Finished(
                    advertisedIdCount = ids.size,
                    candidateCount = discovered.size,
                    durationNs = elapsed(startedNs),
                ),
            )
        }

    private fun advertisedIds(): Result<List<String>> = runCatchingCamera {
        cameraManager.cameraIdList.filter(String::isNotBlank)
    }

    private fun readCharacteristics(cameraId: String): Result<CameraCharacteristics> =
        runCatchingCamera { cameraManager.getCameraCharacteristics(cameraId) }

    @RequiresApi(Build.VERSION_CODES.P)
    private fun readPhysicalIds(characteristics: CameraCharacteristics): List<String> =
        runCatchingCamera {
            characteristics.physicalCameraIds.filter(String::isNotBlank).sorted()
        }.getOrDefault(emptyList())

    private fun elapsed(startedNs: Long): Long =
        (SystemClock.elapsedRealtimeNanos() - startedNs).coerceAtLeast(0L)

    private data class DiscoveredMinimal(
        val metadata: JavaMinimalCameraMetadata,
        val source: JavaDiscoverySource,
        val discoveryOrder: Int,
    )

    private companion object {
        // Advertised NDK discovery runs beside Java and performs one characteristics read at a
        // time. Reserve one of the four HAL metadata lanes so combined default concurrency stays
        // at the Phase 1A target rather than briefly reaching five.
        const val DEFAULT_TOTAL_METADATA_CONCURRENCY = 4
        const val RESERVED_NATIVE_METADATA_CONCURRENCY = 1
        const val DEFAULT_METADATA_CONCURRENCY =
            DEFAULT_TOTAL_METADATA_CONCURRENCY - RESERVED_NATIVE_METADATA_CONCURRENCY

        fun defaultFingerprintContext() = FingerprintFallbackContext(
            buildFingerprint = Build.FINGERPRINT,
            deviceCodename = Build.DEVICE,
            model = Build.MODEL,
        )
    }
}

private object MinimalCharacteristicsMapper {
    fun map(
        identity: LensIdentity,
        characteristics: CameraCharacteristics,
    ): JavaMinimalCameraMetadata {
        val reported = characteristics.safeGet(
            CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES,
        )?.toSet()
        val streamMap = characteristics.safeGet(
            CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP,
        )
        val formats = streamMap.safe { outputFormats }?.toSet().orEmpty()
        val privateSizes = streamMap.safe { getOutputSizes(SurfaceTexture::class.java) }
            .orEmpty()
            .mapNotNull(Size::toValidModel)
            .distinct()
            .sortedWith(compareBy<Size2D> { it.area ?: Long.MAX_VALUE }.thenBy { it.width })
        val backward = reported?.contains(
            CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_BACKWARD_COMPATIBLE,
        ).asSupport()
        val depth = reported?.contains(CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_DEPTH_OUTPUT)
        val system = reported?.contains(CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_SYSTEM_CAMERA)
        val rawAdvertised = reported?.contains(CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_RAW)
        return JavaMinimalCameraMetadata(
            identity = identity.copy(
                nodeKind = when {
                    identity.streamPhysicalCameraId != null -> LensNodeKind.PHYSICAL
                    reported?.contains(
                        CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_LOGICAL_MULTI_CAMERA,
                    ) == true -> LensNodeKind.LOGICAL
                    else -> identity.nodeKind
                },
            ),
            facing = when (characteristics.safeGet(CameraCharacteristics.LENS_FACING)) {
                CameraMetadata.LENS_FACING_FRONT -> LensFacing.FRONT
                CameraMetadata.LENS_FACING_BACK -> LensFacing.BACK
                CameraMetadata.LENS_FACING_EXTERNAL -> LensFacing.EXTERNAL
                else -> LensFacing.UNKNOWN
            },
            focalLengthsMm = characteristics.safeGet(
                CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS,
            )?.filter { it.isFinite() && it > 0f }?.map(Float::toDouble)
                ?.takeIf(List<Double>::isNotEmpty),
            sensorPhysicalSize = characteristics.safeGet(
                CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE,
            )?.let { size -> PhysicalSize(size.width.toDouble(), size.height.toDouble()) }
                ?.takeIf(PhysicalSize::isValid),
            activeArray = characteristics.safeGet(
                CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE,
            )?.toValidModel(),
            pixelArraySize = characteristics.safeGet(
                CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE,
            )?.toValidModel(),
            sensorOrientationDegrees = characteristics.safeGet(CameraCharacteristics.SENSOR_ORIENTATION)
                ?.takeIf { it in 0..359 },
            hardwareLevel = when (characteristics.safeGet(
                CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL,
            )) {
                CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_LEGACY -> HardwareLevel.LEGACY
                CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_LIMITED -> HardwareLevel.LIMITED
                CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_FULL -> HardwareLevel.FULL
                CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_3 -> HardwareLevel.LEVEL_3
                CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_EXTERNAL -> HardwareLevel.EXTERNAL
                else -> HardwareLevel.UNKNOWN
            },
            backwardCompatible = backward,
            rawCapabilityAdvertised = rawAdvertised,
            rawStreamActuallyDeclared = formats.any(::isPortableRawFormat),
            privatePreviewSizes = privateSizes,
            yuvStreamActuallyDeclared = ImageFormat.YUV_420_888 in formats,
            depthOnly = depth == true && backward == CapabilitySupport.UNSUPPORTED,
            systemOnly = system == true,
        )
    }

    private fun isPortableRawFormat(value: Int): Boolean =
        value == ImageFormat.RAW_SENSOR || value == ImageFormat.RAW10 ||
            value == ImageFormat.RAW12 || (Build.VERSION.SDK_INT >= 37 && value == ImageFormat.RAW14)
}

private fun Boolean?.asSupport(): CapabilitySupport = when (this) {
    true -> CapabilitySupport.SUPPORTED
    false -> CapabilitySupport.UNSUPPORTED
    null -> CapabilitySupport.UNKNOWN
}

private fun Size.toValidModel(): Size2D? = Size2D(width, height).takeIf(Size2D::isValid)
private fun Rect.toValidModel(): SensorRect? = SensorRect(left, top, right, bottom)
    .takeIf(SensorRect::isValid)

private fun <T> CameraCharacteristics.safeGet(
    key: CameraCharacteristics.Key<T>,
): T? = try {
    get(key)
} catch (error: Throwable) {
    if (error is VirtualMachineError || error is ThreadDeath) throw error
    null
}

private inline fun <T> StreamConfigurationMap?.safe(
    block: StreamConfigurationMap.() -> T,
): T? = if (this == null) {
    null
} else {
    try {
        block()
    } catch (error: Throwable) {
        if (error is VirtualMachineError || error is ThreadDeath) throw error
        null
    }
}

private inline fun <T> runCatchingCamera(block: () -> T): Result<T> = try {
    Result.success(block())
} catch (error: Throwable) {
    if (error is VirtualMachineError || error is ThreadDeath) throw error
    Result.failure(error)
}

private fun Throwable.toDiscoveryFailure(publicCameraId: String? = null): CameraDiscoveryFailure =
    CameraDiscoveryFailure(
        publicCameraId = publicCameraId,
        kind = when (this) {
            is SecurityException -> DiscoveryFailureKind.ACCESS_DENIED
            is CameraAccessException -> if (reason == CameraAccessException.CAMERA_DISABLED) {
                DiscoveryFailureKind.ACCESS_DENIED
            } else {
                DiscoveryFailureKind.CAMERA_SERVICE
            }
            is IllegalArgumentException -> DiscoveryFailureKind.INVALID_METADATA
            else -> DiscoveryFailureKind.UNKNOWN
        },
        detail = sanitizedType(),
    )

private fun Throwable.sanitizedType(): String = when (this) {
    is CameraAccessException -> "CameraAccess(${reason})"
    is SecurityException -> "SecurityException"
    is IllegalArgumentException -> "InvalidCameraMetadata"
    else -> javaClass.simpleName.take(64).ifBlank { "UnknownFailure" }
}
