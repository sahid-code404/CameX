package com.sahidcode404.camex.core.diagnostics

import android.content.Context
import com.sahidcode404.camex.core.camera.diagnostics.CameraStartupTraceSnapshot
import com.sahidcode404.camex.core.camera.discovery.DiscoveryBackendSummary
import com.sahidcode404.camex.core.camera.discovery.HybridCameraDiscoverySnapshot
import com.sahidcode404.camex.core.camera.discovery.nativebackend.NativeDiscoveryFailure
import com.sahidcode404.camex.core.camera.topology.CameraDiscoverySource
import com.sahidcode404.camex.core.camera.topology.CameraEnvironmentFingerprint
import com.sahidcode404.camex.core.camera.topology.CameraProfile
import com.sahidcode404.camex.core.camera.topology.CameraProfileSelector
import com.sahidcode404.camex.core.camera.topology.CameraRoute
import com.sahidcode404.camex.core.camera.topology.CameraRouteAlias
import com.sahidcode404.camex.core.camera.topology.CameraRouteFailure
import com.sahidcode404.camex.core.camera.topology.CameraSessionTrust
import com.sahidcode404.camex.core.camera.topology.CameraTopology
import com.sahidcode404.camex.core.camera.topology.OpticalGroupingComparisonRecord
import com.sahidcode404.camex.core.camera.topology.OpticalLensMatch
import com.sahidcode404.camex.core.camera.topology.OpticalLensMatcher
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
import com.sahidcode404.camex.core.model.LensDescriptor
import com.sahidcode404.camex.core.model.LogicalRelationshipReport
import com.sahidcode404.camex.core.model.OpticalGroupingComparisonReport
import com.sahidcode404.camex.core.model.RouteTrustReport
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

