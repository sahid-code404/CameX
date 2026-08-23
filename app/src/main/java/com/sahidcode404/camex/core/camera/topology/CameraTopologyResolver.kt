package com.sahidcode404.camex.core.camera.topology

import com.sahidcode404.camex.core.model.CapabilitySupport
import com.sahidcode404.camex.core.model.FieldOfView
import com.sahidcode404.camex.core.model.FingerprintStrategy
import com.sahidcode404.camex.core.model.HardwareLevel
import com.sahidcode404.camex.core.model.LensFacing
import com.sahidcode404.camex.core.model.LensFingerprint
import com.sahidcode404.camex.core.model.PhysicalSize
import com.sahidcode404.camex.core.model.SensorRect
import com.sahidcode404.camex.core.model.Size2D
import com.sahidcode404.camex.core.model.StreamFormat
import com.sahidcode404.camex.core.model.isPortableRaw
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import kotlin.math.atan
import kotlin.math.hypot

/**
 * Authoritative Phase 1B canonicalization engine. Exact transport observations merge first; then
 * confidence-based optical grouping turns vendor aliases into profiles under one physical lens.
 * It is pure, deterministic, metadata-only, and never opens a camera.
 */
object CameraTopologyResolver {
    fun resolve(
        environmentFingerprint: CameraEnvironmentFingerprint,
        evidence: Collection<CameraRouteEvidence>,
        cachedTopology: CameraTopology? = null,
        mode: TopologyReconciliationMode = TopologyReconciliationMode.INCREMENTAL,
    ): CameraTopology {
        val liveCandidates = evidence.mapNotNull(::normalizeEvidence)
        val compatibleCache = cachedTopology?.takeIf {
            it.schemaVersion == CameraTopology.CURRENT_SCHEMA_VERSION &&
                it.environmentFingerprint.isCompatibleWith(environmentFingerprint)
        }
        val liveKeys = liveCandidates.mapTo(mutableSetOf()) { it.routeKey }
        val cachedCandidates = compatibleCache.orEmptyCandidates()
            .filter { candidate ->
                mode != TopologyReconciliationMode.FULLY_RECONCILED ||
                    candidate.routeKey in liveKeys
            }

        val candidates = when (mode) {
            TopologyReconciliationMode.CACHE_BOOTSTRAP -> cachedCandidates
            TopologyReconciliationMode.INCREMENTAL,
            TopologyReconciliationMode.FULLY_RECONCILED,
            -> liveCandidates + cachedCandidates
        }

        val exactProfiles = candidates
            .groupBy(Candidate::routeKey)
            .toSortedMap()
            .values
            .map { mergeExactProfile(it, environmentFingerprint) }
            .sortedWith(profileRouteComparator)

        val canonicalLenses = groupOpticalLenses(exactProfiles, environmentFingerprint)
            .sortedWith(canonicalRouteComparator)
        return CameraTopology(
            environmentFingerprint = environmentFingerprint,
            routes = canonicalLenses,
            logicalRelationships = relationships(canonicalLenses),
        )
    }

    /** Cache entries are canonical lenses; reconciliation expands them back into profile evidence. */
    private fun CameraTopology?.orEmptyCandidates(): List<Candidate> = this?.routes.orEmpty()
        .flatMap { route ->
            route.profiles.mapNotNull { profile ->
                normalizeCandidate(
                    sourceSet = profile.discoverySources + CameraDiscoverySource.CACHE,
                    discoveredCameraId = profile.discoveredCameraId,
                    openCameraId = profile.openCameraId,
                    streamPhysicalCameraId = profile.streamPhysicalCameraId,
                    logicalParentCameraId = profile.logicalParentCameraId,
                    routeKind = profile.routeKind,
                    minimalMetadata = profile.metadata,
                    fullCapabilities = profile.fullCapabilities,
                    lensFingerprint = route.lensFingerprint,
                    trust = profile.trust,
                )
            }
        }

    private fun normalizeEvidence(evidence: CameraRouteEvidence): Candidate? = normalizeCandidate(
        sourceSet = setOf(evidence.source),
        discoveredCameraId = evidence.discoveredCameraId,
        openCameraId = evidence.openCameraId,
        streamPhysicalCameraId = evidence.streamPhysicalCameraId,
        logicalParentCameraId = evidence.logicalParentCameraId,
        routeKind = evidence.routeKind,
        minimalMetadata = evidence.minimalMetadata,
        fullCapabilities = evidence.fullCapabilities,
        lensFingerprint = evidence.lensFingerprint,
        trust = evidence.trust,
    )

