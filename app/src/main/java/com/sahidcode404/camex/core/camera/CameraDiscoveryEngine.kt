package com.sahidcode404.camex.core.camera

import android.content.Context
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.os.Build
import androidx.annotation.RequiresApi
import com.sahidcode404.camex.core.logic.LensClassifier
import com.sahidcode404.camex.core.logic.LensFingerprintGenerator
import com.sahidcode404.camex.core.model.FingerprintFallbackContext
import com.sahidcode404.camex.core.model.LensCapabilities
import com.sahidcode404.camex.core.model.LensDescriptor
import com.sahidcode404.camex.core.model.LensFacing
import com.sahidcode404.camex.core.model.LensIdentity
import com.sahidcode404.camex.core.model.LensNodeKind
import com.sahidcode404.camex.core.model.LensUsability
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class LogicalCameraRelationship(
    val logicalCameraId: String,
    val physicalCameraIds: List<String>,
)

enum class DiscoveryFailureKind {
    CAMERA_SERVICE,
    ACCESS_DENIED,
    INVALID_METADATA,
    PHYSICAL_METADATA_UNAVAILABLE,
    UNKNOWN,
}

data class CameraDiscoveryFailure(
    val publicCameraId: String? = null,
    val physicalCameraId: String? = null,
    val kind: DiscoveryFailureKind,
    /** Bounded and sanitized: exception messages and stack traces are intentionally excluded. */
    val detail: String,
)

data class CameraDiscoverySnapshot(
    val lenses: List<LensDescriptor>,
    val logicalRelationships: List<LogicalCameraRelationship>,
    val failures: List<CameraDiscoveryFailure>,
) {
    val publicLenses: List<LensDescriptor>
        get() = lenses.filter { it.identity.streamPhysicalCameraId == null }
    val physicalLenses: List<LensDescriptor>
        get() = lenses.filter { it.identity.streamPhysicalCameraId != null }

    companion object {
        val Empty = CameraDiscoverySnapshot(emptyList(), emptyList(), emptyList())
    }
}

/**
 * Discovers both application-visible camera IDs and physical children of logical cameras.
 *
 * Physical children are represented as routed endpoints: the logical parent remains the only ID
 * passed to CameraManager.openCamera, while the physical ID is reserved for output configuration.
 */
