package com.sahidcode404.camex.core.camera

import com.sahidcode404.camex.core.model.FpsRange
import com.sahidcode404.camex.core.model.LensDescriptor
import com.sahidcode404.camex.core.model.LensPreferenceRecord
import com.sahidcode404.camex.core.model.PreviewPreference
import com.sahidcode404.camex.core.model.StreamFormat
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Process-local O(1) projection of persisted preview preferences.
 *
 * Resolution remains per canonical optical lens. Preview FPS is intentionally one global target,
 * stored in a reserved non-camera preference record so every lens follows one centralized selector.
 * The target is mapped only onto FPS ranges actually reported by the active profile; unsupported or
 * stale targets fall back to that profile's normal Auto behavior.
 */
object PreviewPreferenceRegistry {
    const val GLOBAL_PREVIEW_FPS_FINGERPRINT = "global_preview_fps"

    private val snapshot = AtomicReference<Map<String, PreviewPreference>>(emptyMap())
    private val mutableGlobalFpsTarget = MutableStateFlow<Int?>(null)

    val globalFpsTarget: StateFlow<Int?> = mutableGlobalFpsTarget.asStateFlow()

    fun replace(records: Collection<LensPreferenceRecord>) {
        val globalRecord = records.firstOrNull {
            it.fingerprint == GLOBAL_PREVIEW_FPS_FINGERPRINT
        }
        mutableGlobalFpsTarget.value = globalRecord
            ?.preview
            ?.fpsRange
            ?.max
            ?.takeIf { it > 0 }

        val next = records.asSequence()
            .filterNot { it.fingerprint == GLOBAL_PREVIEW_FPS_FINGERPRINT }
            .mapNotNull { record ->
                record.fingerprint.takeIf(String::isNotBlank)?.let { it to record.preview }
            }
            .toMap()
        snapshot.set(next)
    }

    fun forLens(lens: LensDescriptor): PreviewPreference = lens.fingerprint
        ?.value
        ?.let { snapshot.get()[it] }
        ?: PreviewPreference()

    /**
     * Builds the session-only capability view used by CameraSessionController. The original
     * discovery/topology metadata stays untouched. Resolution is an exact per-lens override. Global
     * FPS is translated to one compatible range from this exact profile instead of hardcoding 30/60.
     */
    fun projectForSession(lens: LensDescriptor): LensDescriptor {
        val preference = forLens(lens)
        val capabilities = lens.capabilities

        val requestedSize = preference.size
        val hasRequestedSize = requestedSize != null && capabilities
            .configurations(StreamFormat.PRIVATE)
            .any { configuration ->
                !configuration.maximumResolution && configuration.size == requestedSize
            }
        val projectedStreams = if (hasRequestedSize) {
            capabilities.streamConfigurations?.filter { configuration ->
                configuration.format != StreamFormat.PRIVATE ||
                    configuration.maximumResolution ||
                    configuration.size == requestedSize
            }
        } else {
            capabilities.streamConfigurations
        }

        val globalTarget = mutableGlobalFpsTarget.value
        val projectedFps = globalTarget
            ?.let { target -> selectReportedRangeForTarget(capabilities.previewFpsRanges, target) }
            ?.let(::listOf)
            ?: capabilities.previewFpsRanges

        if (projectedStreams === capabilities.streamConfigurations &&
            projectedFps === capabilities.previewFpsRanges
        ) {
            return lens
        }
        return lens.copy(
            capabilities = capabilities.copy(
                streamConfigurations = projectedStreams,
                previewFpsRanges = projectedFps,
            ),
        )
    }

    private fun selectReportedRangeForTarget(
        ranges: Collection<FpsRange>?,
        target: Int,
    ): FpsRange? = ranges
        .orEmpty()
        .asSequence()
        .filter { it.isValid && it.max > 0 && target in it.min..it.max }
        .minWithOrNull(
            compareBy<FpsRange> { kotlin.math.abs(it.max - target) }
                .thenBy { it.max - it.min }
                .thenByDescending { it.min },
        )

    internal fun clearForTest() {
        snapshot.set(emptyMap())
        mutableGlobalFpsTarget.value = null
    }
}