    private fun normalizeCandidate(
        sourceSet: Set<CameraDiscoverySource>,
        discoveredCameraId: String,
        openCameraId: String,
        streamPhysicalCameraId: String?,
        logicalParentCameraId: String?,
        routeKind: CameraRouteKind,
        minimalMetadata: MinimalCameraMetadata,
        fullCapabilities: FullCameraCapabilities?,
        lensFingerprint: LensFingerprint?,
        trust: CameraRouteTrust,
    ): Candidate? {
        val discovered = discoveredCameraId.trim().takeIf(String::isNotEmpty)
        val physical = streamPhysicalCameraId.normalizedId()
        val parent = logicalParentCameraId.normalizedId()
        val requestedOpen = openCameraId.trim().takeIf(String::isNotEmpty)
        val open = if (physical != null && parent != null) parent else requestedOpen ?: discovered
        if (open == null) return null
        return Candidate(
            sourceSet = sourceSet,
            discoveredCameraId = discovered ?: physical ?: open,
            openCameraId = open,
            streamPhysicalCameraId = physical,
            logicalParentCameraId = parent,
            routeKind = routeKind,
            minimalMetadata = minimalMetadata.normalized(),
            fullCapabilities = fullCapabilities,
            lensFingerprint = lensFingerprint?.takeIf { it.value.isNotBlank() },
            trust = trust.copy(failure = trust.failure?.normalized()),
        )
    }

    /** Merge Java/NDK/cache observations that name the exact same transport route. */
    private fun mergeExactProfile(
        candidates: List<Candidate>,
        environment: CameraEnvironmentFingerprint,
    ): CameraRoute {
        require(candidates.isNotEmpty())
        val address = candidates.minWith(candidateAddressComparator)
        val metadata = mergeMetadata(candidates.map(Candidate::minimalMetadata))
        val full = chooseFullCapabilities(candidates.mapNotNull(Candidate::fullCapabilities))
        val trust = mergeExactTrust(candidates.map(Candidate::trust), metadata)
        val role = classify(metadata, trust)
        return CameraRoute(
            canonicalRouteId = address.routeKey,
            discoveredCameraId = address.discoveredCameraId,
            openCameraId = address.openCameraId,
            streamPhysicalCameraId = address.streamPhysicalCameraId,
            logicalParentCameraId = address.logicalParentCameraId,
            routeKind = address.routeKind,
            sources = candidates.flatMapTo(sortedSetOf(compareBy(CameraDiscoverySource::ordinal))) {
                it.sourceSet
            },
            minimalMetadata = metadata,
            fullCapabilities = full,
            // This is replaced by an optical fingerprint after grouping. It remains useful as a
            // deterministic fallback if metadata is too sparse for stable optical identity.
            lensFingerprint = chooseFingerprint(candidates.mapNotNull(Candidate::lensFingerprint))
                ?: generateFallbackFingerprint(address, environment),
            role = role.first,
            roleConfidence = role.second,
            trust = trust,
        )
    }

    /**
     * Build clique-safe optical groups. A candidate must strongly match at least one member and may
     * not conflict with any existing member. Ambiguous/probable matches remain separate lenses and
     * are visible in diagnostics rather than being dangerously collapsed.
     */
    private fun groupOpticalLenses(
        profiles: List<CameraRoute>,
        environment: CameraEnvironmentFingerprint,
    ): List<CameraRoute> {
        if (profiles.isEmpty()) return emptyList()
        val groups = mutableListOf<MutableList<CameraRoute>>()
        profiles.forEach { profile ->
            val eligible = groups.mapIndexedNotNull { index, group ->
                val comparisons = group.map { member -> OpticalLensMatcher.compare(member, profile) }
                if (comparisons.any { it.match == OpticalLensMatch.CONFLICT }) return@mapIndexedNotNull null
                val strongest = comparisons.maxOfOrNull(OpticalLensComparison::score) ?: Int.MIN_VALUE
                if (comparisons.none { it.match == OpticalLensMatch.STRONG_MATCH }) null
                else index to strongest
            }
            val target = eligible
                .sortedWith(compareByDescending<Pair<Int, Int>> { it.second }.thenBy { it.first })
                .firstOrNull()
                ?.first
            if (target == null) groups += mutableListOf(profile) else groups[target] += profile
        }
        return groups.map { group -> canonicalizeGroup(group, environment) }
    }

