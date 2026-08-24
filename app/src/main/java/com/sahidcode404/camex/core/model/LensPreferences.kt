package com.sahidcode404.camex.core.model

import kotlinx.serialization.Serializable

/**
 * Persisted viewfinder preference.
 *
 * Normal optical-lens records use [streamFormat]. The reserved global viewfinder record uses
 * [fpsRange], [fpsOverrideEnabled], and [highResolutionViewfinder]. [size] is retained only so
 * schema-v4 installs decode without data loss; the v5 runtime deliberately ignores it because
 * resolution is no longer a per-lens user setting.
 */
@Serializable
data class PreviewPreference(
    val size: Size2D? = null,
    val fpsRange: FpsRange? = null,
    val streamFormat: StreamFormat? = null,
    val fpsOverrideEnabled: Boolean = false,
    val highResolutionViewfinder: Boolean = false,
) {
    val isAuto: Boolean
        get() = size == null &&
            fpsRange == null &&
            streamFormat == null &&
            !fpsOverrideEnabled &&
            !highResolutionViewfinder
}

@Serializable
data class LensPreferenceRecord(
    val fingerprint: String,
    val visible: Boolean = true,
    val displayName: String? = null,
    val position: Int? = null,
    val preview: PreviewPreference = PreviewPreference(),
)

@Serializable
data class LensPreferencesState(
    val schemaVersion: Int = CURRENT_SCHEMA_VERSION,
    val records: List<LensPreferenceRecord> = emptyList(),
    val oneXReferenceFingerprint: String? = null,
    /** Last successfully previewed lens across every facing. */
    val lastSelectedFingerprint: String? = null,
    val lastSelectedRearFingerprint: String? = null,
    val lastSelectedFrontFingerprint: String? = null,
) {
    companion object {
        const val CURRENT_SCHEMA_VERSION = 5
    }
}

/** Camera-ID keyed input accepted only by the one-time v1 migration path. */
@Serializable
data class LegacyLensPreference(
    val cameraId: String,
    val visible: Boolean = true,
    val displayName: String? = null,
    val position: Int? = null,
    val isOneXReference: Boolean = false,
    val wasLastSelectedRear: Boolean = false,
    val wasLastSelectedFront: Boolean = false,
)
