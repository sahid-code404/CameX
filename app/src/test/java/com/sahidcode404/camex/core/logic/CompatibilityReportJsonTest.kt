package com.sahidcode404.camex.core.logic

import com.sahidcode404.camex.core.model.AndroidReport
import com.sahidcode404.camex.core.model.AppReport
import com.sahidcode404.camex.core.model.CameraCompatibilityEntry
import com.sahidcode404.camex.core.model.CompatibilityReport
import com.sahidcode404.camex.core.model.DeviceReport
import com.sahidcode404.camex.core.model.DiscoveryFailureReport
import com.sahidcode404.camex.core.model.GraphicsReport
import com.sahidcode404.camex.core.model.LensCapabilities
import com.sahidcode404.camex.core.model.PhysicalSize
import com.sahidcode404.camex.core.model.LensFingerprint
import com.sahidcode404.camex.core.model.LogicalRelationshipReport
import com.sahidcode404.camex.core.model.ProbeOutcome
import com.sahidcode404.camex.core.model.ProbeReportEntry
import com.sahidcode404.camex.core.model.ProbeStage
import com.sahidcode404.camex.core.model.ProbeStageResult
import com.sahidcode404.camex.core.model.LensProbeResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CompatibilityReportJsonTest {
    @Test
    fun reportRoundTripsWithDiagnosticsAndUnknownFields() {
        val lens = testLens("diagnostic-route")
        val fingerprint: LensFingerprint = LensFingerprintGenerator.generate(lens)
        val probe = LensProbeResult(
            stages = listOf(ProbeStageResult(ProbeStage.DISCOVERED, ProbeOutcome.SUCCESS, 4)),
        )
        val report = CompatibilityReport(
            generatedAtUtc = "2026-08-23T12:00:00Z",
            device = DeviceReport(
                manufacturer = "Example",
                model = "Synthetic",
                buildFingerprint = "build/fingerprint",
            ),
            android = AndroidReport(37, "17", "2026-08-01"),
            graphics = GraphicsReport(
                vulkanApiVersion = "1.3.0",
                vulkanHardwareLevel = 1,
                vulkanHardwareVersion = 4_206_595,
                openGlEsVersion = "3.2",
            ),
            cameras = listOf(
                CameraCompatibilityEntry(
                    identity = lens.identity,
                    fingerprint = fingerprint,
                    facing = lens.facing,
                    usability = lens.usability,
                    category = lens.category,
                    fieldOfView = LensMath.fieldOfView(lens.capabilities),
                    capabilities = lens.capabilities,
                ),
            ),
            discoveryFailures = listOf(
                DiscoveryFailureReport("route-b", null, "ACCESS_DENIED", "SecurityException"),
            ),
            logicalRelationships = listOf(
                LogicalRelationshipReport("diagnostic-route", listOf("physical-a")),
            ),
            probeResults = listOf(ProbeReportEntry(lens.identity.routingKey, fingerprint, probe)),
            app = AppReport(
                versionName = "0.1.0",
                versionCode = 1,
                buildType = "debug",
                buildTimestampUtc = "2026-08-23T12:00:00Z",
                gitSha = "abc123",
                nativeLoaded = true,
                nativeVersion = "0.1",
                nativeSelfTestPassed = true,
            ),
        )

        val encoded = CompatibilityReportJson.encode(report)
        val withFutureField = encoded.replaceFirst("{", "{\n  \"futureField\": true,")
        val decoded = CompatibilityReportJson.decode(withFutureField)

        assertEquals(report, decoded)
        assertTrue(encoded.contains("\"schemaVersion\": 1"))
        assertTrue(encoded.contains("\"discoveryFailures\""))
        assertTrue(encoded.contains("\"gitSha\": \"abc123\""))
    }

    @Test
    fun reportSchemaHasNoPersonalDataBuckets() {
        val encoded = CompatibilityReportJson.encode(
            CompatibilityReport(generatedAtUtc = "2026-08-23T12:00:00Z"),
        ).lowercase()

        listOf("account", "contact", "location", "authenticationtoken", "personalmedia").forEach {
            assertFalse("Unexpected personal-data field: $it", encoded.contains("\"$it\""))
        }
    }

    @Test
    fun brokenHalFloatingPointValuesAreOmittedFromStrictJson() {
        val lens = testLens("broken-floats").copy(
            capabilities = LensCapabilities(
                focalLengthsMm = listOf(Double.NaN, Double.POSITIVE_INFINITY),
                sensorPhysicalSize = PhysicalSize(Double.NaN, 4.0),
                apertures = listOf(Double.NEGATIVE_INFINITY),
            ),
        )
        val encoded = CompatibilityReportJson.encode(
            CompatibilityReport(
                generatedAtUtc = "2026-08-23T12:00:00Z",
                cameras = listOf(
                    CameraCompatibilityEntry(
                        identity = lens.identity,
                        capabilities = lens.capabilities,
                        estimatedMaximumRawFps = Double.NaN,
                    ),
                ),
            ),
        )

        assertFalse(encoded.contains("NaN"))
        assertFalse(encoded.contains("Infinity"))
        CompatibilityReportJson.decode(encoded)
    }
}