    private fun canonicalizeGroup(
        routes: List<CameraRoute>,
        environment: CameraEnvironmentFingerprint,
    ): CameraRoute {
        require(routes.isNotEmpty())
        val allProfiles = routes.flatMap(CameraRoute::profiles)
        val preferred = CameraProfileSelector.select(allProfiles)
            ?: allProfiles.minBy(CameraProfile::profileFingerprint)
        val metadata = mergeMetadata(allProfiles.map(CameraProfile::metadata))
        val full = chooseFullCapabilities(allProfiles.mapNotNull(CameraProfile::fullCapabilities))
        val preferredTrust = preferred.trust
        val role = classify(metadata, aggregateLensMetadataTrust(allProfiles, metadata))
        val opticalFingerprint = generateOpticalFingerprint(
            metadata = metadata,
            full = full,
            environment = environment,
            fallbackProfile = preferred,
        )
        return CameraRoute(
            canonicalRouteId = preferred.profileId,
            discoveredCameraId = preferred.discoveredCameraId,
            openCameraId = preferred.openCameraId,
            streamPhysicalCameraId = preferred.streamPhysicalCameraId,
            logicalParentCameraId = preferred.logicalParentCameraId,
            routeKind = preferred.routeKind,
            sources = preferred.discoverySources,
            minimalMetadata = metadata,
            fullCapabilities = full ?: preferred.fullCapabilities,
            lensFingerprint = opticalFingerprint,
            role = role.first,
            roleConfidence = role.second,
            // Runtime usability tracks the preferred profile. If that profile is structurally
            // rejected CameraProfileSelector promotes another profile before this reaches the UI.
            trust = preferredTrust,
            aliases = allProfiles
                .filterNot { it.profileId == preferred.profileId }
                .distinctBy(CameraProfile::profileId)
                .sortedWith(compareBy<CameraProfile>(
                    { routeKindRank(it.routeKind) },
                    CameraProfile::profileFingerprint,
                ))
                .map(CameraProfile::toAlias),
        )
    }

    private fun aggregateLensMetadataTrust(
        profiles: List<CameraProfile>,
        metadata: MinimalCameraMetadata,
    ): CameraRouteTrust {
        val metadataTrust = when {
            metadata.systemCameraAdvertised == CapabilitySupport.SUPPORTED -> CameraMetadataTrust.SYSTEM_ONLY
            profiles.any { it.metadataTrust == CameraMetadataTrust.METADATA_VALID } ||
                metadata.hasPhotographicEvidenceForClassification -> CameraMetadataTrust.METADATA_VALID
            profiles.all { it.metadataTrust == CameraMetadataTrust.ACCESS_DENIED } -> CameraMetadataTrust.ACCESS_DENIED
            profiles.all { it.metadataTrust == CameraMetadataTrust.SYSTEM_ONLY } -> CameraMetadataTrust.SYSTEM_ONLY
            profiles.all { it.metadataTrust == CameraMetadataTrust.BROKEN } -> CameraMetadataTrust.BROKEN
            profiles.any { it.metadataTrust == CameraMetadataTrust.DISCOVERED } -> CameraMetadataTrust.DISCOVERED
            else -> CameraMetadataTrust.UNKNOWN
        }
        val session = when {
            profiles.any { it.sessionTrust == CameraSessionTrust.SESSION_VERIFIED } ->
                CameraSessionTrust.SESSION_VERIFIED
            profiles.isNotEmpty() && profiles.all { it.structurallyRejected } ->
                CameraSessionTrust.SESSION_REJECTED
            profiles.any { it.sessionTrust == CameraSessionTrust.TRANSIENT_FAILURE } ->
                CameraSessionTrust.TRANSIENT_FAILURE
            else -> CameraSessionTrust.UNKNOWN
        }
        val raw = when {
            profiles.any { it.rawTrust == CameraRawTrust.RAW_VERIFIED } -> CameraRawTrust.RAW_VERIFIED
            profiles.isNotEmpty() && profiles.all {
                it.rawTrust == CameraRawTrust.RAW_REJECTED || it.rawTrust == CameraRawTrust.NOT_ADVERTISED
            } -> CameraRawTrust.RAW_REJECTED
            profiles.any { it.rawTrust == CameraRawTrust.TRANSIENT_FAILURE } -> CameraRawTrust.TRANSIENT_FAILURE
            profiles.all { it.rawTrust == CameraRawTrust.NOT_ADVERTISED } -> CameraRawTrust.NOT_ADVERTISED
            else -> CameraRawTrust.UNKNOWN
        }
        val failure = if (session == CameraSessionTrust.SESSION_REJECTED) {
            profiles.mapNotNull(CameraProfile::failure)
                .filter { it.durability == CameraFailureDurability.STRUCTURAL }
                .sortedWith(compareBy(CameraRouteFailure::kind, { it.detail.orEmpty() }))
                .firstOrNull()
        } else {
            null
        }
        return CameraRouteTrust(metadataTrust, session, raw, failure)
    }

