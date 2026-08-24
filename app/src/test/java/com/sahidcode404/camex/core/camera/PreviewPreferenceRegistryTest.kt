package com.sahidcode404.camex.core.camera

import com.sahidcode404.camex.core.model.FingerprintStrategy
import com.sahidcode404.camex.core.model.FpsRange
import com.sahidcode404.camex.core.model.LensCapabilities
import com.sahidcode404.camex.core.model.LensDescriptor
import com.sahidcode404.camex.core.model.LensFingerprint
import com.sahidcode404.camex.core.model.LensIdentity
import com.sahidcode404.camex.core.model.LensPreferenceRecord
import com.sahidcode404.camex.core.model.PreviewPreference
import com.sahidcode404.camex.core.model.Size2D
import com.sahidcode404.camex.core.model.StreamConfiguration
import com.sahidcode404.camex.core.model.StreamFormat
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PreviewPreferenceRegistryTest {
    @After
    fun tearDown() {
        PreviewPreferenceRegistry.clearForTest()
    }

    @Test
    fun autoModeLeavesAeFpsUnconstrainedEvenWhenThresholdsAreStored() {
        PreviewPreferenceRegistry.replace(
            listOf(
                LensPreferenceRecord(
                    fingerprint = PreviewPreferenceRegistry.GLOBAL_PREVIEW_FPS_FINGERPRINT,
                    preview = PreviewPreference(
                        fpsRange = FpsRange(20, 50),
                        fpsOverrideEnabled = false,
                    ),
                ),
            ),
        )

        val projected = PreviewPreferenceRegistry.projectForSession(
            lens(FpsRange(15, 30), FpsRange(30, 60), FpsRange(60, 60)),
        )

        assertFalse(PreviewPreferenceRegistry.fpsOverrideEnabled.value)
        assertEquals(FpsRange(20, 50), PreviewPreferenceRegistry.globalFpsRange.value)
        assertTrue(projected.capabilities.previewFpsRanges.orEmpty().isEmpty())
    }

    @Test
    fun enabledGlobalRangeMapsToNearestOverlappingRangeReportedByProfile() {
        PreviewPreferenceRegistry.replace(
            listOf(
                LensPreferenceRecord(
                    fingerprint = PreviewPreferenceRegistry.GLOBAL_PREVIEW_FPS_FINGERPRINT,
                    preview = PreviewPreference(
                        fpsRange = FpsRange(20, 50),
                        fpsOverrideEnabled = true,
                    ),
                ),
            ),
        )

        val projected = PreviewPreferenceRegistry.projectForSession(
            lens(FpsRange(15, 30), FpsRange(30, 60)),
        )

        assertTrue(PreviewPreferenceRegistry.fpsOverrideEnabled.value)
        assertEquals(listOf(FpsRange(30, 60)), projected.capabilities.previewFpsRanges)
    }

    @Test
    fun unsupportedEnabledRangeFallsBackToHalAutoInsteadOfForcingAnotherRange() {
        PreviewPreferenceRegistry.replace(
            listOf(
                LensPreferenceRecord(
                    fingerprint = PreviewPreferenceRegistry.GLOBAL_PREVIEW_FPS_FINGERPRINT,
                    preview = PreviewPreference(
                        fpsRange = FpsRange(60, 60),
                        fpsOverrideEnabled = true,
                    ),
                ),
            ),
        )

        val projected = PreviewPreferenceRegistry.projectForSession(
            lens(FpsRange(15, 30), FpsRange(30, 30)),
        )

        assertTrue(projected.capabilities.previewFpsRanges.orEmpty().isEmpty())
    }

    @Test
    fun perLensYuvSelectionIsAppliedOnlyWhenThatProfileReportsYuv() {
        PreviewPreferenceRegistry.replace(
            listOf(
                LensPreferenceRecord(
                    fingerprint = TEST_FINGERPRINT,
                    preview = PreviewPreference(streamFormat = StreamFormat.YUV_420_888),
                ),
            ),
        )
        val descriptor = lensWithStreams(
            StreamConfiguration(StreamFormat.PRIVATE, Size2D(1920, 1080)),
            StreamConfiguration(StreamFormat.YUV_420_888, Size2D(1920, 1080)),
        )

        val projected = PreviewPreferenceRegistry.projectForSession(descriptor)

        assertEquals(StreamFormat.YUV_420_888, projected.previewStreamFormat)
    }

    @Test
    fun stalePerLensYuvSelectionFallsBackToPrivate() {
        PreviewPreferenceRegistry.replace(
            listOf(
                LensPreferenceRecord(
                    fingerprint = TEST_FINGERPRINT,
                    preview = PreviewPreference(streamFormat = StreamFormat.YUV_420_888),
                ),
            ),
        )
        val projected = PreviewPreferenceRegistry.projectForSession(
            lensWithStreams(StreamConfiguration(StreamFormat.PRIVATE, Size2D(1280, 720))),
        )

        assertEquals(StreamFormat.PRIVATE, projected.previewStreamFormat)
    }

    @Test
    fun highResolutionViewfinderChoosesLargestStreamThatSustainsSmoothAutoCadence() {
        PreviewPreferenceRegistry.replace(
            listOf(
                LensPreferenceRecord(
                    fingerprint = PreviewPreferenceRegistry.GLOBAL_PREVIEW_FPS_FINGERPRINT,
                    preview = PreviewPreference(highResolutionViewfinder = true),
                ),
            ),
        )
        val descriptor = lensWithStreams(
            StreamConfiguration(
                StreamFormat.PRIVATE,
                Size2D(1920, 1080),
                minFrameDurationNs = 33_333_333L,
            ),
            StreamConfiguration(
                StreamFormat.PRIVATE,
                Size2D(2560, 1440),
                minFrameDurationNs = 66_666_667L,
            ),
            StreamConfiguration(
                StreamFormat.YUV_420_888,
                Size2D(1280, 720),
                minFrameDurationNs = 33_333_333L,
            ),
            StreamConfiguration(
                StreamFormat.YUV_420_888,
                Size2D(1920, 1080),
                minFrameDurationNs = 50_000_000L,
            ),
            StreamConfiguration(StreamFormat.JPEG, Size2D(4000, 3000)),
            StreamConfiguration(
                StreamFormat.PRIVATE,
                Size2D(8000, 6000),
                maximumResolution = true,
            ),
        )

        val projected = PreviewPreferenceRegistry.projectForSession(descriptor)
        val configurations = projected.capabilities.streamConfigurations.orEmpty()

        assertTrue(PreviewPreferenceRegistry.highResolutionViewfinder.value)
        assertEquals(
            listOf(Size2D(1920, 1080)),
            configurations.filter { it.format == StreamFormat.PRIVATE && !it.maximumResolution }
                .map { it.size },
        )
        assertEquals(
            listOf(Size2D(1280, 720)),
            configurations.filter { it.format == StreamFormat.YUV_420_888 && !it.maximumResolution }
                .map { it.size },
        )
        assertTrue(
            configurations.any {
                it.format == StreamFormat.PRIVATE &&
                    it.maximumResolution &&
                    it.size == Size2D(8000, 6000)
            },
        )
        assertTrue(configurations.any { it.format == StreamFormat.JPEG })
    }

    @Test
    fun highResolutionViewfinderUsesOverrideCadenceWhenOverrideIsEnabled() {
        PreviewPreferenceRegistry.replace(
            listOf(
                LensPreferenceRecord(
                    fingerprint = PreviewPreferenceRegistry.GLOBAL_PREVIEW_FPS_FINGERPRINT,
                    preview = PreviewPreference(
                        fpsRange = FpsRange(60, 60),
                        fpsOverrideEnabled = true,
                        highResolutionViewfinder = true,
                    ),
                ),
            ),
        )
        val descriptor = LensDescriptor(
            identity = LensIdentity("camera-alpha"),
            fingerprint = LensFingerprint(TEST_FINGERPRINT, FingerprintStrategy.STABLE_METADATA),
            capabilities = LensCapabilities(
                previewFpsRanges = listOf(FpsRange(30, 30), FpsRange(60, 60)),
                streamConfigurations = listOf(
                    StreamConfiguration(
                        StreamFormat.PRIVATE,
                        Size2D(1920, 1080),
                        minFrameDurationNs = 33_333_333L,
                    ),
                    StreamConfiguration(
                        StreamFormat.PRIVATE,
                        Size2D(1280, 720),
                        minFrameDurationNs = 16_666_667L,
                    ),
                ),
            ),
        )

        val projected = PreviewPreferenceRegistry.projectForSession(descriptor)

        assertEquals(
            listOf(Size2D(1280, 720)),
            projected.capabilities.configurations(StreamFormat.PRIVATE).map { it.size },
        )
        assertEquals(listOf(FpsRange(60, 60)), projected.capabilities.previewFpsRanges)
    }

    @Test
    fun reportedOutputFormatsAreGroupedWithoutMakingCaptureFormatsSelectable() {
        val descriptor = lensWithStreams(
            StreamConfiguration(StreamFormat.PRIVATE, Size2D(1920, 1080)),
            StreamConfiguration(StreamFormat.YUV_420_888, Size2D(1920, 1080)),
            StreamConfiguration(StreamFormat.RAW_SENSOR, Size2D(4000, 3000)),
        )

        PreviewPreferenceRegistry.projectForSession(descriptor)

        val capabilities = requireNotNull(
            PreviewPreferenceRegistry.viewfinderCapabilities.value[TEST_FINGERPRINT],
        )
        assertTrue(
            requireNotNull(capabilities.firstOrNull { it.format == StreamFormat.PRIVATE })
                .selectableLiveStream,
        )
        assertTrue(
            requireNotNull(capabilities.firstOrNull { it.format == StreamFormat.YUV_420_888 })
                .selectableLiveStream,
        )
        assertFalse(
            requireNotNull(capabilities.firstOrNull { it.format == StreamFormat.RAW_SENSOR })
                .selectableLiveStream,
        )
    }

    @Test
    fun oldPerLensFpsRecordDoesNotOverrideCentralAutoMode() {
        PreviewPreferenceRegistry.replace(
            listOf(
                LensPreferenceRecord(
                    fingerprint = TEST_FINGERPRINT,
                    preview = PreviewPreference(fpsRange = FpsRange(60, 60)),
                ),
            ),
        )
        val projected = PreviewPreferenceRegistry.projectForSession(
            lens(FpsRange(15, 30), FpsRange(30, 30)),
        )

        assertNull(PreviewPreferenceRegistry.globalFpsRange.value)
        assertTrue(projected.capabilities.previewFpsRanges.orEmpty().isEmpty())
    }

    private fun lens(vararg ranges: FpsRange) = LensDescriptor(
        identity = LensIdentity("camera-alpha"),
        capabilities = LensCapabilities(previewFpsRanges = ranges.toList()),
    )

    private fun lensWithStreams(vararg configurations: StreamConfiguration) = LensDescriptor(
        identity = LensIdentity("camera-alpha"),
        fingerprint = LensFingerprint(TEST_FINGERPRINT, FingerprintStrategy.STABLE_METADATA),
        capabilities = LensCapabilities(streamConfigurations = configurations.toList()),
    )

    private companion object {
        const val TEST_FINGERPRINT = "lensfingerprint"
    }
}
