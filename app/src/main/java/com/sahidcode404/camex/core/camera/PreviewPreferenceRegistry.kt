package com.sahidcode404.camex.core.camera

import com.sahidcode404.camex.core.model.LensDescriptor
import com.sahidcode404.camex.core.model.LensPreferenceRecord
import com.sahidcode404.camex.core.model.PreviewPreference
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

    internal fun clearForTest() {
        snapshot.set(emptyMap())
    }
}
