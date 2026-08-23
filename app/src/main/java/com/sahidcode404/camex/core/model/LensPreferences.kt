package com.sahidcode404.camex.core.model

import kotlinx.serialization.Serializable

@Serializable
data class LensPreferenceRecord(
    val fingerprint: String,
    val visible: Boolean = true,
    val displayName: String? = null,
    val position: Int? = null,
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
        const val CURRENT_SCHEMA_VERSION = 3
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