    private fun mergeMetadata(values: List<MinimalCameraMetadata>): MinimalCameraMetadata {
        val focalLengths = values.flatMap(MinimalCameraMetadata::focalLengthsMm)
            .filter(::positiveFinite)
            .distinct()
            .sorted()
        val rawFormats = values.flatMapTo(mutableSetOf(), MinimalCameraMetadata::rawFormats)
            .filterTo(sortedSetOf(compareBy(StreamFormat::ordinal))) { it.isRawLike }
        val rawSizes = values.flatMap(MinimalCameraMetadata::rawSizes).validDistinctSizes()
        val privatePreviewSizes = values.flatMap(MinimalCameraMetadata::privatePreviewSizes)
            .validDistinctSizes()
        val yuvPreviewSizes = values.flatMap(MinimalCameraMetadata::yuvPreviewSizes)
            .validDistinctSizes()
        val physical = chooseVoted(values.mapNotNull { it.sensorPhysicalSize?.takeIf(PhysicalSize::isValid) }) {
            "${decimal(it.widthMm)}x${decimal(it.heightMm)}"
        }
        val active = chooseVoted(values.mapNotNull { it.activeArray?.takeIf(SensorRect::isValid) }) {
            "${it.left},${it.top},${it.right},${it.bottom}"
        }
        val pixel = chooseVoted(values.mapNotNull { it.pixelArraySize?.takeIf(Size2D::isValid) }) {
            "${it.width}x${it.height}"
        }
        val orientation = chooseVoted(
            values.mapNotNull { it.sensorOrientationDegrees?.takeIf { degrees -> degrees in 0..359 } },
            Int::toString,
        )
        val facing = chooseEnum(values.map(MinimalCameraMetadata::facing), LensFacing.UNKNOWN)
        val hardware = chooseEnum(values.map(MinimalCameraMetadata::hardwareLevel), HardwareLevel.UNKNOWN)
        val providedFov = chooseVoted(
            values.mapNotNull { it.approximateFieldOfView?.takeIf(FieldOfView::isSane) },
        ) {
            listOf(it.horizontalDegrees, it.verticalDegrees, it.diagonalDegrees, it.focalLengthMm)
                .joinToString(",", transform = ::decimal)
        }
        val rawDeclared = mergeSupport(values.map(MinimalCameraMetadata::rawStreamActuallyDeclared))
            .promoteWhen(rawFormats.isNotEmpty() || rawSizes.isNotEmpty())
        val previewDeclared = mergeSupport(values.map(MinimalCameraMetadata::previewStreamActuallyDeclared))
            .promoteWhen(privatePreviewSizes.isNotEmpty() || yuvPreviewSizes.isNotEmpty())
        return MinimalCameraMetadata(
            facing = facing,
            focalLengthsMm = focalLengths,
            sensorPhysicalSize = physical,
            activeArray = active,
            pixelArraySize = pixel,
            sensorOrientationDegrees = orientation,
            approximateFieldOfView = providedFov ?: calculateFov(physical, focalLengths.firstOrNull()),
            hardwareLevel = hardware,
            backwardCompatibleAdvertised = mergeSupport(values.map(MinimalCameraMetadata::backwardCompatibleAdvertised)),
            rawCapabilityAdvertised = mergeSupport(values.map(MinimalCameraMetadata::rawCapabilityAdvertised)),
            rawStreamActuallyDeclared = rawDeclared,
            rawFormats = rawFormats,
            rawSizes = rawSizes,
            previewStreamActuallyDeclared = previewDeclared,
            privatePreviewSizes = privatePreviewSizes,
            yuvPreviewSizes = yuvPreviewSizes,
            depthEvidence = mergeSupport(values.map(MinimalCameraMetadata::depthEvidence)),
            tofEvidence = mergeSupport(values.map(MinimalCameraMetadata::tofEvidence)),
            infraredEvidence = mergeSupport(values.map(MinimalCameraMetadata::infraredEvidence)),
            monochromeEvidence = mergeSupport(values.map(MinimalCameraMetadata::monochromeEvidence)),
            systemCameraAdvertised = mergeSupport(values.map(MinimalCameraMetadata::systemCameraAdvertised)),
        )
    }

