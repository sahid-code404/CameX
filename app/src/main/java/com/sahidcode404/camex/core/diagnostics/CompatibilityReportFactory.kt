package com.sahidcode404.camex.core.diagnostics

import android.content.Context
import com.sahidcode404.camex.core.camera.diagnostics.CameraStartupTraceSnapshot
import com.sahidcode404.camex.core.camera.discovery.DiscoveryBackendSummary
import com.sahidcode404.camex.core.camera.discovery.HybridCameraDiscoverySnapshot
import com.sahidcode404.camex.core.camera.discovery.nativebackend.NativeDiscoveryFailure
import com.sahidcode404.camex.core.camera.topology.CameraDiscoverySource
import com.sahidcode404.camex.core.camera.topology.CameraEnvironmentFingerprint
import com.sahidcode404.camex.core.camera.topology.CameraRoute
import com.sahidcode404.camex.core.camera.topology.CameraRouteAlias
import com.sahidcode404.camex.core.camera.topology.CameraRouteFailure
import com.sahidcode404.camex.core.camera.topology.CameraTopology
import com.sahidcode404.camex.core.camera.topology.toLensDescriptor
import com.sahidcode404.camex.core.logic.CompatibilityReportJson
import com.sahidcode404.camex.core.logic.LensMath
import com.sahidcode404.camex.core.model.AndroidReport
import com.sahidcode404.camex.core.model.AppReport
import com.sahidcode404.camex.core.model.AppliedQuirkReport
import com.sahidcode404.camex.core.model.CameraCacheReport
import com.sahidcode404.camex.core.model.CameraCompatibilityEntry
import com.sahidcode404.camex.core.model.CameraEnvironmentReport
import com.sahidcode404.camex.core.model.CameraMetadataEvidenceReport
import com.sahidcode404.camex.core.model.CameraRouteAliasReport
import com.sahidcode404.camex.core.model.CameraRouteFailureReport
import com.sahidcode404.camex.core.model.CameraStartupTraceReport
import com.sahidcode404.camex.core.model.CanonicalTopologyReport
import com.sahidcode404.camex.core.model.CompatibilityReport
import com.sahidcode404.camex.core.model.DeviceReport
import com.sahidcode404.camex.core.model.DiscoveryBackendFailureReport
import com.sahidcode404.camex.core.model.DiscoveryBackendReport
import com.sahidcode404.camex.core.model.DiscoveryFailureReport
import com.sahidcode404.camex.core.model.FailureCountReport
import com.sahidcode404.camex.core.model.FailureReasonSummaryReport
import com.sahidcode404.camex.core.model.GraphicsReport
import com.sahidcode404.camex.core.model.LensDescriptor
import com.sahidcode404.camex.core.model.LogicalRelationshipReport
import com.sahidcode404.camex.core.model.RouteTrustReport
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

