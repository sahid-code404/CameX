package com.sahidcode404.camex.core.camera

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