class CameraDiscoveryEngine(
    private val cameraManager: CameraManager,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val fingerprintFallbackContext: FingerprintFallbackContext = defaultFingerprintContext(),
) {
    constructor(
        context: Context,
        dispatcher: CoroutineDispatcher = Dispatchers.Default,
    ) : this(
        context.applicationContext.getSystemService(CameraManager::class.java),
        dispatcher,
        defaultFingerprintContext(),
    )

    suspend fun discover(): CameraDiscoverySnapshot = withContext(dispatcher) {
        val failures = mutableListOf<CameraDiscoveryFailure>()
        val lenses = mutableListOf<LensDescriptor>()
        val relationships = mutableListOf<LogicalCameraRelationship>()
        val publicIds = try {
            cameraManager.cameraIdList.toList()
        } catch (error: Throwable) {
            if (error is VirtualMachineError || error is ThreadDeath) throw error
            failures += error.toDiscoveryFailure()
            return@withContext CameraDiscoverySnapshot(emptyList(), emptyList(), failures)
        }

        var discoveryOrder = 0
        publicIds.forEach { publicId ->
            val parentCharacteristics = tryCharacteristics(publicId, failures) ?: run {
                val failure = failures.lastOrNull { it.publicCameraId == publicId }
                lenses += LensDescriptor(
                    identity = LensIdentity(publicCameraId = publicId),
                    capabilities = LensCapabilities(),
                    // Access denial is external policy, not broken metadata. Keep the route in
                    // diagnostics, but mark it inaccessible so probing cannot misclassify it.
                    usability = if (failure?.kind == DiscoveryFailureKind.ACCESS_DENIED) {
                        LensUsability.INACCESSIBLE
                    } else {
                        LensUsability.UNKNOWN
                    },
                    discoveryOrder = discoveryOrder++,
                ).enriched()
                return@forEach
            }
            val parent = CameraCharacteristicsMapper.map(
                identity = LensIdentity(publicCameraId = publicId),
                characteristics = parentCharacteristics,
                discoveryOrder = discoveryOrder++,
            )
            lenses += parent.enriched()

            val physicalIds = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                readPhysicalIds(parentCharacteristics)
            } else {
                emptyList()
            }
            if (physicalIds.isEmpty()) return@forEach
            relationships += LogicalCameraRelationship(publicId, physicalIds)

            physicalIds.forEach { physicalId ->
                val identity = LensIdentity(
                    // Only application-visible IDs belong in publicCameraId. The parent is the
                    // public route for physical-only nodes even if physicalId also happens to be public.
                    publicCameraId = publicId,
                    physicalCameraId = physicalId,
                    logicalParentCameraId = publicId,
                    nodeKind = LensNodeKind.PHYSICAL,
                )
                val physicalCharacteristics = tryPhysicalCharacteristics(
                    parentId = publicId,
                    physicalId = physicalId,
                    failures = failures,
                )
                lenses += (if (physicalCharacteristics != null) {
                    val child = CameraCharacteristicsMapper.map(
                        identity,
                        physicalCharacteristics,
                        discoveryOrder++,
                    )
                    // Some HALs omit facing only on physical metadata. Topology makes the logical
                    // parent's facing the safe fallback; preserve all child optical metadata.
                    if (child.facing == LensFacing.UNKNOWN) {
                        child.copy(facing = parent.facing)
                    } else {
                        child
                    }
                } else {
                    // Preserve the topology in diagnostics. Facing is safe to inherit; stream
                    // metadata is not, because the parent may advertise a combination the child rejects.
                    LensDescriptor(
                        identity = identity,
                        facing = parent.facing,
                        capabilities = LensCapabilities(),
                        discoveryOrder = discoveryOrder++,
                    )
                }).enriched()
            }
        }

        CameraDiscoverySnapshot(
            lenses = lenses.toList(),
            logicalRelationships = relationships.toList(),
            failures = failures.toList(),
        )
    }

    private fun tryCharacteristics(
        cameraId: String,
        failures: MutableList<CameraDiscoveryFailure>,
    ): CameraCharacteristics? = try {
        cameraManager.getCameraCharacteristics(cameraId)
    } catch (error: Throwable) {
        if (error is VirtualMachineError || error is ThreadDeath) throw error
        failures += error.toDiscoveryFailure(publicCameraId = cameraId)
        null
    }

    private fun tryPhysicalCharacteristics(
        parentId: String,
        physicalId: String,
        failures: MutableList<CameraDiscoveryFailure>,
    ): CameraCharacteristics? = try {
        cameraManager.getCameraCharacteristics(physicalId)
    } catch (error: Throwable) {
        if (error is VirtualMachineError || error is ThreadDeath) throw error
        failures += CameraDiscoveryFailure(
            publicCameraId = parentId,
            physicalCameraId = physicalId,
            kind = DiscoveryFailureKind.PHYSICAL_METADATA_UNAVAILABLE,
            detail = error.sanitizedType(),
        )
        null
    }

    @RequiresApi(Build.VERSION_CODES.P)
    private fun readPhysicalIds(characteristics: CameraCharacteristics): List<String> = try {
        characteristics.physicalCameraIds
            .filter(String::isNotBlank)
            .sorted()
    } catch (error: Throwable) {
        if (error is VirtualMachineError || error is ThreadDeath) throw error
        emptyList()
    }

    private fun Throwable.toDiscoveryFailure(
        publicCameraId: String? = null,
    ): CameraDiscoveryFailure {
        val kind = when (this) {
            is SecurityException -> DiscoveryFailureKind.ACCESS_DENIED
            is CameraAccessException -> if (reason == CameraAccessException.CAMERA_DISABLED) {
                DiscoveryFailureKind.ACCESS_DENIED
            } else {
                DiscoveryFailureKind.CAMERA_SERVICE
            }
            is IllegalArgumentException -> DiscoveryFailureKind.INVALID_METADATA
            else -> DiscoveryFailureKind.UNKNOWN
        }
        return CameraDiscoveryFailure(
            publicCameraId = publicCameraId,
            kind = kind,
            detail = sanitizedType(),
        )
    }

    private fun Throwable.sanitizedType(): String = when (this) {
        is CameraAccessException -> "CameraAccess(${cameraAccessReasonName(reason)})"
        is SecurityException -> "SecurityException"
        is IllegalArgumentException -> "InvalidCameraMetadata"
        else -> javaClass.simpleName.take(64).ifBlank { "UnknownFailure" }
    }

    private fun cameraAccessReasonName(reason: Int): String = when (reason) {
        CameraAccessException.CAMERA_DISABLED -> "disabled"
        CameraAccessException.CAMERA_DISCONNECTED -> "disconnected"
        CameraAccessException.CAMERA_ERROR -> "service_error"
        CameraAccessException.CAMERA_IN_USE -> "in_use"
        CameraAccessException.MAX_CAMERAS_IN_USE -> "max_in_use"
        else -> "unknown"
    }

    private fun LensDescriptor.enriched(): LensDescriptor {
        val withFingerprint = copy(
            fingerprint = LensFingerprintGenerator.generate(this, fingerprintFallbackContext),
        )
        return withFingerprint.copy(category = LensClassifier.classify(withFingerprint))
    }

    private companion object {
        fun defaultFingerprintContext() = FingerprintFallbackContext(
            buildFingerprint = Build.FINGERPRINT,
            deviceCodename = Build.DEVICE,
            model = Build.MODEL,
        )
    }
}