object CompatibilityReportFactory {
    /**
     * Topology-first schema-v2 export. This is a read-only projection: it performs no discovery,
     * probing, validation or camera opens.
     */
    fun create(
        context: Context,
        topology: CameraTopology,
        discovery: HybridCameraDiscoverySnapshot,
        startupTrace: CameraStartupTraceSnapshot,
        lenses: List<LensDescriptor> = topology.routes.map { it.toLensDescriptor() },
        quirks: List<AppliedQuirkReport> = emptyList(),
        generatedAtUtc: String = nowUtc(),
    ): CompatibilityReport {
        val platform = PlatformDiagnostics.collect(context.applicationContext)
        val routes = topology.routes.sortedBy(CameraRoute::canonicalRouteId)
        val lensesByRoute = lenses.associateBy { lens ->
            RouteKey(lens.identity.openCameraId, lens.identity.streamPhysicalCameraId)
        }
        val entries = routes.map { route ->
            val lens = lensesByRoute[RouteKey(route.openCameraId, route.streamPhysicalCameraId)]
                ?: route.toLensDescriptor()
            route.toCompatibilityEntry(lens)
        }
        val userVisibleRoutes = routes.mapNotNull { route ->
            val lens = lensesByRoute[RouteKey(route.openCameraId, route.streamPhysicalCameraId)]
            route.canonicalRouteId.takeIf {
                lens != null && lens.usability.isSelectable &&
                    lens.category.isNormalSelectorCandidate
            }
        }
        val hiddenRoutes = routes.map(CameraRoute::canonicalRouteId) - userVisibleRoutes.toSet()

        val javaFailures = discovery.javaFailures.map { failure ->
            DiscoveryBackendFailureReport(
                publicCameraId = failure.publicCameraId,
                physicalCameraId = failure.physicalCameraId,
                reason = failure.kind.name,
                detail = failure.detail.sanitizedDetail(),
            )
        }
        val javaDiscovery = discovery.java.toReport(
            cameraIds = routes.cameraIdsFrom(
                CameraDiscoverySource.JAVA_PUBLIC,
                CameraDiscoverySource.JAVA_PHYSICAL,
            ),
            failures = javaFailures,
        )
        val ndkDiscovery = discovery.ndk.toReport(
            cameraIds = routes.cameraIdsFrom(CameraDiscoverySource.NDK_ADVERTISED),
            failures = discovery.ndkFailures.map { it.toReport() },
        )
        val deepDiscovery = discovery.deep.toReport(
            cameraIds = routes.cameraIdsFrom(CameraDiscoverySource.NDK_DEEP),
            failures = discovery.deepFailures.map { it.toReport() },
        )

        return CompatibilityReport(
            generatedAtUtc = generatedAtUtc,
            device = platform.device.toReport(),
            android = platform.android.toReport(),
            graphics = platform.graphics.toReport(),
            environment = topology.environmentFingerprint.toReport(topology.schemaVersion),
            cache = CameraCacheReport(
                ready = discovery.cacheReady,
                hit = discovery.cacheHit,
                miss = discovery.cacheReady && !discovery.cacheHit,
                missReason = discovery.cacheMissReason?.name,
                generatedAtEpochMs = discovery.cacheGeneratedAtEpochMs,
                deepScanRequired = discovery.initialDeepScanRequired,
            ),
            startupTrace = startupTrace.toReport(),
            javaDiscovery = javaDiscovery,
            ndkDiscovery = ndkDiscovery,
            deepDiscovery = deepDiscovery,
            canonicalTopology = CanonicalTopologyReport(
                schemaVersion = topology.schemaVersion,
                routeCount = routes.size,
                canonicalRouteIds = routes.map(CameraRoute::canonicalRouteId),
            ),
            cameras = entries,
            discoveryFailures = javaFailures.map { failure ->
                DiscoveryFailureReport(
                    publicCameraId = failure.publicCameraId,
                    physicalCameraId = failure.physicalCameraId,
                    kind = failure.reason,
                    detail = failure.detail.orEmpty(),
                )
            },
            logicalRelationships = topology.logicalRelationships.map { relationship ->
                LogicalRelationshipReport(
                    logicalCameraId = relationship.logicalCameraId,
                    physicalCameraIds = relationship.physicalCameraIds.sorted(),
                )
            }.sortedBy(LogicalRelationshipReport::logicalCameraId),
            hiddenRoutes = hiddenRoutes,
            userVisibleRoutes = userVisibleRoutes,
            trustState = routes.map { route ->
                RouteTrustReport(
                    canonicalRouteId = route.canonicalRouteId,
                    metadataTrust = route.trust.metadata.name,
                    sessionTrust = route.trust.session.name,
                    rawTrust = route.trust.raw.name,
                    failure = route.trust.failure?.toReport(),
                )
            },
            failureReasons = failureReasons(routes, javaFailures, discovery.nativeFailures),
            // Schema v2 reports lazy route trust directly; it never initiates probe sessions.
            probeResults = emptyList(),
            quirks = quirks,
            app = platform.app.toReport(),
        )
    }

