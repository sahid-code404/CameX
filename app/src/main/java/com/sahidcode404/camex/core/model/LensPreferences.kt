package com.sahidcode404.camex.core.model

import kotlinx.serialization.Serializable

/**
 * Per-canonical-lens live-preview preference. Null fields mean Auto and are intentionally
 * capability-driven at runtime. Values are never camera IDs and stale values fall back safely.
 */
@Serializable
data class PreviewPreference(
    val size: Size2D? = null,
    val fpsRange: FpsRange? = null,
) {
    val isAuto: Boolean get() = size == null && fpsRange == null
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
        const val CURRENT_SCHEMA_VERSION = 4
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
