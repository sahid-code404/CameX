package com.sahidcode404.camex.core.camera

import com.sahidcode404.camex.core.model.FpsRange
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
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
    fun avoidsSlowStreamWhenCompatibleFastAlternativeExists() {
        val selected = PreviewSizeSelector.select(
            candidates = listOf(
                PreviewStreamCandidate(1920, 1080, 100_000_000L),
                PreviewStreamCandidate(1280, 720, 33_333_333L),
            ),
            request = PreviewSelectionRequest(targetWidth = 1920, targetHeight = 1080),
        )

        assertEquals(1280, selected?.width)
        assertEquals(720, selected?.height)
    }

    @Test
    fun thirtyFpsIsPreferredOverTwentyFiveFpsForLivePreview() {
        val selected = PreviewSizeSelector.select(
            candidates = listOf(
                PreviewStreamCandidate(1920, 1080, 40_000_000L),
                PreviewStreamCandidate(1280, 720, 33_333_333L),
            ),
            request = PreviewSelectionRequest(targetWidth = 1920, targetHeight = 1080),
        )

        assertEquals(1280, selected?.width)
        assertEquals(720, selected?.height)
    }

    @Test
    fun fpsSelectorPrefersFixedThirtyThenHighestFloorContainingThirty() {
        assertEquals(
            FpsRange(30, 30),
            PreviewFpsSelector.select(
                listOf(FpsRange(15, 30), FpsRange(24, 30), FpsRange(30, 30), FpsRange(30, 60)),
            ),
        )
        assertEquals(
            FpsRange(24, 30),
            PreviewFpsSelector.select(listOf(FpsRange(15, 30), FpsRange(24, 30))),
        )
    }

    @Test
    fun fpsSelectorFallsBackToClosestUsableReportedRange() {
        assertEquals(
            FpsRange(24, 24),
            PreviewFpsSelector.select(listOf(FpsRange(10, 20), FpsRange(24, 24), FpsRange(60, 60))),
        )
        assertNull(PreviewFpsSelector.select(emptyList()))
    }

    @Test
    fun fallsBackToOnlyOversizedStreamAndRejectsInvalidMetadata() {
        val selected = PreviewSizeSelector.select(
            candidates = listOf(PreviewStreamCandidate(4000, 3000)),
            request = PreviewSelectionRequest(targetWidth = 1000, targetHeight = 750),
        )

        assertNotNull(selected)
        assertNull(
            PreviewSizeSelector.select(
                listOf(PreviewStreamCandidate(0, 1080), PreviewStreamCandidate(-1, 2)),
                PreviewSelectionRequest(1080, 1920),
            ),
        )
    }
}
