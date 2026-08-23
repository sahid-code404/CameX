package com.sahidcode404.camex.core.logic

import com.sahidcode404.camex.core.model.LegacyLensPreference
import com.sahidcode404.camex.core.model.LensDescriptor
import com.sahidcode404.camex.core.model.LensPreferenceRecord
import com.sahidcode404.camex.core.model.LensPreferencesState

data class ResolvedLensPreference(
    val lens: LensDescriptor,
    val visible: Boolean,
    val displayName: String?,
    val position: Int?,
    val isOneXReference: Boolean,
)

object LensPreferenceOrdering {
    fun resolve(
        lenses: List<LensDescriptor>,
        preferences: LensPreferencesState,
        includeHidden: Boolean = false,
    ): List<ResolvedLensPreference> {
        // A corrupt store may contain duplicates. Last record wins deterministically.
        val records = preferences.records
            .filter { it.fingerprint.isNotBlank() }
            .associateBy { it.fingerprint }
        return lenses.map { lens ->
            val fingerprint = lens.fingerprint?.value
            val record = fingerprint?.let(records::get)
            ResolvedLensPreference(
                lens = lens,
                visible = record?.visible ?: true,
                displayName = record?.displayName?.trim()?.takeIf(String::isNotEmpty),
                position = record?.position?.takeIf { it >= 0 },
                isOneXReference = fingerprint != null &&
                    fingerprint == preferences.oneXReferenceFingerprint,
            )
        }.filter { includeHidden || it.visible }
            .sortedWith(
                compareBy<ResolvedLensPreference> { it.position ?: Int.MAX_VALUE }
                    .thenBy { it.lens.discoveryOrder }
                    .thenBy { it.lens.fingerprint?.value.orEmpty() },
            )
    }
}

data class PreferenceMigrationResult(
    val preferences: LensPreferencesState,
    val unmappedCameraIds: List<String>,
)

object PreferenceMigration {
    /**
     * One-time migration from camera IDs. A record is migrated only when it maps unambiguously to
     * one discovered fingerprint. Physical IDs are considered before public IDs so a logical
     * parent's ID cannot accidentally rename every child lens.
     */
    fun fromLegacyCameraIds(
        legacy: List<LegacyLensPreference>,
        discovered: List<LensDescriptor>,
    ): PreferenceMigrationResult {
        val records = mutableListOf<LensPreferenceRecord>()
        val unmapped = mutableListOf<String>()
        var reference: String? = null
        var lastRear: String? = null
        var lastFront: String? = null

        legacy.forEach { old ->
            val physicalMatches = discovered.filter { it.identity.physicalCameraId == old.cameraId }
            val candidates = if (physicalMatches.isNotEmpty()) {
                physicalMatches
            } else {
                discovered.filter {
                    it.identity.physicalCameraId == null && it.identity.publicCameraId == old.cameraId
                }
            }
            val fingerprints = candidates.mapNotNull { it.fingerprint?.value }.distinct()
            if (fingerprints.size != 1) {
                unmapped += old.cameraId
                return@forEach
            }
            val fingerprint = fingerprints.single()
            records += LensPreferenceRecord(
                fingerprint = fingerprint,
                visible = old.visible,
                displayName = old.displayName,
                position = old.position?.takeIf { it >= 0 },
            )
            if (old.isOneXReference) reference = fingerprint
            if (old.wasLastSelectedRear) lastRear = fingerprint
            if (old.wasLastSelectedFront) lastFront = fingerprint
        }

        return PreferenceMigrationResult(
            preferences = LensPreferencesState(
                records = records.associateBy { it.fingerprint }.values.toList(),
                oneXReferenceFingerprint = reference,
                lastSelectedFingerprint = lastRear ?: lastFront,
                lastSelectedRearFingerprint = lastRear,
                lastSelectedFrontFingerprint = lastFront,
            ),
            unmappedCameraIds = unmapped,
        )
    }

    /** Re-keys known old fingerprints after a migration; unknown records are safely retained. */
    fun rekeyFingerprints(
        current: LensPreferencesState,
        aliases: Map<String, String>,
    ): LensPreferencesState {
        fun remap(value: String?): String? = value?.let { aliases[it]?.takeIf(String::isNotBlank) ?: it }
        val records = current.records.mapNotNull { record ->
            val key = remap(record.fingerprint)?.takeIf(String::isNotBlank) ?: return@mapNotNull null
            record.copy(fingerprint = key)
        }.associateBy { it.fingerprint }.values.toList()
        return current.copy(
            schemaVersion = LensPreferencesState.CURRENT_SCHEMA_VERSION,
            records = records,
            oneXReferenceFingerprint = remap(current.oneXReferenceFingerprint),
            lastSelectedFingerprint = remap(current.lastSelectedFingerprint),
            lastSelectedRearFingerprint = remap(current.lastSelectedRearFingerprint),
            lastSelectedFrontFingerprint = remap(current.lastSelectedFrontFingerprint),
        )
    }
}
