package com.sahidcode404.camex.core.logic

import com.sahidcode404.camex.core.model.FingerprintStrategy
import com.sahidcode404.camex.core.model.LegacyLensPreference
import com.sahidcode404.camex.core.model.LensFingerprint
import com.sahidcode404.camex.core.model.LensIdentity
import com.sahidcode404.camex.core.model.LensNodeKind
import com.sahidcode404.camex.core.model.LensPreferenceRecord
import com.sahidcode404.camex.core.model.LensPreferencesState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LensPreferenceLogicTest {
    @Test
    fun appliesVisibilityNameReferenceAndUserOrdering() {
        val first = fingerprinted("route-a", "fp-a", 0)
        val second = fingerprinted("route-b", "fp-b", 1)
        val third = fingerprinted("route-c", "fp-c", 2)
        val state = LensPreferencesState(
            records = listOf(
                LensPreferenceRecord("fp-a", visible = false, displayName = " Hidden ", position = 0),
                LensPreferenceRecord("fp-b", displayName = " Portrait ", position = 2),
                LensPreferenceRecord("fp-c", position = 1),
            ),
            oneXReferenceFingerprint = "fp-c",
        )

        val visible = LensPreferenceOrdering.resolve(listOf(first, second, third), state)

        assertEquals(listOf("fp-c", "fp-b"), visible.map { it.lens.fingerprint!!.value })
        assertEquals("Portrait", visible.last().displayName)
        assertTrue(visible.first().isOneXReference)

        val all = LensPreferenceOrdering.resolve(listOf(first, second, third), state, includeHidden = true)
        assertFalse(all.first().visible)
    }

    @Test
    fun corruptDuplicateRecordUsesLastAndInvalidPositionFallsBack() {
        val lens = fingerprinted("route", "fp", 0)
        val state = LensPreferencesState(
            records = listOf(
                LensPreferenceRecord("fp", visible = false, position = 0),
                LensPreferenceRecord("fp", visible = true, displayName = "  ", position = -7),
            ),
        )

        val resolved = LensPreferenceOrdering.resolve(listOf(lens), state).single()

        assertTrue(resolved.visible)
        assertNull(resolved.displayName)
        assertNull(resolved.position)
    }

    @Test
    fun migratesLegacyPhysicalIdBeforeSharedParentRoute() {
        val first = fingerprinted("parent", "wide-fp", 0).copy(
            identity = LensIdentity("parent", "physical-wide", "parent", LensNodeKind.PHYSICAL),
        )
        val second = fingerprinted("parent", "tele-fp", 1).copy(
            identity = LensIdentity("parent", "physical-tele", "parent", LensNodeKind.PHYSICAL),
        )
        val result = PreferenceMigration.fromLegacyCameraIds(
            legacy = listOf(
                LegacyLensPreference(
                    cameraId = "physical-tele",
                    displayName = "Reach",
                    isOneXReference = true,
                ),
            ),
            discovered = listOf(first, second),
        )

        assertTrue(result.unmappedCameraIds.isEmpty())
        assertEquals("tele-fp", result.preferences.records.single().fingerprint)
        assertEquals("Reach", result.preferences.records.single().displayName)
        assertEquals("tele-fp", result.preferences.oneXReferenceFingerprint)
    }

    @Test
    fun ambiguousOrMissingLegacyIdIsReportedNotGuessed() {
        val duplicateA = fingerprinted("shared", "a", 0)
        val duplicateB = fingerprinted("shared", "b", 1)
        val result = PreferenceMigration.fromLegacyCameraIds(
            listOf(LegacyLensPreference("shared"), LegacyLensPreference("missing")),
            listOf(duplicateA, duplicateB),
        )

        assertTrue(result.preferences.records.isEmpty())
        assertEquals(listOf("shared", "missing"), result.unmappedCameraIds)
    }

    @Test
    fun fingerprintRekeyMigratesAllReferencesAndToleratesUnknownRecords() {
        val state = LensPreferencesState(
            schemaVersion = 1,
            records = listOf(
                LensPreferenceRecord("old", displayName = "Main"),
                LensPreferenceRecord("orphan", displayName = "Keep for reconnect"),
            ),
            oneXReferenceFingerprint = "old",
            lastSelectedRearFingerprint = "old",
            lastSelectedFrontFingerprint = "orphan",
        )

        val migrated = PreferenceMigration.rekeyFingerprints(state, mapOf("old" to "new"))

        assertEquals(LensPreferencesState.CURRENT_SCHEMA_VERSION, migrated.schemaVersion)
        assertEquals(setOf("new", "orphan"), migrated.records.map { it.fingerprint }.toSet())
        assertEquals("new", migrated.oneXReferenceFingerprint)
        assertEquals("new", migrated.lastSelectedRearFingerprint)
        assertEquals("orphan", migrated.lastSelectedFrontFingerprint)
    }

    private fun fingerprinted(route: String, fingerprint: String, order: Int) = testLens(
        route,
        discoveryOrder = order,
    ).copy(
        fingerprint = LensFingerprint(fingerprint, FingerprintStrategy.STABLE_METADATA),
    )
}