    private fun MinimalCameraMetadata.normalized(): MinimalCameraMetadata = mergeMetadata(listOf(this))

    private fun chooseFullCapabilities(values: List<FullCameraCapabilities>): FullCameraCapabilities? =
        values.maxWithOrNull(compareBy<FullCameraCapabilities>(
            ::fullCapabilityScore,
            ::fullCapabilityKey,
        ))

    private fun fullCapabilityScore(full: FullCameraCapabilities): Int {
        val value = full.capabilities
        return (if (full.complete) 100 else 0) +
            value.streamConfigurations.orEmpty().size * 3 +
            value.highSpeedConfigurations.orEmpty().size * 2 +
            value.focalLengthsMm.orEmpty().size +
            listOf(
                value.sensorPhysicalSize,
                value.pixelArraySize,
                value.activeArray,
                value.isoRange,
                value.exposureTimeRangeNs,
                value.minimumFocusDistanceDiopters,
                value.colorFilterArrangement,
            ).count { it != null }
    }

    private fun fullCapabilityKey(full: FullCameraCapabilities): String {
        val value = full.capabilities
        return buildString {
            append(value.hardwareLevel.name).append('|')
            append(value.focalLengthsMm.orEmpty().sorted()).append('|')
            append(value.sensorPhysicalSize).append('|')
            append(value.pixelArraySize).append('|')
            append(value.activeArray).append('|')
            append(value.colorFilterArrangement).append('|')
            append(value.streamConfigurations.orEmpty().sortedWith(compareBy(
                { it.format.ordinal }, { it.size.width }, { it.size.height },
            )))
        }
    }

    private fun mergeExactTrust(
        values: List<CameraRouteTrust>,
        metadata: MinimalCameraMetadata,
    ): CameraRouteTrust {
        val photographicMetadata = metadata.hasPhotographicEvidenceForClassification
        val metadataTrust = when {
            metadata.systemCameraAdvertised == CapabilitySupport.SUPPORTED -> CameraMetadataTrust.SYSTEM_ONLY
            values.any { it.metadata == CameraMetadataTrust.METADATA_VALID } -> CameraMetadataTrust.METADATA_VALID
            photographicMetadata -> CameraMetadataTrust.METADATA_VALID
            values.any { it.metadata == CameraMetadataTrust.BROKEN } -> CameraMetadataTrust.BROKEN
            values.any { it.metadata == CameraMetadataTrust.ACCESS_DENIED } &&
                values.none { it.metadata == CameraMetadataTrust.DISCOVERED } -> CameraMetadataTrust.ACCESS_DENIED
            values.any { it.metadata == CameraMetadataTrust.SYSTEM_ONLY } -> CameraMetadataTrust.SYSTEM_ONLY
            values.any { it.metadata == CameraMetadataTrust.METADATA_REJECTED } -> CameraMetadataTrust.METADATA_REJECTED
            values.any { it.metadata == CameraMetadataTrust.DISCOVERED } -> CameraMetadataTrust.DISCOVERED
            else -> CameraMetadataTrust.UNKNOWN
        }
        val session = when {
            values.any { it.session == CameraSessionTrust.SESSION_VERIFIED } -> CameraSessionTrust.SESSION_VERIFIED
            values.any { it.session == CameraSessionTrust.SESSION_REJECTED } -> CameraSessionTrust.SESSION_REJECTED
            values.any { it.session == CameraSessionTrust.TRANSIENT_FAILURE } -> CameraSessionTrust.TRANSIENT_FAILURE
            else -> CameraSessionTrust.UNKNOWN
        }
        val raw = when {
            values.any { it.raw == CameraRawTrust.RAW_VERIFIED } -> CameraRawTrust.RAW_VERIFIED
            values.any { it.raw == CameraRawTrust.RAW_REJECTED } -> CameraRawTrust.RAW_REJECTED
            values.any { it.raw == CameraRawTrust.TRANSIENT_FAILURE } -> CameraRawTrust.TRANSIENT_FAILURE
            values.any { it.raw == CameraRawTrust.NOT_ADVERTISED } -> CameraRawTrust.NOT_ADVERTISED
            metadata.rawCapabilityAdvertised == CapabilitySupport.UNSUPPORTED &&
                metadata.rawStreamActuallyDeclared == CapabilitySupport.UNSUPPORTED -> CameraRawTrust.NOT_ADVERTISED
            else -> CameraRawTrust.UNKNOWN
        }
        val failure = values.mapNotNull(CameraRouteTrust::failure)
            .sortedWith(compareByDescending<CameraRouteFailure> {
                it.durability == CameraFailureDurability.STRUCTURAL
            }.thenBy { it.kind.ordinal }.thenBy { it.detail.orEmpty() })
            .firstOrNull()
        return CameraRouteTrust(metadataTrust, session, raw, failure)
    }

