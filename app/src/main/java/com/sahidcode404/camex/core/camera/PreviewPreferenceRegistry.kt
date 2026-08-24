package com.sahidcode404.camex.core.camera

import com.sahidcode404.camex.core.model.FpsRange
import com.sahidcode404.camex.core.model.LensDescriptor
import com.sahidcode404.camex.core.model.LensPreferenceRecord
import com.sahidcode404.camex.core.model.PreviewPreference
import com.sahidcode404.camex.core.model.Size2D
import com.sahidcode404.camex.core.model.StreamFormat
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Reported stream capability projected for the settings UI without touching the live camera path. */
data class ViewfinderFormatCapability(
    val format: StreamFormat,
    val regularSizes: List<Size2D> = emptyList(),
    val maximumResolutionSizes: List<Size2D> = emptyList(),
)

/**
 * Process-local O(1) projection of persisted viewfinder preferences.
 *
 * Resolution remains per canonical optical lens. Viewfinder FPS is intentionally one global range,
 * stored in a reserved non-camera preference record so every lens follows one centralized selector.
 * The requested range is mapped only onto FPS ranges actually reported by the active profile;
 * unsupported or stale requests fall back to that profile's normal Auto behavior.
 */
object PreviewPreferenceRegistry {
    const val GLOBAL_PREVIEW_FPS_FINGERPRINT = "global_preview_fps"

    private val snapshot = AtomicReference<Map<String, PreviewPreference>>(emptyMap())
    private val mutableGlobalFpsRange = MutableStateFlow<FpsRange?>(null)
    private val mutableViewfinderCapabilities =
        MutableStateFlow<Map<String, List<ViewfinderFormatCapability>>>(emptyMap())
    private val capabilityLock = Any()

    val globalFpsRange: StateFlow<FpsRange?> = mutableGlobalFpsRange.asStateFlow()
    val viewfinderCapabilities: StateFlow<Map<String, List<ViewfinderFormatCapability>>> =
        mutableViewfinderCapabilities.asStateFlow()

    fun replace(records: Collection<LensPreferenceRecord>) {
        val globalRecord = records.firstOrNull {
            it.fingerprint == GLOBAL_PREVIEW_FPS_FINGERPRINT
        }
        mutableGlobalFpsRange.value = globalRecord
            ?.preview
            ?.fpsRange
            ?.takeIf { it.isValid && it.max > 0 }

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
     * discovery/topology metadata stays untouched. Resolution is an exact per-lens PRIVATE override.
     * Global FPS is translated to one compatible range from this exact profile instead of hardcoding
     * any device, camera ID, resolution, 30/60 FPS assumption, or vendor behavior.
     */
    fun projectForSession(lens: LensDescriptor): LensDescriptor {
        recordReportedCapabilities(lens)

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

        val requestedGlobalRange = mutableGlobalFpsRange.value
        val projectedFps = requestedGlobalRange
            ?.let { requested ->
                selectReportedRangeForRequest(capabilities.previewFpsRanges, requested)
            }
            ?.let(::listOf)
            ?: capabilities.previewFpsRanges

        if (projectedStreams == capabilities.streamConfigurations &&
            projectedFps == capabilities.previewFpsRanges
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

    private fun selectReportedRangeForRequest(
        ranges: Collection<FpsRange>?,
        requested: FpsRange,
    ): FpsRange? {
        if (!requested.isValid || requested.max <= 0) return null
        val valid = ranges.orEmpty()
            .asSequence()
            .filter { it.isValid && it.max > 0 }
            .distinct()
            .toList()
        valid.firstOrNull { it == requested }?.let { return it }

        return valid.asSequence()
            .filter { candidate ->
                candidate.max >= requested.min && candidate.min <= requested.max
            }
            .minWithOrNull(
                compareBy<FpsRange> {
                    kotlin.math.abs(it.min - requested.min) +
                        kotlin.math.abs(it.max - requested.max)
                }.thenBy { kotlin.math.abs((it.max - it.min) - (requested.max - requested.min)) }
                    .thenByDescending { it.min }
                    .thenByDescending { it.max },
            )
    }

    private fun recordReportedCapabilities(lens: LensDescriptor) {
        val keys = buildList {
            lens.fingerprint?.value?.takeIf(String::isNotBlank)?.let(::add)
            lens.identity.routingKey.takeIf(String::isNotBlank)?.let(::add)
        }.distinct()
        if (keys.isEmpty()) return

        val incoming = lens.capabilities.streamConfigurations
            .orEmpty()
            .asSequence()
            .filter { it.format != StreamFormat.UNKNOWN && it.size.isValid }
            .groupBy { it.format }
            .map { (format, configurations) ->
                ViewfinderFormatCapability(
                    format = format,
                    regularSizes = configurations
                        .asSequence()
                        .filterNot { it.maximumResolution }
                        .map { it.size }
                        .distinct()
                        .sortedWith(sizeComparator())
                        .toList(),
                    maximumResolutionSizes = configurations
                        .asSequence()
                        .filter { it.maximumResolution }
                        .map { it.size }
                        .distinct()
                        .sortedWith(sizeComparator())
                        .toList(),
                )
            }
            .sortedBy { it.format.ordinal }

        if (incoming.isEmpty()) return
        synchronized(capabilityLock) {
            val next = mutableViewfinderCapabilities.value.toMutableMap()
            keys.forEach { key ->
                next[key] = mergeCapabilities(next[key].orEmpty(), incoming)
            }
            mutableViewfinderCapabilities.value = next.toMap()
        }
    }

    private fun mergeCapabilities(
        existing: Collection<ViewfinderFormatCapability>,
        incoming: Collection<ViewfinderFormatCapability>,
    ): List<ViewfinderFormatCapability> = (existing + incoming)
        .groupBy { it.format }
        .map { (format, entries) ->
            ViewfinderFormatCapability(
                format = format,
                regularSizes = entries
                    .asSequence()
                    .flatMap { it.regularSizes.asSequence() }
                    .distinct()
                    .sortedWith(sizeComparator())
                    .toList(),
                maximumResolutionSizes = entries
                    .asSequence()
                    .flatMap { it.maximumResolutionSizes.asSequence() }
                    .distinct()
                    .sortedWith(sizeComparator())
                    .toList(),
            )
        }
        .sortedBy { it.format.ordinal }

    private fun sizeComparator(): Comparator<Size2D> =
        compareByDescending<Size2D> { it.area ?: 0L }
            .thenByDescending { it.width }
            .thenByDescending { it.height }

    internal fun clearForTest() {
        snapshot.set(emptyMap())
        mutableGlobalFpsRange.value = null
        synchronized(capabilityLock) {
            mutableViewfinderCapabilities.value = emptyMap()
        }
    }
}