    fun encode(
        context: Context,
        topology: CameraTopology,
        discovery: HybridCameraDiscoverySnapshot,
        startupTrace: CameraStartupTraceSnapshot,
        lenses: List<LensDescriptor> = topology.routes.map { it.toLensDescriptor() },
        quirks: List<AppliedQuirkReport> = emptyList(),
    ): String = CompatibilityReportJson.encode(
        create(context, topology, discovery, startupTrace, lenses, quirks),
    )

    private fun CameraEnvironmentFingerprint.toReport(topologySchemaVersion: Int) =
        CameraEnvironmentReport(
            stableKey = stableKey,
            cacheSchemaVersion = cacheSchemaVersion,
            topologySchemaVersion = topologySchemaVersion,
            discoverySchemaVersion = discoverySchemaVersion,
            apiLevel = apiLevel,
            advertisedTopologySignature = advertisedTopologySignature,
        )

    private fun CameraStartupTraceSnapshot.toReport() = CameraStartupTraceReport(
        offsetsNs = offsetsNs.entries
            .sortedBy { it.key.ordinal }
            .associate { (milestone, offset) -> milestone.name to offset },
        cacheToLensesMs = cacheToLensesMs,
        appToCameraRequestMs = appToCameraRequestMs,
        appToFirstPreviewFrameMs = appToFirstPreviewFrameMs,
        advertisedScanMs = advertisedScanMs,
        deepAuxScanMs = deepAuxScanMs,
    )

    private fun CameraRoute.toCompatibilityEntry(lens: LensDescriptor) =
        CameraCompatibilityEntry(
            identity = lens.identity,
            canonicalRouteId = canonicalRouteId,
            discoveredCameraId = discoveredCameraId,
            openCameraId = openCameraId,
            streamPhysicalCameraId = streamPhysicalCameraId,
            logicalParentCameraId = logicalParentCameraId,
            routeKind = routeKind.name,
            sources = sources.map { it.name }.sorted(),
            role = role.name,
            roleConfidence = roleConfidence.name,
            metadataTrust = trust.metadata.name,
            sessionTrust = trust.session.name,
            rawTrust = trust.raw.name,
            routeFailure = trust.failure?.toReport(),
            aliases = aliases.sortedWith(
                compareBy<CameraRouteAlias> { it.openCameraId }
                    .thenBy { it.streamPhysicalCameraId.orEmpty() }
                    .thenBy { it.discoveredCameraId },
            ).map { it.toReport() },
            metadataEvidence = CameraMetadataEvidenceReport(
                rawCapabilityAdvertised = minimalMetadata.rawCapabilityAdvertised.name,
                rawStreamActuallyDeclared = minimalMetadata.rawStreamActuallyDeclared.name,
                rawFormats = minimalMetadata.rawFormats.map { it.name }.sorted(),
                rawSizes = minimalMetadata.rawSizes,
                previewStreamActuallyDeclared = minimalMetadata.previewStreamActuallyDeclared.name,
                privatePreviewSizes = minimalMetadata.privatePreviewSizes,
                yuvPreviewSizes = minimalMetadata.yuvPreviewSizes,
            ),
            cacheStatus = when {
                CameraDiscoverySource.CACHE !in sources -> "LIVE"
                sources.size == 1 -> "CACHE_ONLY"
                else -> "CACHE_AND_LIVE"
            },
            fingerprint = lens.fingerprint,
            facing = lens.facing,
            usability = lens.usability,
            category = lens.category,
            fieldOfView = LensMath.fieldOfView(lens.capabilities),
            capabilities = lens.capabilities,
            maximumRawSize = lens.capabilities.maximumRawSize,
            estimatedMaximumRawFps = lens.capabilities.estimatedMaximumRawFps,
        )

    private fun CameraRouteFailure.toReport() = normalized().let { failure ->
        CameraRouteFailureReport(
            kind = failure.kind.name,
            durability = failure.durability.name,
            detail = failure.detail,
        )
    }