    private fun classify(
        metadata: MinimalCameraMetadata,
        trust: CameraRouteTrust,
    ): Pair<PhotographicRole, RoleConfidence> {
        if (trust.metadata == CameraMetadataTrust.SYSTEM_ONLY ||
            metadata.systemCameraAdvertised == CapabilitySupport.SUPPORTED
        ) return PhotographicRole.SYSTEM_ONLY to RoleConfidence.CONFIRMED
        if (trust.metadata == CameraMetadataTrust.ACCESS_DENIED) {
            return PhotographicRole.INACCESSIBLE to RoleConfidence.CONFIRMED
        }
        if (trust.metadata == CameraMetadataTrust.BROKEN) {
            return PhotographicRole.BROKEN to RoleConfidence.CONFIRMED
        }
        if (metadata.infraredEvidence == CapabilitySupport.SUPPORTED) {
            return PhotographicRole.NON_PHOTO_IR to RoleConfidence.STRONG
        }
        if (metadata.tofEvidence == CapabilitySupport.SUPPORTED) {
            return PhotographicRole.NON_PHOTO_TOF to RoleConfidence.STRONG
        }
        if (metadata.depthEvidence == CapabilitySupport.SUPPORTED &&
            !metadata.hasPhotographicEvidenceForClassification
        ) return PhotographicRole.NON_PHOTO_DEPTH to RoleConfidence.STRONG
        if (metadata.monochromeEvidence == CapabilitySupport.SUPPORTED &&
            metadata.hasPhotographicEvidenceForClassification
        ) return PhotographicRole.PHOTOGRAPHIC_MONO to RoleConfidence.STRONG

        val fov = metadata.approximateFieldOfView?.diagonalDegrees
            ?.takeIf { it.isFinite() && it > 0.0 && it < 180.0 }
        if (fov == null || !metadata.hasPhotographicEvidenceForClassification) {
            val confidence = if (metadata.hasPhotographicEvidenceForClassification) {
                RoleConfidence.MODERATE
            } else if (metadata.hasAnyMetadata) {
                RoleConfidence.WEAK
            } else {
                RoleConfidence.UNKNOWN
            }
            return PhotographicRole.PHOTOGRAPHIC_UNKNOWN to confidence
        }
        val role = when {
            fov >= 90.0 -> PhotographicRole.PHOTOGRAPHIC_ULTRAWIDE
            fov >= 55.0 -> PhotographicRole.PHOTOGRAPHIC_WIDE
            fov >= 25.0 -> PhotographicRole.PHOTOGRAPHIC_TELEPHOTO
            else -> PhotographicRole.PHOTOGRAPHIC_SUPER_TELEPHOTO
        }
        return role to RoleConfidence.STRONG
    }

    private fun chooseFingerprint(values: List<LensFingerprint>): LensFingerprint? = values
        .sortedWith(compareByDescending<LensFingerprint> {
            it.strategy == FingerprintStrategy.STABLE_METADATA
        }.thenBy(LensFingerprint::value))
        .firstOrNull()

