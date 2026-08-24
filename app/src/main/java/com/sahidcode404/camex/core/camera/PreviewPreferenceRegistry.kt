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
import kotlin.math.abs
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

    private const val DEFAULT_RESPONSIVE_PREVIEW_FPS = 30.0
    private const val FPS_TOLERANCE = 0.75

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
     * Auto frame rate deliberately leaves CONTROL_AE_TARGET_FPS_RANGE unconstrained so the active
     * HAL can use its own preview-tuned cadence, matching the behavior users expect from GCam-like
     * photo preview. Only the explicit global override is projected as a Camera2 FPS range.
     *
     * High-resolution viewfinder is also cadence-aware: it picks the largest regular live stream
     * that can sustain the requested cadence (30 fps in Auto, or the override upper threshold).
     * If no stream can sustain that cadence, it picks the fastest reported stream rather than
     * blindly selecting the largest slow stream.
     */
    fun projectForSession(lens: LensDescriptor): LensDescriptor {
        recordReportedCapabilities(lens)
        val capabilities = lens.capabilities
        val preference = forLens(lens)
        val selectedStream = selectLiveStream(capabilities.streamConfigurations, preference.streamFormat)

        val responsiveTargetFps = if (mutableFpsOverrideEnabled.value) {
            mutableGlobalFpsRange.value?.max?.toDouble()
                ?.takeIf { it.isFinite() && it > 0.0 }
                ?: DEFAULT_RESPONSIVE_PREVIEW_FPS
        } else {
            DEFAULT_RESPONSIVE_PREVIEW_FPS
        }

        val projectedStreams = if (mutableHighResolutionViewfinder.value) {
            keepHighestResponsiveLiveStreams(
                configurations = capabilities.streamConfigurations,
                targetFps = responsiveTargetFps,
            )
        } else {
            capabilities.streamConfigurations
        }

        val projectedFps: List<FpsRange>? = if (mutableFpsOverrideEnabled.value) {
            mutableGlobalFpsRange.value
                ?.let { requested ->
                    selectReportedRangeForRequest(capabilities.previewFpsRanges, requested)
                }
                ?.let(::listOf)
                ?: emptyList()
        } else {
            // Empty means the controller does not set CONTROL_AE_TARGET_FPS_RANGE. This is the
            // smooth Auto path; reported ranges remain available through settings/diagnostics.
            emptyList()
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

    private fun keepHighestResponsiveLiveStreams(
        configurations: List<StreamConfiguration>?,
        targetFps: Double,
    ): List<StreamConfiguration>? {
        configurations ?: return null
        val selected = configurations.asSequence()
            .filter { !it.maximumResolution && it.size.isValid && it.format.isLiveViewfinderStream }
            .groupBy { it.format }
            .mapValues { (_, entries) ->
                selectHighestResponsiveConfiguration(entries, targetFps)
            }
        if (selected.isEmpty()) return configurations

        return configurations.filter { configuration ->
            !configuration.format.isLiveViewfinderStream ||
                configuration.maximumResolution ||
                selected[configuration.format] === configuration
        }
    }

    private fun selectHighestResponsiveConfiguration(
        entries: List<StreamConfiguration>,
        targetFps: Double,
    ): StreamConfiguration? {
        if (entries.isEmpty()) return null

        val known = entries.mapNotNull { configuration ->
            configuration.estimatedMaximumFps()?.let { fps -> configuration to fps }
        }
        val sustainable = known
            .filter { (_, fps) -> fps + FPS_TOLERANCE >= targetFps }
            .map { it.first }

        if (sustainable.isNotEmpty()) {
            return sustainable.maxWithOrNull(resolutionComparator())
        }

        // Unknown timing is safer than knowingly forcing a stream whose metadata proves it is slow.
        val unknown = entries.filter { it.estimatedMaximumFps() == null }
        if (unknown.isNotEmpty()) {
            return unknown.maxWithOrNull(resolutionComparator())
        }

        val fastest = known.maxOfOrNull { it.second }
            ?: return entries.maxWithOrNull(resolutionComparator())
        return known.asSequence()
            .filter { (_, fps) -> abs(fps - fastest) <= FPS_TOLERANCE }
            .map { it.first }
            .maxWithOrNull(resolutionComparator())
    }

    private fun StreamConfiguration.estimatedMaximumFps(): Double? = minFrameDurationNs
        ?.takeIf { it > 0L }
        ?.let { 1_000_000_000.0 / it.toDouble() }

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
                    abs(it.min - requested.min) + abs(it.max - requested.max)
                }.thenBy { abs((it.max - it.min) - (requested.max - requested.min)) }
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

    private fun resolutionComparator(): Comparator<StreamConfiguration> =
        compareBy<StreamConfiguration> { it.size.area ?: 0L }
            .thenBy { it.size.width }
            .thenBy { it.size.height }

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
