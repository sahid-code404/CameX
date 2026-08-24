package com.sahidcode404.camex.core.camera

import com.sahidcode404.camex.core.model.FpsRange
import com.sahidcode404.camex.core.model.LensCapabilities
import com.sahidcode404.camex.core.model.LensDescriptor
import com.sahidcode404.camex.core.model.LensIdentity
import com.sahidcode404.camex.core.model.LensPreferenceRecord
import com.sahidcode404.camex.core.model.PreviewPreference
import com.sahidcode404.camex.core.model.Size2D
import com.sahidcode404.camex.core.model.StreamConfiguration
import com.sahidcode404.camex.core.model.StreamFormat
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PreviewPreferenceRegistryTest {
    @After
    fun tearDown() {
        PreviewPreferenceRegistry.clearForTest()
    }

    @Test
    fun globalRangeMapsToExactReportedRange() {
        PreviewPreferenceRegistry.replace(
            listOf(
                LensPreferenceRecord(
                    fingerprint = PreviewPreferenceRegistry.GLOBAL_PREVIEW_FPS_FINGERPRINT,
                    preview = PreviewPreference(fpsRange = FpsRange(15, 30)),
                ),
            ),
        )
        val projected = PreviewPreferenceRegistry.projectForSession(
            lens(FpsRange(15, 30), FpsRange(30, 60), FpsRange(60, 60)),
        )

        assertEquals(FpsRange(15, 30), PreviewPreferenceRegistry.globalFpsRange.value)
        assertEquals(listOf(FpsRange(15, 30)), projected.capabilities.previewFpsRanges)
    }

    @Test
    fun globalRangeMapsToNearestOverlappingRangeReportedByProfile() {
        PreviewPreferenceRegistry.replace(
            listOf(
                LensPreferenceRecord(
                    fingerprint = PreviewPreferenceRegistry.GLOBAL_PREVIEW_FPS_FINGERPRINT,
                    preview = PreviewPreference(fpsRange = FpsRange(20, 50)),
                ),
            ),
        )
        val projected = PreviewPreferenceRegistry.projectForSession(
            lens(FpsRange(15, 30), FpsRange(30, 60)),
        )

        assertEquals(listOf(FpsRange(30, 60)), projected.capabilities.previewFpsRanges)
    }

    @Test
    fun unsupportedGlobalRangeFallsBackToProfileAutoRanges() {
        val original = listOf(FpsRange(15, 30), FpsRange(30, 30))
        PreviewPreferenceRegistry.replace(
            listOf(
                LensPreferenceRecord(
                    fingerprint = PreviewPreferenceRegistry.GLOBAL_PREVIEW_FPS_FINGERPRINT,
                    preview = PreviewPreference(fpsRange = FpsRange(60, 60)),
                ),
            ),
        )
        val projected = PreviewPreferenceRegistry.projectForSession(lens(*original.toTypedArray()))

        assertEquals(original, projected.capabilities.previewFpsRanges)
    }

    @Test
    fun oldPerLensFpsRecordDoesNotOverrideCentralAutoMode() {
        PreviewPreferenceRegistry.replace(
            listOf(
                LensPreferenceRecord(
                    fingerprint = "lensfingerprint",
                    preview = PreviewPreference(fpsRange = FpsRange(60, 60)),
                ),
            ),
        )
        val original = listOf(FpsRange(15, 30), FpsRange(30, 30))
        val projected = PreviewPreferenceRegistry.projectForSession(lens(*original.toTypedArray()))

        assertNull(PreviewPreferenceRegistry.globalFpsRange.value)
        assertEquals(original, projected.capabilities.previewFpsRanges)
    }

    @Test
    fun reportedOutputFormatsAreGroupedForViewfinderSettings() {
        val descriptor = LensDescriptor(
            identity = LensIdentity("camera-alpha"),
            capabilities = LensCapabilities(
                streamConfigurations = listOf(
                    StreamConfiguration(StreamFormat.PRIVATE, Size2D(1920, 1080)),
                    StreamConfiguration(StreamFormat.PRIVATE, Size2D(1280, 720)),
                    StreamConfiguration(StreamFormat.YUV_420_888, Size2D(1920, 1080)),
                    StreamConfiguration(StreamFormat.RAW_SENSOR, Size2D(4000, 3000)),
                    StreamConfiguration(
                        StreamFormat.RAW_SENSOR,
                        Size2D(8000, 6000),
                        maximumResolution = true,
                    ),
                ),
            ),
        )

        PreviewPreferenceRegistry.projectForSession(descriptor)

        val capabilities = requireNotNull(
            PreviewPreferenceRegistry.viewfinderCapabilities.value[descriptor.identity.routingKey],
        )
        assertTrue(capabilities.any { it.format == StreamFormat.PRIVATE })
        assertTrue(capabilities.any { it.format == StreamFormat.YUV_420_888 })
        val raw = requireNotNull(capabilities.firstOrNull { it.format == StreamFormat.RAW_SENSOR })
        assertEquals(listOf(Size2D(4000, 3000)), raw.regularSizes)
        assertEquals(listOf(Size2D(8000, 6000)), raw.maximumResolutionSizes)
    }

    private fun lens(vararg ranges: FpsRange) = LensDescriptor(
        identity = LensIdentity("camera-alpha"),
        capabilities = LensCapabilities(previewFpsRanges = ranges.toList()),
    )
}
