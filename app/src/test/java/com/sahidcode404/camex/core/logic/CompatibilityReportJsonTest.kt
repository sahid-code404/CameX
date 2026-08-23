package com.sahidcode404.camex.core.logic

import com.sahidcode404.camex.core.model.AndroidReport
import com.sahidcode404.camex.core.model.AppReport
import com.sahidcode404.camex.core.model.CameraCacheReport
import com.sahidcode404.camex.core.model.CameraCompatibilityEntry
import com.sahidcode404.camex.core.model.CameraEnvironmentReport
import com.sahidcode404.camex.core.model.CameraMetadataEvidenceReport
import com.sahidcode404.camex.core.model.CameraProfileCompatibilityReport
import com.sahidcode404.camex.core.model.CameraRouteAliasReport
import com.sahidcode404.camex.core.model.CameraRouteFailureReport
import com.sahidcode404.camex.core.model.CameraStartupTraceReport
import com.sahidcode404.camex.core.model.CanonicalLensCompatibilityReport
import com.sahidcode404.camex.core.model.CanonicalLensTrustReport
import com.sahidcode404.camex.core.model.CanonicalTopologyReport
import com.sahidcode404.camex.core.model.CompatibilityReport
import com.sahidcode404.camex.core.model.DeviceReport
import com.sahidcode404.camex.core.model.DiscoveryBackendFailureReport
import com.sahidcode404.camex.core.model.DiscoveryBackendReport
import com.sahidcode404.camex.core.model.DiscoveryFailureReport
import com.sahidcode404.camex.core.model.FailureCountReport
import com.sahidcode404.camex.core.model.FailureReasonSummaryReport
import com.sahidcode404.camex.core.model.GraphicsReport
import com.sahidcode404.camex.core.model.LensCapabilities
import com.sahidcode404.camex.core.model.LensFingerprint
import com.sahidcode404.camex.core.model.LensProbeResult
import com.sahidcode404.camex.core.model.LogicalRelationshipReport
import com.sahidcode404.camex.core.model.OpticalGroupingComparisonReport
import com.sahidcode404.camex.core.model.PhysicalSize
import com.sahidcode404.camex.core.model.ProbeOutcome
import com.sahidcode404.camex.core.model.ProbeReportEntry
import com.sahidcode404.camex.core.model.ProbeStage
import com.sahidcode404.camex.core.model.ProbeStageResult
import com.sahidcode404.camex.core.model.RouteTrustReport
import com.sahidcode404.camex.core.model.SensorRect
import com.sahidcode404.camex.core.model.Size2D
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CompatibilityReportJsonTest {
    @Test
    fun reportRoundTripsWithDiagnosticsProfilesAndUnknownFields() {
        val lens = testLens("diagnostic-route")
        val fingerprint: LensFingerprint = LensFingerprintGenerator.generate(lens)
        val probe = LensProbeResult(
            stages = listOf(ProbeStageResult(ProbeStage.DISCOVERED, ProbeOutcome.SUCCESS, 4)),
        )
        val profileFailure = CameraRouteFailureReport(
            kind = "SESSION_CONFIGURATION_UNSUPPORTED",
            durability = "STRUCTURAL",
            detail = "synthetic alias failure",
        )
        val grouping = OpticalGroupingComparisonReport(
            leftProfileId = "profile-a",
            rightProfileId = "profile-b",
            leftProfileFingerprint = "cp2_a",
            rightProfileFingerprint = "cp2_b",
            match = "STRONG_MATCH",
            score = 93,
            evidenceFamilies = listOf("GEOMETRY", "OPTICAL", "SENSOR"),
            positiveReasons = listOf("focal length strongly agrees", "RAW dimensions agree"),
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
            environment = CameraEnvironmentReport(
                stableKey = "ce1_sha256digest",
                cacheSchemaVersion = 3,
                topologySchemaVersion = 3,
                discoverySchemaVersion = 3,
                apiLevel = 37,
                advertisedTopologySignature = "ca1_topologydigest",
            ),
            cache = CameraCacheReport(
                ready = true,
                hit = true,
                deepScanRequired = false,
            ),
            startupTrace = CameraStartupTraceReport(
                offsetsNs = mapOf(
                    "APP_START" to 0L,
                    "CACHE_READY" to 1_000_000L,
                    "FIRST_PREVIEW_FRAME" to 20_000_000L,
                ),
                cacheToLensesMs = 0.75,
                appToCameraRequestMs = 2.0,
                appToFirstPreviewFrameMs = 20.0,
                advertisedScanMs = 12.0,
                deepAuxScanMs = 8.0,
            ),
            javaDiscovery = DiscoveryBackendReport(
                status = "COMPLETE",
                candidateCount = 2,
                durationMs = 12,
                cameraIds = listOf("diagnostic-route", "physical-a"),
            ),
            ndkDiscovery = DiscoveryBackendReport(
                status = "COMPLETE",
                candidateCount = 1,
                durationMs = 4,
                cameraIds = listOf("diagnostic-route"),
            ),
            deepDiscovery = DiscoveryBackendReport(
                status = "COMPLETE",
                candidateCount = 1,
                durationMs = 8,
                cameraIds = listOf("physical-a"),
                failureCount = 1,
                failuresByReason = listOf(FailureCountReport("METADATA_UNAVAILABLE", 1)),
                failures = listOf(
                    DiscoveryBackendFailureReport(
                        cameraId = "candidate-8",
                        stage = "READ_CHARACTERISTICS",
                        reason = "METADATA_UNAVAILABLE",
                        statusCode = -10004,
                    ),
                ),
            ),
            canonicalTopology = CanonicalTopologyReport(
                schemaVersion = 3,
                routeCount = 1,
                canonicalRouteIds = listOf("cl3_${fingerprint.value}"),
                profileCount = 2,
            ),
            canonicalLenses = listOf(
                CanonicalLensCompatibilityReport(
                    canonicalLensId = "cl3_${fingerprint.value}",
                    opticalFingerprint = fingerprint,
                    facing = lens.facing,
                    role = "PHOTOGRAPHIC_WIDE",
                    roleConfidence = "STRONG",
                    focalLengthsMm = lens.capabilities.focalLengthsMm.orEmpty(),
                    fieldOfView = LensMath.fieldOfView(lens.capabilities),
                    sensorPhysicalSize = lens.capabilities.sensorPhysicalSize,
                    pixelArraySize = lens.capabilities.pixelArraySize,
                    canonicalTrust = CanonicalLensTrustReport(
                        metadataTrust = "METADATA_VALID",
                        sessionTrust = "SESSION_VERIFIED",
                        rawTrust = "UNKNOWN",
                        lastAttemptEpochMs = 1234L,
                    ),
                    preferredProfileId = "profile-a",
                    profileCount = 2,
                    groupingConfidence = "STRONG_MATCH",
                    profiles = listOf(
                        CameraProfileCompatibilityReport(
                            profileId = "profile-a",
                            profileFingerprint = "cp2_a",
                            preferred = true,
                            ranking = 1,
                            profileScore = 1200,
                            discoveredCameraId = "diagnostic-route",
                            openCameraId = "diagnostic-route",
                            routeKind = "PUBLIC_DIRECT",
                            discoverySources = listOf("JAVA_PUBLIC"),
                            metadataTrust = "METADATA_VALID",
                            sessionTrust = "SESSION_VERIFIED",
                            rawTrust = "UNKNOWN",
                            lastAttemptEpochMs = 1234L,
                            previewVerified = true,
                            rawAdvertised = "SUPPORTED",
                            rawStreamActuallyDeclared = "SUPPORTED",
                            assignedCanonicalLensId = "cl3_${fingerprint.value}",
                            focalLengthsMm = listOf(4.72),
                            sensorPhysicalSize = PhysicalSize(7.2, 5.4),
                            pixelArraySize = Size2D(4000, 3000),
                            activeArray = SensorRect(0, 0, 4000, 3000),
                            rawDimensions = listOf(Size2D(4000, 3000)),
                            colorFilterArrangement = "RGGB",
                            sensorOrientationDegrees = 90,
                            apertures = listOf(1.8),
                            groupingComparisons = listOf(grouping),
                        ),
                        CameraProfileCompatibilityReport(
                            profileId = "profile-b",
                            profileFingerprint = "cp2_b",
                            ranking = 2,
                            profileScore = -1000,
                            discoveredCameraId = "physical-a",
                            openCameraId = "diagnostic-route",
                            physicalCameraId = "physical-a",
                            logicalParentCameraId = "diagnostic-route",
                            routeKind = "LOGICAL_PHYSICAL_MEMBER",
                            discoverySources = listOf("JAVA_PHYSICAL"),
                            metadataTrust = "METADATA_VALID",
                            sessionTrust = "SESSION_REJECTED",
                            rawTrust = "UNKNOWN",
                            failure = profileFailure,
                            failureDurability = "STRUCTURAL",
                            previewVerified = false,
                            rawAdvertised = "UNKNOWN",
                            rawStreamActuallyDeclared = "UNKNOWN",
                            assignedCanonicalLensId = "cl3_${fingerprint.value}",
                            groupingComparisons = listOf(grouping),
                        ),
                    ),
                ),
            ),
            opticalGrouping = listOf(grouping),
            cameras = listOf(
                CameraCompatibilityEntry(
                    identity = lens.identity,
                    canonicalRouteId = "cr1_16:diagnostic-route|0:",
                    discoveredCameraId = "diagnostic-route",
                    openCameraId = "diagnostic-route",
                    routeKind = "PUBLIC_DIRECT",
                    sources = listOf("JAVA_PUBLIC", "NDK_ADVERTISED"),
                    role = "PHOTOGRAPHIC_WIDE",
                    roleConfidence = "STRONG",
                    metadataTrust = "METADATA_VALID",
                    sessionTrust = "SESSION_VERIFIED",
                    rawTrust = "RAW_VERIFIED",
                    aliases = listOf(
                        CameraRouteAliasReport(
                            discoveredCameraId = "0",
                            openCameraId = "diagnostic-route",
                            routeKind = "NDK_DIRECT",
                            sources = listOf("NDK_ADVERTISED"),
                        ),
                    ),
                    metadataEvidence = CameraMetadataEvidenceReport(
                        rawCapabilityAdvertised = "SUPPORTED",
                        rawStreamActuallyDeclared = "SUPPORTED",
                        rawFormats = listOf("RAW_SENSOR"),
                        rawSizes = listOf(Size2D(4032, 3024)),
                        previewStreamActuallyDeclared = "SUPPORTED",
                        privatePreviewSizes = listOf(Size2D(1920, 1080)),
                    ),
                    cacheStatus = "CACHE_AND_LIVE",
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
            userVisibleRoutes = listOf("cr1_16:diagnostic-route|0:"),
            trustState = listOf(
                RouteTrustReport(
                    canonicalRouteId = "profile-a",
                    metadataTrust = "METADATA_VALID",
                    sessionTrust = "SESSION_VERIFIED",
                    rawTrust = "RAW_VERIFIED",
                ),
            ),
            failureReasons = listOf(
                FailureReasonSummaryReport("native", "METADATA_UNAVAILABLE", 1),
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
        assertTrue(encoded.contains("\"schemaVersion\": 4"))
        assertTrue(encoded.contains("\"canonicalLenses\""))
        assertTrue(encoded.contains("\"profiles\""))
        assertTrue(encoded.contains("\"profileFingerprint\": \"cp2_a\""))
        assertTrue(encoded.contains("\"groupingConfidence\": \"STRONG_MATCH\""))
        assertTrue(encoded.contains("\"opticalGrouping\""))
        assertTrue(encoded.contains("\"evidenceFamilies\""))
        assertTrue(encoded.contains("\"assignedCanonicalLensId\""))
        assertTrue(encoded.contains("\"rawDimensions\""))
        assertTrue(encoded.contains("\"startupTrace\""))
        assertTrue(encoded.contains("\"canonicalTopology\""))
        assertTrue(encoded.contains("\"rawStreamActuallyDeclared\": \"SUPPORTED\""))
        assertTrue(encoded.contains("\"discoveryFailures\""))
        assertTrue(encoded.contains("\"gitSha\": \"abc123\""))
    }

    @Test
    fun schemaOnePayloadStillDecodesWithNewerFieldsDefaulted() {
        val decoded = CompatibilityReportJson.decode(
            """{"schemaVersion":1,"generatedAtUtc":"2026-08-23T12:00:00Z"}""",
        )

        assertEquals(1, decoded.schemaVersion)
        assertTrue(decoded.canonicalTopology.canonicalRouteIds.isEmpty())
        assertTrue(decoded.canonicalLenses.isEmpty())
        assertTrue(decoded.opticalGrouping.isEmpty())
        assertTrue(decoded.startupTrace.offsetsNs.isEmpty())
        assertEquals("NOT_STARTED", decoded.javaDiscovery.status)
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
                startupTrace = CameraStartupTraceReport(
                    offsetsNs = mapOf("APP_START" to -1L),
                    cacheToLensesMs = Double.NaN,
                    appToFirstPreviewFrameMs = Double.POSITIVE_INFINITY,
                ),
                javaDiscovery = DiscoveryBackendReport(
                    candidateCount = -2,
                    durationMs = -1L,
                ),
                cameras = listOf(
                    CameraCompatibilityEntry(
                        identity = lens.identity,
                        routeFailure = CameraRouteFailureReport(
                            kind = "INVALID_METADATA",
                            durability = "STRUCTURAL",
                            detail = "x".repeat(400),
                        ),
                        metadataEvidence = CameraMetadataEvidenceReport(
                            rawCapabilityAdvertised = "UNKNOWN",
                            rawStreamActuallyDeclared = "UNKNOWN",
                            rawSizes = listOf(Size2D(-1, 20)),
                            previewStreamActuallyDeclared = "UNKNOWN",
                        ),
                        capabilities = lens.capabilities,
                        estimatedMaximumRawFps = Double.NaN,
                    ),
                ),
            ),
        )

        assertFalse(encoded.contains("NaN"))
        assertFalse(encoded.contains("Infinity"))
        val decoded = CompatibilityReportJson.decode(encoded)
        assertTrue(decoded.startupTrace.offsetsNs.isEmpty())
        assertEquals(null, decoded.javaDiscovery.durationMs)
        assertEquals(0, decoded.javaDiscovery.candidateCount)
        assertTrue(decoded.cameras.single().metadataEvidence?.rawSizes.orEmpty().isEmpty())
        assertEquals(256, decoded.cameras.single().routeFailure?.detail?.length)
    }
}
