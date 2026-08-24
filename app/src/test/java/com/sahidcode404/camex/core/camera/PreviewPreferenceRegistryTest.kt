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
    fun storedFpsThresholdsDoNotApplyUntilOverrideIsEnabled() {
        val original = listOf(FpsRange(15, 30), FpsRange(30, 60))
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

        val projected = PreviewPreferenceRegistry.projectForSession(lens(*original.toTypedArray()))

        assertFalse(PreviewPreferenceRegistry.fpsOverrideEnabled.value)
        assertEquals(FpsRange(20, 50), PreviewPreferenceRegistry.globalFpsRange.value)
        assertEquals(original, projected.capabilities.previewFpsRanges)
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
    fun unsupportedEnabledRangeFallsBackToProfileAutoRanges() {
        val original = listOf(FpsRange(15, 30), FpsRange(30, 30))
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

        val projected = PreviewPreferenceRegistry.projectForSession(lens(*original.toTypedArray()))

        assertEquals(original, projected.capabilities.previewFpsRanges)
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
    fun highResolutionViewfinderKeepsHighestRegularLiveConfigurationPerFormat() {
        PreviewPreferenceRegistry.replace(
            listOf(
                LensPreferenceRecord(
                    fingerprint = PreviewPreferenceRegistry.GLOBAL_PREVIEW_FPS_FINGERPRINT,
                    preview = PreviewPreference(highResolutionViewfinder = true),
                ),
            ),
        )
        val descriptor = lensWithStreams(
            StreamConfiguration(StreamFormat.PRIVATE, Size2D(1280, 720)),
            StreamConfiguration(StreamFormat.PRIVATE, Size2D(2560, 1440)),
            StreamConfiguration(StreamFormat.YUV_420_888, Size2D(640, 480)),
            StreamConfiguration(StreamFormat.YUV_420_888, Size2D(1920, 1080)),
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
            listOf(Size2D(2560, 1440)),
            configurations.filter { it.format == StreamFormat.PRIVATE && !it.maximumResolution }
                .map { it.size },
        )
        assertEquals(
            listOf(Size2D(1920, 1080)),
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
        val original = listOf(FpsRange(15, 30), FpsRange(30, 30))
        val projected = PreviewPreferenceRegistry.projectForSession(lens(*original.toTypedArray()))

        assertNull(PreviewPreferenceRegistry.globalFpsRange.value)
        assertEquals(original, projected.capabilities.previewFpsRanges)
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
