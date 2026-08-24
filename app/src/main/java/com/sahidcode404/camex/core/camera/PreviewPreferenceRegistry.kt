package com.sahidcode404.camex.core.camera

import com.sahidcode404.camex.core.model.FpsRange
import com.sahidcode404.camex.core.model.LensDescriptor
import com.sahidcode404.camex.core.model.LensPreferenceRecord
import com.sahidcode404.camex.core.model.PreviewPreference
import com.sahidcode404.camex.core.model.Size2D
import com.sahidcode404.camex.core.model.StreamConfiguration
import com.sahidcode404.camex.core.model.StreamFormat
import com.sahidcode404.camex.core.model.isLiveViewfinderStream
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Reported Camera2 output capability projected for settings without opening a camera. */
data class ViewfinderFormatCapability(
    val format: StreamFormat,
    val regularSizes: List<Size2D> = emptyList(),
    val maximumResolutionSizes: List<Size2D> = emptyList(),
    val selectableLiveStream: Boolean = format.isLiveViewfinderStream,
)

/**
 * O(1) process-local projection of persisted viewfinder preferences.
 *
 * The topology/discovery models remain pristine. A session receives a short-lived descriptor copy
 * containing only preferences that the exact active profile can satisfy. Stale/unsupported values
 * always collapse back to the normal Camera2 PRIVATE Auto path.
 */
object PreviewPreferenceRegistry {
    const val GLOBAL_PREVIEW_FPS_FINGERPRINT = "global_preview_fps"

    private val snapshot = AtomicReference<Map<String, PreviewPreference>>(emptyMap())
    private val mutablePreferences = MutableStateFlow<Map<String, PreviewPreference>>(emptyMap())
    private val mutableGlobalFpsRange = MutableStateFlow<FpsRange?>(null)
    private val mutableFpsOverrideEnabled = MutableStateFlow(false)
    private val mutableHighResolutionViewfinder = MutableStateFlow(false)
    private val mutableViewfinderCapabilities =
        MutableStateFlow<Map<String, List<ViewfinderFormatCapability>>>(emptyMap())
    private val capabilityLock = Any()

    val preferences: StateFlow<Map<String, PreviewPreference>> = mutablePreferences.asStateFlow()
    val globalFpsRange: StateFlow<FpsRange?> = mutableGlobalFpsRange.asStateFlow()
    val fpsOverrideEnabled: StateFlow<Boolean> = mutableFpsOverrideEnabled.asStateFlow()
    val highResolutionViewfinder: StateFlow<Boolean> =
        mutableHighResolutionViewfinder.asStateFlow()
    val viewfinderCapabilities: StateFlow<Map<String, List<ViewfinderFormatCapability>>> =
        mutableViewfinderCapabilities.asStateFlow()

    fun replace(records: Collection<LensPreferenceRecord>) {
        val global = records.firstOrNull { it.fingerprint == GLOBAL_PREVIEW_FPS_FINGERPRINT }
            ?.preview
            ?: PreviewPreference()
        mutableGlobalFpsRange.value = global.fpsRange?.takeIf { it.isValid && it.max > 0 }
        mutableFpsOverrideEnabled.value = global.fpsOverrideEnabled
        mutableHighResolutionViewfinder.value = global.highResolutionViewfinder

        val next = records.asSequence()
            .filterNot { it.fingerprint == GLOBAL_PREVIEW_FPS_FINGERPRINT }
            .mapNotNull { record ->
                record.fingerprint.takeIf(String::isNotBlank)?.let { it to record.preview }
            }
            .toMap()
        snapshot.set(next)
        mutablePreferences.value = next
    }

    fun forLens(lens: LensDescriptor): PreviewPreference = lens.fingerprint
        ?.value
        ?.let { snapshot.get()[it] }
        ?: PreviewPreference()

    /**
     * Builds the descriptor consumed only by the live Camera2 session owner.
     *
     * High-resolution viewfinder means the largest *regular* stream configuration the active
     * profile reports for each live viewfinder format. It intentionally does not force Camera2's
     * maximum-resolution sensor-pixel mode, because many devices cannot sustain that mode as a
     * repeating preview and doing so would violate the universal safe-fallback contract.
     */
    fun projectForSession(lens: LensDescriptor): LensDescriptor {
        recordReportedCapabilities(lens)
        val capabilities = lens.capabilities
        val preference = forLens(lens)
        val selectedStream = selectLiveStream(capabilities.streamConfigurations, preference.streamFormat)

        val projectedStreams = if (mutableHighResolutionViewfinder.value) {
            keepHighestRegularLiveStreams(capabilities.streamConfigurations)
        } else {
            capabilities.streamConfigurations
        }

        val projectedFps = if (mutableFpsOverrideEnabled.value) {
            mutableGlobalFpsRange.value
                ?.let { requested ->
                    selectReportedRangeForRequest(capabilities.previewFpsRanges, requested)
                }
                ?.let(::listOf)
                ?: capabilities.previewFpsRanges
        } else {
            capabilities.previewFpsRanges
        }

        if (projectedStreams == capabilities.streamConfigurations &&
            projectedFps == capabilities.previewFpsRanges &&
            selectedStream == lens.previewStreamFormat
        ) {
            return lens
        }
        return lens.copy(
            previewStreamFormat = selectedStream,
            capabilities = capabilities.copy(
                streamConfigurations = projectedStreams,
                previewFpsRanges = projectedFps,
            ),
        )
    }

    private fun selectLiveStream(
        configurations: Collection<StreamConfiguration>?,
        requested: StreamFormat?,
    ): StreamFormat? {
        val available = configurations.orEmpty()
            .asSequence()
            .filter { !it.maximumResolution && it.size.isValid && it.format.isLiveViewfinderStream }
            .map { it.format }
            .toSet()
        if (StreamFormat.PRIVATE !in available) return null
        return requested
            ?.takeIf { it.isLiveViewfinderStream && it in available }
            ?: StreamFormat.PRIVATE
    }

    private fun keepHighestRegularLiveStreams(
        configurations: List<StreamConfiguration>?,
    ): List<StreamConfiguration>? {
        configurations ?: return null
        val highest = configurations.asSequence()
            .filter { !it.maximumResolution && it.size.isValid && it.format.isLiveViewfinderStream }
            .groupBy { it.format }
            .mapValues { (_, entries) ->
                entries.maxWithOrNull(
                    compareBy<StreamConfiguration> { it.size.area ?: 0L }
                        .thenBy { it.size.width }
                        .thenBy { it.size.height },
                )
            }
        if (highest.isEmpty()) return configurations
        return configurations.filter { configuration ->
            !configuration.format.isLiveViewfinderStream ||
                configuration.maximumResolution ||
                highest[configuration.format] === configuration
        }
    }

    internal fun selectReportedRangeForRequest(
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
        mutablePreferences.value = emptyMap()
        mutableGlobalFpsRange.value = null
        mutableFpsOverrideEnabled.value = false
        mutableHighResolutionViewfinder.value = false
        synchronized(capabilityLock) {
            mutableViewfinderCapabilities.value = emptyMap()
        }
    }
}
