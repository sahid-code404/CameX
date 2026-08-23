package com.sahidcode404.camex.core.logic

import com.sahidcode404.camex.core.model.FingerprintStrategy
import com.sahidcode404.camex.core.model.LensCategory
import com.sahidcode404.camex.core.model.LensFacing
import com.sahidcode404.camex.core.model.LensFingerprint
import com.sahidcode404.camex.core.model.LensUsability
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PrimaryLensSelectorTest {
    @Test
    fun choosesWideOpticsWithoutUsingCameraId() {
        val teleWithConventionalId = testLens(
            id = "0",
            focalMm = 12.0,
            category = LensCategory.PHOTOGRAPHIC_TELEPHOTO,
            discoveryOrder = 0,
        )
        val wideWithArbitraryId = testLens(
            id = "camera-zeta",
            focalMm = 4.0,
            category = LensCategory.PHOTOGRAPHIC_WIDE,
            discoveryOrder = 1,
        )

        assertEquals(wideWithArbitraryId, PrimaryLensSelector.select(listOf(teleWithConventionalId, wideWithArbitraryId)))
    }

    @Test
    fun explicitFingerprintReferenceWinsAmongEligibleRearLenses() {
        val wideFingerprint = LensFingerprint("wide", FingerprintStrategy.STABLE_METADATA)
        val teleFingerprint = LensFingerprint("tele", FingerprintStrategy.STABLE_METADATA)
        val wide = testLens("wide").copy(fingerprint = wideFingerprint)
        val tele = testLens("tele", focalMm = 10.0).copy(fingerprint = teleFingerprint)

        assertEquals(tele, PrimaryLensSelector.select(listOf(wide, tele), teleFingerprint))
    }

    @Test
    fun excludesFrontAuxiliaryAndUnusableNodes() {
        val front = testLens("front", facing = LensFacing.FRONT)
        val depth = testLens(
            "depth",
            category = LensCategory.NON_PHOTO_DEPTH,
            usability = LensUsability.DEPTH_AUXILIARY,
        )
        val broken = testLens("broken", usability = LensUsability.BROKEN)

        assertNull(PrimaryLensSelector.select(listOf(front, depth, broken)))
    }

    @Test
    fun fovEvidenceOutranksRawFeatureCountForLikelyMainLens() {
        val rawUltra = testLens(
            "ultra",
            focalMm = 2.0,
            usability = LensUsability.RAW_NATIVE,
            category = LensCategory.PHOTOGRAPHIC_ULTRAWIDE,
        )
        val processedWide = testLens(
            "wide",
            focalMm = 4.0,
            usability = LensUsability.PROCESSED_ONLY,
            category = LensCategory.PHOTOGRAPHIC_WIDE,
        )

        assertEquals(processedWide, PrimaryLensSelector.select(listOf(rawUltra, processedWide)))
    }
}
