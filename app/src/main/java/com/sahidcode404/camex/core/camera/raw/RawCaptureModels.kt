package com.sahidcode404.camex.core.camera.raw

import com.sahidcode404.camex.core.model.CapabilitySupport
import com.sahidcode404.camex.core.model.LensDescriptor
import com.sahidcode404.camex.core.model.Size2D
import com.sahidcode404.camex.core.model.StreamFormat

enum class RawSupportState {
    SUPPORTED,
    UNSUPPORTED,
    UNKNOWN,
}

enum class RawCapturePhase {
    IDLE,
    CAPTURING,
    SAVING,
    SAVED,
    FAILED,
}

data class RawCapabilityInfo(
    val support: RawSupportState = RawSupportState.UNKNOWN,
    val availableSizes: List<Size2D> = emptyList(),
    val selectedSize: Size2D? = null,
    val sessionReady: Boolean = false,
    val profileRoutingKey: String? = null,
    val detail: String? = null,
) {
    val canAttempt: Boolean
        get() = support != RawSupportState.UNSUPPORTED && selectedSize != null

    companion object {
        val Unknown = RawCapabilityInfo()
    }
}

object RawCapabilityResolver {
    fun resolve(lens: LensDescriptor): RawCapabilityInfo {
        // Phase 2 intentionally uses SCALER_STREAM_CONFIGURATION_MAP RAW_SENSOR outputs, not the
        // maximum-resolution pixel-mode map. Maximum-resolution RAW can be added in a later phase.
        val sizes = lens.capabilities.configurations(StreamFormat.RAW_SENSOR)
            .filterNot { it.maximumResolution }
            .map { it.size }
            .filter { it.isValid }
            .distinct()
            .sortedByDescending { it.area ?: -1L }
        val advertised = lens.capabilities.flags.raw
        val support = when {
            advertised == CapabilitySupport.SUPPORTED && sizes.isNotEmpty() -> RawSupportState.SUPPORTED
            advertised == CapabilitySupport.UNSUPPORTED && sizes.isEmpty() -> RawSupportState.UNSUPPORTED
            else -> RawSupportState.UNKNOWN
        }
        val detail = when {
            advertised == CapabilitySupport.SUPPORTED && sizes.isEmpty() ->
                "RAW capability advertised but no RAW_SENSOR size was reported"
            advertised == CapabilitySupport.UNSUPPORTED && sizes.isNotEmpty() ->
                "RAW_SENSOR sizes were reported despite RAW capability being unavailable"
            advertised == CapabilitySupport.UNKNOWN ->
                "RAW capability metadata is incomplete"
            else -> null
        }
        return RawCapabilityInfo(
            support = support,
            availableSizes = sizes,
            selectedSize = sizes.firstOrNull(),
            profileRoutingKey = lens.identity.routingKey,
            detail = detail,
        )
    }
}

data class RawCaptureRequest(
    val selectionGeneration: Long,
    val canonicalFingerprint: String?,
    val profileFingerprint: String?,
)

data class RawCaptureContext(
    val selectionGeneration: Long,
    val canonicalFingerprint: String?,
    val profileFingerprint: String?,
    val routingKey: String,
    val openCameraId: String,
    val streamPhysicalCameraId: String?,
    val rawSize: Size2D,
    val captureToken: Long,
    val transportGeneration: Long,
)

data class RawCaptureDiagnostics(
    val context: RawCaptureContext? = null,
    val rawSupported: RawSupportState = RawSupportState.UNKNOWN,
    val availableRawSizes: List<Size2D> = emptyList(),
    val selectedRawSize: Size2D? = null,
    val rawTimestamp: Long? = null,
    val resultTimestamp: Long? = null,
    val exposureTimeNs: Long? = null,
    val iso: Int? = null,
    val dngWidth: Int? = null,
    val dngHeight: Int? = null,
    val dngBytes: Long? = null,
    val mediaStoreUri: String? = null,
    val captureDurationMs: Long? = null,
    val writeDurationMs: Long? = null,
    val lastRawError: String? = null,
)

data class RawCaptureState(
    val phase: RawCapturePhase = RawCapturePhase.IDLE,
    val capability: RawCapabilityInfo = RawCapabilityInfo.Unknown,
    val diagnostics: RawCaptureDiagnostics = RawCaptureDiagnostics(),
) {
    val inProgress: Boolean
        get() = phase == RawCapturePhase.CAPTURING || phase == RawCapturePhase.SAVING

    companion object {
        val Idle = RawCaptureState()
    }
}

data class RawSavedFile(
    val uri: String,
    val bytes: Long,
    val width: Int,
    val height: Int,
)

sealed interface RawCaptureResult {
    data class Saved(
        val file: RawSavedFile,
        val diagnostics: RawCaptureDiagnostics,
    ) : RawCaptureResult

    data class Failed(
        val reason: String,
        val structural: Boolean,
        val diagnostics: RawCaptureDiagnostics,
    ) : RawCaptureResult
}

interface RawCaptureController {
    val rawCaptureState: kotlinx.coroutines.flow.StateFlow<RawCaptureState>

    /** Called by the runtime selection bridge; null immediately invalidates an in-flight frame. */
    fun updateActiveSelection(selectionGeneration: Long?, routingKey: String?)

    suspend fun captureRaw(request: RawCaptureRequest): RawCaptureResult
}