object CompatibilityReportFactory {
    /**
     * Topology-first schema-v4 export. canonicalLenses[].profiles[] is authoritative and
     * opticalGrouping[] preserves pairwise identity reasoning, including comparisons that did not
     * merge. Export remains read-only and performs no discovery, probing, validation, camera open,
     * or media access.
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
        val routes = topology.routes.sortedWith(
            compareBy<CameraRoute> { it.lensFingerprint?.value.orEmpty() }
                .thenBy { it.canonicalRouteId },
        )
        val groupingReports = topology.groupingComparisons.map { comparison ->
            comparison.toReport()
        }
        val lensesByFingerprint = lenses.mapNotNull { lens ->
            lens.fingerprint?.value?.let { it to lens }
        }.toMap()
        val entries = routes.map { route ->
            val lens = route.lensFingerprint?.value?.let(lensesByFingerprint::get)
                ?: route.toLensDescriptor()
            route.toCompatibilityEntry(lens)
        }
        val canonicalLensReports = routes.map { route ->
            val lens = route.lensFingerprint?.value?.let(lensesByFingerprint::get)
                ?: route.toLensDescriptor()
            route.toCanonicalLensReport(lens, topology.groupingComparisons)
        }
        val userVisibleRoutes = routes.mapNotNull { route ->
            val lens = route.lensFingerprint?.value?.let(lensesByFingerprint::get)
            route.canonicalRouteId.takeIf {
                lens != null && lens.usability.isSelectable && lens.category.isNormalSelectorCandidate
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
                canonicalRouteIds = routes.map { route ->
                    "cl3_${route.lensFingerprint?.value ?: route.canonicalRouteId}"
                },
                profileCount = routes.sumOf { it.profiles.size },
            ),
            canonicalLenses = canonicalLensReports,
            opticalGrouping = groupingReports,
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
            trustState = routes.flatMap { route ->
                route.profiles.map { profile ->
                    RouteTrustReport(
                        canonicalRouteId = profile.profileId,
                        metadataTrust = profile.metadataTrust.name,
                        sessionTrust = profile.sessionTrust.name,
                        rawTrust = profile.rawTrust.name,
                        failure = profile.failure?.toReport(),
                    )
                }
            },
            failureReasons = failureReasons(routes, javaFailures, discovery.nativeFailures),
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

    private fun CameraRoute.toCanonicalLensReport(
        lens: LensDescriptor,
        groupingComparisons: List<OpticalGroupingComparisonRecord>,
    ): CanonicalLensCompatibilityReport {
        val orderedProfiles = profiles.sortedWith(
            compareByDescending<CameraProfile> { CameraProfileSelector.score(it) }
                .thenBy(CameraProfile::profileFingerprint),
        )
        val preferredId = preferredProfileId ?: orderedProfiles.firstOrNull()?.profileId
        val canonicalLensId = "cl3_${lens.fingerprint?.value ?: canonicalRouteId}"
        val profileReports = orderedProfiles.mapIndexed { index, profile ->
            val profileComparisons = groupingComparisons
                .filter { comparison ->
                    comparison.leftProfileId == profile.profileId ||
                        comparison.rightProfileId == profile.profileId
                }
                .map { comparison -> comparison.toReport() }
            val full = profile.fullCapabilities?.capabilities
            CameraProfileCompatibilityReport(
                profileId = profile.profileId,
                profileFingerprint = profile.profileFingerprint,
                preferred = profile.profileId == preferredId,
                ranking = index + 1,
                profileScore = CameraProfileSelector.score(profile),
                discoveredCameraId = profile.discoveredCameraId,
                openCameraId = profile.openCameraId,
                physicalCameraId = profile.streamPhysicalCameraId,
                logicalParentCameraId = profile.logicalParentCameraId,
                routeKind = profile.routeKind.name,
                discoverySources = profile.discoverySources.map { it.name }.sorted(),
                metadataTrust = profile.metadataTrust.name,
                sessionTrust = profile.sessionTrust.name,
                rawTrust = profile.rawTrust.name,
                lastAttemptEpochMs = profile.lastAttemptEpochMs,
                failure = profile.failure?.toReport(),
                failureDurability = profile.failure?.durability?.name,
                previewVerified = profile.sessionTrust == CameraSessionTrust.SESSION_VERIFIED,
                rawAdvertised = profile.metadata.rawCapabilityAdvertised.name,
                rawStreamActuallyDeclared = profile.metadata.rawStreamActuallyDeclared.name,
                assignedCanonicalLensId = canonicalLensId,
                focalLengthsMm = profile.metadata.focalLengthsMm,
                fieldOfView = profile.metadata.approximateFieldOfView
                    ?: full?.let(LensMath::fieldOfView),
                sensorPhysicalSize = profile.metadata.sensorPhysicalSize ?: full?.sensorPhysicalSize,
                pixelArraySize = profile.metadata.pixelArraySize ?: full?.pixelArraySize,
                activeArray = profile.metadata.activeArray ?: full?.activeArray,
                rawDimensions = profile.metadata.rawSizes,
                colorFilterArrangement = full?.colorFilterArrangement?.name,
                sensorOrientationDegrees = profile.metadata.sensorOrientationDegrees
                    ?: full?.sensorOrientationDegrees,
                apertures = full?.apertures.orEmpty(),
                groupingComparisons = profileComparisons,
            )
        }
        return CanonicalLensCompatibilityReport(
            canonicalLensId = canonicalLensId,
            opticalFingerprint = lens.fingerprint,
            facing = lens.facing,
            role = role.name,
            roleConfidence = roleConfidence.name,
            focalLengthsMm = minimalMetadata.focalLengthsMm,
            fieldOfView = LensMath.fieldOfView(lens.capabilities),
            sensorPhysicalSize = minimalMetadata.sensorPhysicalSize,
            pixelArraySize = minimalMetadata.pixelArraySize,
            canonicalTrust = CanonicalLensTrustReport(
                metadataTrust = trust.metadata.name,
                sessionTrust = trust.session.name,
                rawTrust = trust.raw.name,
                lastAttemptEpochMs = trust.lastAttemptEpochMs,
                failure = trust.failure?.toReport(),
            ),
            preferredProfileId = preferredId,
            profileCount = orderedProfiles.size,
            groupingConfidence = groupingConfidence(orderedProfiles),
            profiles = profileReports,
        )
    }

    private fun groupingConfidence(profiles: List<CameraProfile>): String {
        if (profiles.size <= 1) return "SINGLE_PROFILE"
        val matches = buildList {
            profiles.indices.forEach { left ->
                for (right in left + 1 until profiles.size) {
                    add(OpticalLensMatcher.compare(profiles[left], profiles[right]).match)
                }
            }
        }
        return when {
            matches.isNotEmpty() && matches.all { it == OpticalLensMatch.STRONG_MATCH } -> "STRONG_MATCH"
            matches.any { it == OpticalLensMatch.CONFLICT } -> "CONFLICT"
            matches.any { it == OpticalLensMatch.PROBABLE_MATCH } -> "PROBABLE_MATCH"
            else -> "MIXED_OR_INSUFFICIENT"
        }
    }

    private fun OpticalGroupingComparisonRecord.toReport() = OpticalGroupingComparisonReport(
        leftProfileId = leftProfileId,
        rightProfileId = rightProfileId,
        leftProfileFingerprint = leftProfileFingerprint,
        rightProfileFingerprint = rightProfileFingerprint,
        match = match,
        score = score,
        evidenceFamilies = evidenceFamilies,
        positiveReasons = positiveReasons,
        negativeReasons = negativeReasons,
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
                profiles.none { CameraDiscoverySource.CACHE in it.discoverySources } -> "LIVE"
                profiles.all { it.discoverySources == setOf(CameraDiscoverySource.CACHE) } -> "CACHE_ONLY"
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

    /** Backend ID summaries include every profile, including failed/hidden aliases. */
    private fun List<CameraRoute>.cameraIdsFrom(
        vararg expectedSources: CameraDiscoverySource,
    ): List<String> {
        val sourceSet = expectedSources.toSet()
        return asSequence()
            .flatMap { route -> route.profiles.asSequence() }
            .filter { profile -> profile.discoverySources.any(sourceSet::contains) }
            .map(CameraProfile::discoveredCameraId)
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
            routes.flatMap { it.profiles }
                .mapNotNull { it.failure?.kind?.name }
                .forEach { add("profile" to it) }
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

    private fun nowUtc(): String = SimpleDateFormat(
        "yyyy-MM-dd'T'HH:mm:ss'Z'",
        Locale.ROOT,
    ).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }.format(Date())
}
