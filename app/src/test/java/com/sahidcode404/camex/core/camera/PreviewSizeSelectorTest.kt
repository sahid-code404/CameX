package com.sahidcode404.camex.core.camera

import com.sahidcode404.camex.core.model.FpsRange
import com.sahidcode404.camex.core.model.PreviewPreference
import com.sahidcode404.camex.core.model.Size2D
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PreviewSizeSelectorTest {
    @Test
    fun selectsSmallestAspectMatchedStreamThatCoversTarget() {
        val selected = PreviewSizeSelector.select(
            candidates = listOf(
                PreviewStreamCandidate(640, 480, 16_666_667L),
                PreviewStreamCandidate(1280, 720, 16_666_667L),
                PreviewStreamCandidate(1920, 1080, 33_333_333L),
                PreviewStreamCandidate(3840, 2160, 66_666_667L),
            ),
            request = PreviewSelectionRequest(targetWidth = 720, targetHeight = 1280),
        )

        assertEquals(1280, selected?.width)
        assertEquals(720, selected?.height)
    }

    @Test
    fun metadataPreferredFpsAvoidsSlowerCompatibleStream() {
        val selected = PreviewSizeSelector.select(
            candidates = listOf(
                PreviewStreamCandidate(1920, 1080, 40_000_000L),
                PreviewStreamCandidate(1280, 720, 16_666_667L),
            ),
            request = PreviewSelectionRequest(
                targetWidth = 1920,
                targetHeight = 1080,
                preferredMinimumFps = 60.0,
            ),
        )

        assertEquals(1280, selected?.width)
        assertEquals(720, selected?.height)
    }

    @Test
    fun noApplicationPreviewCapRejectsNothingByDefault() {
        val selected = PreviewSizeSelector.select(
            candidates = listOf(PreviewStreamCandidate(4000, 3000)),
            request = PreviewSelectionRequest(targetWidth = 1000, targetHeight = 750),
        )

        assertNotNull(selected)
    }

    @Test
    fun invalidMetadataIsRejected() {
        assertNull(
            PreviewSizeSelector.select(
                listOf(PreviewStreamCandidate(0, 1080), PreviewStreamCandidate(-1, 2)),
                PreviewSelectionRequest(1080, 1920),
            ),
        )
    }

    @Test
    fun manualPreviewSizeIsExactAndStaleChoiceFallsBackToAuto() {
        val candidates = listOf(
            PreviewStreamCandidate(1280, 720, 16_666_667L),
            PreviewStreamCandidate(1920, 1080, 33_333_333L),
        )
        val request = PreviewSelectionRequest(1080, 1920, preferredMinimumFps = 60.0)

        val manual = PreviewPreferenceResolver.selectStream(
            candidates,
            request,
            PreviewPreference(size = Size2D(1920, 1080)),
        )
        assertEquals(1920, manual?.width)
        assertEquals(1080, manual?.height)
        assertFalse(
            PreviewPreferenceResolver.didFallbackSize(
                candidates,
                PreviewPreference(size = Size2D(1920, 1080)),
            ),
        )

        val stalePreference = PreviewPreference(size = Size2D(2222, 1111))
        val fallback = PreviewPreferenceResolver.selectStream(candidates, request, stalePreference)
        assertEquals(1280, fallback?.width)
        assertEquals(720, fallback?.height)
        assertTrue(PreviewPreferenceResolver.didFallbackSize(candidates, stalePreference))
    }

    @Test
    fun autoFpsUsesHighestReportedNormalPreviewRange() {
        val target = requireNotNull(
            PreviewFpsSelector.preferredTargetFps(
                listOf(
                    FpsRange(15, 30),
                    FpsRange(24, 30),
                    FpsRange(30, 30),
                    FpsRange(30, 60),
                    FpsRange(60, 60),
                ),
            ),
        )
        assertEquals(60.0, target, 0.0)
        assertNull(PreviewFpsSelector.preferredTargetFps(emptyList()))
    }

    @Test
    fun requestedFpsMustBeReportedAndSustainableBySelectedStream() {
        val ranges = listOf(FpsRange(15, 30), FpsRange(30, 30), FpsRange(60, 60))

        assertEquals(
            FpsRange(30, 30),
            PreviewFpsSelector.selectForStream(
                ranges = ranges,
                minimumFrameDurationNanos = 33_333_333L,
                requested = FpsRange(30, 30),
            ),
        )
        assertNull(
            PreviewFpsSelector.selectForStream(
                ranges = listOf(FpsRange(60, 60)),
                minimumFrameDurationNanos = 33_333_333L,
                requested = FpsRange(60, 60),
            ),
        )
        assertEquals(
            FpsRange(30, 30),
            PreviewFpsSelector.selectForStream(
                ranges = ranges,
                minimumFrameDurationNanos = 33_333_333L,
                requested = FpsRange(24, 24),
            ),
        )
    }

    @Test
    fun selectedStreamFrameDurationBoundsAdvertisedFpsRange() {
        val selected = PreviewFpsSelector.selectForStream(
            ranges = listOf(
                FpsRange(15, 30),
                FpsRange(30, 30),
                FpsRange(30, 60),
                FpsRange(60, 60),
            ),
            minimumFrameDurationNanos = 33_333_333L,
        )

        assertEquals(FpsRange(30, 30), selected)
    }

    @Test
    fun unknownStreamDurationUsesBestRangeActuallyReportedByCamera() {
        val selected = PreviewFpsSelector.selectForStream(
            ranges = listOf(FpsRange(15, 30), FpsRange(30, 60), FpsRange(60, 60)),
            minimumFrameDurationNanos = null,
        )

        assertEquals(FpsRange(60, 60), selected)
    }

    @Test
    fun noReportedFpsRangeLeavesAeUnconstrained() {
        assertNull(PreviewFpsSelector.selectForStream(emptyList(), 16_666_667L))
    }
}