    /** Stable optical identity contains no Camera2/vendor ID when sufficient sensor metadata exists. */
    private fun generateOpticalFingerprint(
        metadata: MinimalCameraMetadata,
        full: FullCameraCapabilities?,
        environment: CameraEnvironmentFingerprint,
        fallbackProfile: CameraProfile,
    ): LensFingerprint {
        val stable = metadata.focalLengthsMm.isNotEmpty() &&
            (metadata.sensorPhysicalSize != null || metadata.pixelArraySize != null ||
                metadata.activeArray != null || metadata.rawSizes.isNotEmpty())
        val canonical = if (stable) {
            "optical-lens-v3|${stableOpticalParts(metadata, full)}"
        } else {
            buildString {
                append("optical-fallback-v3|")
                append(environment.buildFingerprint.trim()).append('|')
                append(fallbackProfile.profileFingerprint)
            }
        }
        return LensFingerprint(
            value = (if (stable) "ol3_" else "of3_") + sha256(canonical),
            strategy = if (stable) FingerprintStrategy.STABLE_METADATA
            else FingerprintStrategy.DEVICE_SCOPED_FALLBACK,
        )
    }

    private fun generateFallbackFingerprint(
        candidate: Candidate,
        environment: CameraEnvironmentFingerprint,
    ): LensFingerprint = LensFingerprint(
        value = "pf3_" + sha256(
            "profile-fallback-v3|${environment.buildFingerprint}|${candidate.routeKey}",
        ),
        strategy = FingerprintStrategy.DEVICE_SCOPED_FALLBACK,
    )

    private fun stableOpticalParts(
        metadata: MinimalCameraMetadata,
        full: FullCameraCapabilities?,
    ): String = buildString {
        append("facing=").append(metadata.facing.name)
        append("|focal=").append(metadata.focalLengthsMm.joinToString(",") { quantized(it, 1000.0) })
        append("|physical=").append(metadata.sensorPhysicalSize?.let {
            "${quantized(it.widthMm, 1000.0)}x${quantized(it.heightMm, 1000.0)}"
        })
        append("|pixel=").append(metadata.pixelArraySize)
        append("|active=").append(metadata.activeArray)
        append("|rawSizes=").append(metadata.rawSizes)
        append("|orientation=").append(metadata.sensorOrientationDegrees)
        append("|cfa=").append(full?.capabilities?.colorFilterArrangement?.name ?: "?")
    }

    private fun relationships(routes: List<CameraRoute>): List<LogicalCameraRelationship> = routes
        .flatMap { route ->
            route.profiles.mapNotNull { profile ->
                val parent = profile.logicalParentCameraId ?: return@mapNotNull null
                val physical = profile.streamPhysicalCameraId ?: return@mapNotNull null
                parent to physical
            }
        }
        .groupBy({ it.first }, { it.second })
        .toSortedMap()
        .map { (parent, physical) ->
            LogicalCameraRelationship(parent, physical.distinct().sorted())
        }

    private fun calculateFov(sensor: PhysicalSize?, focal: Double?): FieldOfView? {
        if (sensor?.isValid != true || focal == null || !positiveFinite(focal)) return null
        fun degrees(size: Double): Double = Math.toDegrees(2.0 * atan(size / (2.0 * focal)))
        return FieldOfView(
            horizontalDegrees = degrees(sensor.widthMm),
            verticalDegrees = degrees(sensor.heightMm),
            diagonalDegrees = degrees(hypot(sensor.widthMm, sensor.heightMm)),
            focalLengthMm = focal,
        )
    }

    private val MinimalCameraMetadata.hasPhotographicEvidenceForClassification: Boolean
        get() = hasCrediblePhotographicEvidence || rawCapabilityAdvertised == CapabilitySupport.SUPPORTED

    private val MinimalCameraMetadata.hasAnyMetadata: Boolean
        get() = facing != LensFacing.UNKNOWN || focalLengthsMm.isNotEmpty() ||
            sensorPhysicalSize != null || activeArray != null || pixelArraySize != null ||
            sensorOrientationDegrees != null || hardwareLevel != HardwareLevel.UNKNOWN ||
            listOf(
                backwardCompatibleAdvertised,
                rawCapabilityAdvertised,
                rawStreamActuallyDeclared,
                previewStreamActuallyDeclared,
                depthEvidence,
                tofEvidence,
                infraredEvidence,
                monochromeEvidence,
                systemCameraAdvertised,
            ).any { it != CapabilitySupport.UNKNOWN }

