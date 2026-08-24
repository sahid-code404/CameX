package com.sahidcode404.camex.core.camera

import com.sahidcode404.camex.core.model.FpsRange
import com.sahidcode404.camex.core.model.LensCapabilities
import com.sahidcode404.camex.core.model.LensDescriptor
import com.sahidcode404.camex.core.model.LensIdentity
import com.sahidcode404.camex.core.model.LensPreferenceRecord
import com.sahidcode404.camex.core.model.PreviewPreference
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test

class PreviewPreferenceRegistryTest {
    @After
    fun tearDown() {
        PreviewPreferenceRegistry.clearForTest()
    }

    @Test
    fun globalTargetMapsToExactFixedRangeWhenProfileReportsIt() {
        PreviewPreferenceRegistry.replace(
            listOf(
                LensPreferenceRecord(
                    fingerprint = PreviewPreferenceRegistry.GLOBAL_PREVIEW_FPS_FINGERPRINT,
                    preview = PreviewPreference(fpsRange = FpsRange(60, 60)),
                ),
            ),
        )
        val projected = PreviewPreferenceRegistry.projectForSession(
            lens(FpsRange(15, 30), FpsRange(30, 60), FpsRange(60, 60)),
        )

        assertEquals(60, PreviewPreferenceRegistry.globalFpsTarget.value)
        assertEquals(listOf(FpsRange(60, 60)), projected.capabilities.previewFpsRanges)
    }

    @Test
    fun globalTargetMapsToReportedVariableRangeWhenThatIsOnlyMatch() {
        PreviewPreferenceRegistry.replace(
            listOf(
                LensPreferenceRecord(
                    fingerprint = PreviewPreferenceRegistry.GLOBAL_PREVIEW_FPS_FINGERPRINT,
                    preview = PreviewPreference(fpsRange = FpsRange(60, 60)),
                ),
            ),
        )
        val projected = PreviewPreferenceRegistry.projectForSession(
            lens(FpsRange(15, 30), FpsRange(30, 60)),
        )

        assertEquals(listOf(FpsRange(30, 60)), projected.capabilities.previewFpsRanges)
    }

    @Test
    fun unsupportedGlobalTargetFallsBackToProfileAutoRanges() {
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

        assertEquals(null, PreviewPreferenceRegistry.globalFpsTarget.value)
        assertEquals(original, projected.capabilities.previewFpsRanges)
    }

    private fun lens(vararg ranges: FpsRange) = LensDescriptor(
        identity = LensIdentity("0"),
        capabilities = LensCapabilities(previewFpsRanges = ranges.toList()),
    )
}
