package com.sahidcode404.camex.core.logic

import com.sahidcode404.camex.core.model.CapabilityFlags
import com.sahidcode404.camex.core.model.CapabilitySupport
import com.sahidcode404.camex.core.model.FingerprintStrategy
import com.sahidcode404.camex.core.model.LensCapabilities
import com.sahidcode404.camex.core.model.LensFingerprint
import com.sahidcode404.camex.core.model.LensIdentity
import com.sahidcode404.camex.core.model.LensNodeKind
import com.sahidcode404.camex.core.model.LensUsability
import com.sahidcode404.camex.core.model.PhysicalSize
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LensDuplicateFilterTest {
    @Test
    fun samePhysicalTargetIsStrongDuplicateEvidence() {
        val first = testLens("parent-a").copy(
            identity = LensIdentity("parent-a", "physical-x", "parent-a", LensNodeKind.PHYSICAL),
        )
        val second = testLens("parent-b").copy(
            identity = LensIdentity("parent-b", "physical-x", "parent-b", LensNodeKind.PHYSICAL),
            capabilities = LensCapabilities(),
        )

        assertTrue(LensDuplicateFilter.areDuplicates(first, second))
    }

    @Test
    fun equalStableFingerprintAcrossDistinctCanonicalRoutesStaysVisible() {
        val fingerprint = LensFingerprint("same", FingerprintStrategy.STABLE_METADATA)
        val first = testLens("a").copy(fingerprint = fingerprint)
        val second = testLens("b").copy(fingerprint = fingerprint, capabilities = LensCapabilities())

        assertFalse(LensDuplicateFilter.areDuplicates(first, second))
        assertEquals(2, LensDuplicateFilter.filterForSelector(listOf(first, second)).size)
    }

    @Test
    fun fallbackFingerprintAloneDoesNotCollapseNodes() {
        val fingerprint = LensFingerprint("same", FingerprintStrategy.DEVICE_SCOPED_FALLBACK)
        val first = testLens("a").copy(fingerprint = fingerprint, capabilities = LensCapabilities())
        val second = testLens("b").copy(fingerprint = fingerprint, capabilities = LensCapabilities())

        assertFalse(LensDuplicateFilter.areDuplicates(first, second))
    }

    @Test
    fun independentRoutesWithMatchingOpticsStayDistinct() {
        val first = testLens("a")
        val second = testLens("b")

        assertFalse(LensDuplicateFilter.areDuplicates(first, second))
    }

    @Test
    fun conflictingFocalOrSparseMetadataStaysDistinct() {
        assertFalse(LensDuplicateFilter.areDuplicates(testLens("wide", 4.0), testLens("tele", 9.0)))

        val sparseA = testLens("sparse-a").copy(
            capabilities = LensCapabilities(sensorPhysicalSize = PhysicalSize(5.0, 4.0)),
        )
        val sparseB = testLens("sparse-b").copy(
            capabilities = LensCapabilities(sensorPhysicalSize = PhysicalSize(5.0, 4.0)),
        )
        assertFalse(LensDuplicateFilter.areDuplicates(sparseA, sparseB))
    }

    @Test
    fun selectorKeepsBestUsableRepresentationButGroupsRetainAll() {
        val processed = testLens(
            "processed",
            usability = LensUsability.PROCESSED_ONLY,
            discoveryOrder = 0,
        ).copy(
            capabilities = testCapabilities(
                flags = CapabilityFlags(
                    backwardCompatible = CapabilitySupport.SUPPORTED,
                    raw = CapabilitySupport.UNSUPPORTED,
                ),
            ),
        )
        val raw = testLens(
            "raw",
            usability = LensUsability.RAW_NATIVE,
            discoveryOrder = 1,
        ).copy(
            identity = LensIdentity("raw-parent", "shared-physical", "raw-parent", LensNodeKind.PHYSICAL),
        )
        val routedProcessed = processed.copy(
            identity = LensIdentity(
                "processed-parent",
                "shared-physical",
                "processed-parent",
                LensNodeKind.PHYSICAL,
            ),
        )
        val groups = LensDuplicateFilter.group(listOf(routedProcessed, raw))

        assertEquals(1, groups.size)
        assertEquals(2, groups.single().members.size)
        assertEquals(raw, groups.single().representative)
        assertEquals(
            listOf(raw),
            LensDuplicateFilter.filterForSelector(listOf(routedProcessed, raw)),
        )
    }

    @Test
    fun unselectableNodeIsNeverShownInSelector() {
        val broken = testLens("broken", usability = LensUsability.BROKEN)

        assertTrue(LensDuplicateFilter.filterForSelector(listOf(broken)).isEmpty())
    }
}