    private val StreamFormat.isRawLike: Boolean
        get() = isPortableRaw || this == StreamFormat.RAW_PRIVATE

    private val FieldOfView.isSane: Boolean
        get() = listOf(horizontalDegrees, verticalDegrees, diagonalDegrees, focalLengthMm)
            .all { it.isFinite() && it > 0.0 } && diagonalDegrees < 180.0

    private fun mergeSupport(values: List<CapabilitySupport>): CapabilitySupport = when {
        values.any { it == CapabilitySupport.SUPPORTED } -> CapabilitySupport.SUPPORTED
        values.isNotEmpty() && values.all { it == CapabilitySupport.UNSUPPORTED } -> CapabilitySupport.UNSUPPORTED
        else -> CapabilitySupport.UNKNOWN
    }

    private fun CapabilitySupport.promoteWhen(condition: Boolean): CapabilitySupport =
        if (condition) CapabilitySupport.SUPPORTED else this

    private fun <T : Enum<T>> chooseEnum(values: List<T>, unknown: T): T {
        val known = values.filterNot { it == unknown }
        if (known.isEmpty()) return unknown
        return known.groupingBy { it }.eachCount().entries
            .sortedWith(compareByDescending<Map.Entry<T, Int>>(Map.Entry<T, Int>::value)
                .thenBy { it.key.ordinal })
            .first().key
    }

    private fun <T> chooseVoted(values: List<T>, key: (T) -> String): T? = values
        .groupBy(key)
        .entries
        .sortedWith(compareByDescending<Map.Entry<String, List<T>>> { it.value.size }
            .thenBy(Map.Entry<String, List<T>>::key))
        .firstOrNull()
        ?.value
        ?.first()

    private fun List<Size2D>.validDistinctSizes(): List<Size2D> = filter(Size2D::isValid)
        .distinct()
        .sortedWith(compareByDescending<Size2D> { it.area ?: -1L }
            .thenBy(Size2D::width)
            .thenBy(Size2D::height))

    private fun String?.normalizedId(): String? = this?.trim()?.takeIf(String::isNotEmpty)

    private fun positiveFinite(value: Double): Boolean = value.isFinite() && value > 0.0

    private fun decimal(value: Double): String = java.math.BigDecimal.valueOf(value)
        .stripTrailingZeros()
        .toPlainString()

    private fun quantized(value: Double, scale: Double): String =
        (kotlin.math.round(value * scale) / scale).toString()

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(StandardCharsets.UTF_8))
        .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }

    private fun routeKindRank(kind: CameraRouteKind): Int = when (kind) {
        CameraRouteKind.PUBLIC_DIRECT -> 0
        CameraRouteKind.EXTERNAL -> 1
        CameraRouteKind.LOGICAL_PHYSICAL_MEMBER -> 2
        CameraRouteKind.LOGICAL_PARENT -> 3
        CameraRouteKind.NDK_DIRECT -> 4
        CameraRouteKind.DEEP_NDK_DIRECT -> 5
    }

    private val candidateAddressComparator = compareBy<Candidate>(
        { routeKindRank(it.routeKind) },
        Candidate::openCameraId,
        { it.streamPhysicalCameraId.orEmpty() },
        Candidate::discoveredCameraId,
    )

    private val profileRouteComparator = compareBy<CameraRoute>(
        { it.minimalMetadata.facing.ordinal },
        { routeKindRank(it.routeKind) },
        CameraRoute::canonicalRouteId,
    )

    private val canonicalRouteComparator = compareBy<CameraRoute>(
        { it.minimalMetadata.facing.ordinal },
        { it.minimalMetadata.approximateFieldOfView?.diagonalDegrees ?: Double.NEGATIVE_INFINITY },
        { it.lensFingerprint?.value.orEmpty() },
        CameraRoute::canonicalRouteId,
    )

    private data class Candidate(
        val sourceSet: Set<CameraDiscoverySource>,
        val discoveredCameraId: String,
        val openCameraId: String,
        val streamPhysicalCameraId: String?,
        val logicalParentCameraId: String?,
        val routeKind: CameraRouteKind,
        val minimalMetadata: MinimalCameraMetadata,
        val fullCapabilities: FullCameraCapabilities?,
        val lensFingerprint: LensFingerprint?,
        val trust: CameraRouteTrust,
    ) {
        val routeKey: String get() = canonicalRouteId(openCameraId, streamPhysicalCameraId)
    }
}
