package com.sahidcode404.camex.core.camera

import com.sahidcode404.camex.core.model.LensDescriptor
import com.sahidcode404.camex.core.model.LensPreferenceRecord
import com.sahidcode404.camex.core.model.PreviewPreference
import com.sahidcode404.camex.core.model.StreamFormat
import java.util.concurrent.atomic.AtomicReference

/**
 * Process-local O(1) projection of persisted per-optical-lens preview preferences.
 *
 * The durable source of truth remains LensSettingsStore. This hot-path snapshot keeps Camera2
 * request/session selection free of DataStore IO and is keyed only by canonical optical
 * fingerprints. Transport/camera IDs never become preferences. Missing/stale entries are Auto.
 */
object PreviewPreferenceRegistry {
    private val snapshot = AtomicReference<Map<String, PreviewPreference>>(emptyMap())

    fun replace(records: Collection<LensPreferenceRecord>) {
        val next = records.asSequence()
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
     * discovery/topology metadata stays untouched. An option is projected only when this exact
     * transport profile actually reports it; otherwise that field remains Auto.
     */
    fun projectForSession(lens: LensDescriptor): LensDescriptor {
        val preference = forLens(lens)
        if (preference.isAuto) return lens

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

        val requestedFps = preference.fpsRange?.takeIf { requested ->
            capabilities.previewFpsRanges.orEmpty().contains(requested)
        }
        val projectedFps = requestedFps?.let(::listOf) ?: capabilities.previewFpsRanges

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

    internal fun clearForTest() {
        snapshot.set(emptyMap())
    }
}