    private fun CameraRouteAlias.toReport() = CameraRouteAliasReport(
        discoveredCameraId = discoveredCameraId,
        openCameraId = openCameraId,
        streamPhysicalCameraId = streamPhysicalCameraId,
        logicalParentCameraId = logicalParentCameraId,
        routeKind = routeKind.name,
        sources = sources.map { it.name }.sorted(),
    )

    private fun DiscoveryBackendSummary.toReport(
        cameraIds: List<String>,
        failures: List<DiscoveryBackendFailureReport>,
    ) = DiscoveryBackendReport(
        status = status.name,
        candidateCount = candidateCount.coerceAtLeast(0),
        durationMs = durationMs?.takeIf { it >= 0L },
        cameraIds = cameraIds,
        failureCount = failures.size,
        failuresByReason = failures
            .groupingBy(DiscoveryBackendFailureReport::reason)
            .eachCount()
            .toSortedMap()
            .map { (reason, count) -> FailureCountReport(reason, count) },
        failures = failures,
    )

    private fun NativeDiscoveryFailure.toReport() = DiscoveryBackendFailureReport(
        cameraId = cameraId,
        stage = stage.name,
        reason = reason.name,
        statusCode = statusCode,
    )

    private fun List<CameraRoute>.cameraIdsFrom(
        vararg expectedSources: CameraDiscoverySource,
    ): List<String> {
        val sourceSet = expectedSources.toSet()
        return asSequence()
            .filter { route -> route.sources.any(sourceSet::contains) }
            .map(CameraRoute::discoveredCameraId)
            .distinct()
            .sorted()
            .toList()
    }

    private fun failureReasons(
        routes: List<CameraRoute>,
        javaFailures: List<DiscoveryBackendFailureReport>,
        nativeFailures: List<NativeDiscoveryFailure>,
    ): List<FailureReasonSummaryReport> {
        val reasons = buildList {
            routes.mapNotNull { it.trust.failure?.kind?.name }.forEach { add("route" to it) }
            javaFailures.forEach { add("java" to it.reason) }
            nativeFailures.forEach { add("native" to it.reason.name) }
        }
        return reasons.groupingBy { it }.eachCount().entries
            .sortedWith(compareBy({ it.key.first }, { it.key.second }))
            .map { (key, count) -> FailureReasonSummaryReport(key.first, key.second, count) }
    }

    private fun DeviceSnapshot.toReport() = DeviceReport(
        manufacturer = manufacturer,
        brand = brand,
        model = model,
        deviceCodename = device,
        product = product,
        buildFingerprint = buildFingerprint,
    )

    private fun AndroidSnapshot.toReport() = AndroidReport(
        sdkInt = sdkInt,
        release = release,
        securityPatch = securityPatch,
    )

    private fun GraphicsSnapshot.toReport() = GraphicsReport(
        vulkanApiVersion = vulkanVersionReadable,
        vulkanHardwareLevel = vulkanHardwareLevel,
        vulkanHardwareVersion = vulkanHardwareVersion,
        openGlEsVersion = openGlEsVersion,
    )

    private fun AppBuildSnapshot.toReport() = AppReport(
        applicationId = applicationId,
        versionName = versionName,
        versionCode = versionCode.toLong(),
        buildType = buildType,
        buildTimestampUtc = buildTimestampUtc,
        gitSha = gitSha,
        nativeLoaded = nativeLoaded,
        nativeVersion = nativeVersion,
        nativeSelfTestPassed = nativeSelfTestPassed,
    )

    private fun String.sanitizedDetail(): String = trim().take(256)

    private data class RouteKey(
        val openCameraId: String,
        val physicalCameraId: String?,
    )

    private fun nowUtc(): String = SimpleDateFormat(
        "yyyy-MM-dd'T'HH:mm:ss'Z'",
        Locale.ROOT,
    ).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }.format(Date())
}
