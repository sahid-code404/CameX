package com.sahidcode404.camex.core.logic

import com.sahidcode404.camex.core.model.ActiveCameraSelectionReport
import com.sahidcode404.camex.core.model.CameraUiSelectionReport
import com.sahidcode404.camex.core.model.CompatibilityReport
import com.sahidcode404.camex.core.model.FingerprintStrategy
import com.sahidcode404.camex.core.model.LensFacing
import com.sahidcode404.camex.core.model.LensFingerprint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CompatibilitySelectionReportJsonTest {
    @Test
    fun `active session and camera UI selection diagnostics round trip`() {
        val fingerprint = LensFingerprint("ol4_selection", FingerprintStrategy.STABLE_METADATA)
        val report = CompatibilityReport(
            generatedAtUtc = "2026-08-23T16:30:00Z",
            activeSelection = ActiveCameraSelectionReport(
                activeProfileRoutingKey = "5:front|0:",
                activeProfileFingerprint = "cp2_front",
                canonicalLensFingerprint = fingerprint,
                canonicalLensId = "cl3_${fingerprint.value}",
                activeProfileFacing = LensFacing.FRONT,
                canonicalFacing = LensFacing.FRONT,
                selectionGeneration = 21L,
                sessionState = "Previewing",
                verified = true,
            ),
            cameraUi = CameraUiSelectionReport(
                normalVisibleFrontCount = 1,
                normalVisibleRearCount = 4,
                cameraUiFacing = LensFacing.FRONT,
                cameraUiLensCount = 1,
                selectedCanonicalFingerprint = fingerprint.value,
                switchFacingTarget = LensFacing.BACK,
                switchFacingEnabled = true,
            ),
        )

        val encoded = CompatibilityReportJson.encode(report)
        val decoded = CompatibilityReportJson.decode(encoded)

        assertEquals(report, decoded)
        assertTrue(encoded.contains("\"activeSelection\""))
        assertTrue(encoded.contains("\"activeProfileRoutingKey\": \"5:front|0:\""))
        assertTrue(encoded.contains("\"normalVisibleRearCount\": 4"))
        assertTrue(encoded.contains("\"cameraUiFacing\": \"FRONT\""))
        assertTrue(encoded.contains("\"switchFacingTarget\": \"BACK\""))
    }
}
