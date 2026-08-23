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
import kotlin.math.round

/**
 * Authoritative Phase 1B canonicalization engine. Transport/profile observations merge by exact
 * routing key first, then strong optical evidence groups those profiles into physical lenses.
 * Camera IDs are never used as optical identity when stable sensor/optical metadata exists.
 */
object CameraTopologyResolver {
    fun resolve(
        environmentFingerprint: CameraEnvironmentFingerprint,
        evidence: Collection<CameraRouteEvidence>,
        cachedTopology: CameraTopology? = null,
        mode: TopologyReconciliationMode = TopologyReconciliationMode.INCREMENTAL,
    ): CameraTopology {
        val live = evidence.mapNotNull(::normalizeEvidence)
        val cache = cachedTopology
            ?.takeIf {
                it.schemaVersion == CameraTopology.CURRENT_SCHEMA_VERSION &&
                    it.environmentFingerprint.isCompatibleWith(environmentFingerprint)
            }
            .orEmptyCandidates()
        val liveKeys = live.mapTo(mutableSetOf(), Candidate::routeKey)
        val retainedCache = cache.filter { candidate ->
            mode != TopologyReconciliationMode.FULLY_RECONCILED || candidate.routeKey in liveKeys
        }
        val candidates = when (mode) {
            TopologyReconciliationMode.CACHE_BOOTSTRAP -> retainedCache
            TopologyReconciliationMode.INCREMENTAL,
            TopologyReconciliationMode.FULLY_RECONCILED,
            -> live + retainedCache
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

    /** Expand a cached canonical lens back into exact profiles before live reconciliation. */
    private fun CameraTopology?.orEmptyCandidates(): List<Candidate> = this?.routes.orEmpty()
        .flatMap { canonical ->
            canonical.profiles.mapNotNull { profile ->
                normalizeCandidate(
                    sourceSet = profile.discoverySources + CameraDiscoverySource.CACHE,
                    discoveredCameraId = profile.discoveredCameraId,
                    openCameraId = profile.openCameraId,
                    streamPhysicalCameraId = profile.streamPhysicalCameraId,
                    logicalParentCameraId = profile.logicalParentCameraId,
                    routeKind = profile.routeKind,
                    minimalMetadata = profile.metadata,
                    fullCapabilities = profile.fullCapabilities,
                    lensFingerprint = canonical.lensFingerprint,
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
            trust = trust.copy(
                failure = trust.failure?.normalized(),
                lastAttemptEpochMs = trust.lastAttemptEpochMs?.takeIf { it >= 0L },
            ),
        )
    }

    /** Multiple backends observing the same transport route become one exact profile snapshot. */
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
            lensFingerprint = chooseFingerprint(candidates.mapNotNull(Candidate::lensFingerprint))
                ?: generateFallbackFingerprint(address, environment),
            role = role.first,
            roleConfidence = role.second,
            trust = trust,
        )
    }

    /**
     * A profile may join a group only if it strongly matches at least one member and conflicts with
     * none. PROBABLE_MATCH remains diagnostics-only so false optical merges stay conservative.
     */
    private fun groupOpticalLenses(
        profiles: List<CameraRoute>,
        environment: CameraEnvironmentFingerprint,
    ): List<CameraRoute> {
        val groups = mutableListOf<MutableList<CameraRoute>>()
        profiles.forEach { candidate ->
            val target = groups.mapIndexedNotNull { index, group ->
                val comparisons = group.map { member -> OpticalLensMatcher.compare(member, candidate) }
                if (comparisons.any { it.match == OpticalLensMatch.CONFLICT }) return@mapIndexedNotNull null
                if (comparisons.none { it.match == OpticalLensMatch.STRONG_MATCH }) return@mapIndexedNotNull null
                index to (comparisons.maxOfOrNull(OpticalLensComparison::score) ?: Int.MIN_VALUE)
            }.sortedWith(
                compareByDescending<Pair<Int, Int>> { it.second }.thenBy { it.first },
            ).firstOrNull()?.first
            if (target == null) groups += mutableListOf(candidate) else groups[target] += candidate
        }
        return groups.map { canonicalizeGroup(it, environment) }
    }

    private fun canonicalizeGroup(
        routes: List<CameraRoute>,
        environment: CameraEnvironmentFingerprint,
    ): CameraRoute {
        require(routes.isNotEmpty())
        val exactProfiles = routes.flatMap(CameraRoute::profiles)
            .distinctBy(CameraProfile::profileId)
            .sortedBy(CameraProfile::profileFingerprint)
        val preferred = CameraProfileSelector.select(exactProfiles)
            ?: exactProfiles.minBy(CameraProfile::profileFingerprint)
        val canonicalMetadata = mergeMetadata(exactProfiles.map(CameraProfile::metadata))
        val canonicalFull = chooseFullCapabilities(exactProfiles.mapNotNull(CameraProfile::fullCapabilities))
        val aggregateTrust = CanonicalLensTrustAggregator.aggregate(exactProfiles, canonicalMetadata)
        val role = classify(canonicalMetadata, aggregateTrust)
        val opticalFingerprint = generateOpticalFingerprint(
            metadata = canonicalMetadata,
            full = canonicalFull,
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
            minimalMetadata = canonicalMetadata,
            fullCapabilities = canonicalFull,
            lensFingerprint = opticalFingerprint,
            role = role.first,
            roleConfidence = role.second,
            trust = aggregateTrust,
            aliases = exactProfiles.filterNot { it.profileId == preferred.profileId }
                .map(CameraProfile::toAlias),
            storedProfiles = exactProfiles,
            preferredProfileId = preferred.profileId,
        )
    }

    /** Merge representative optical metadata without averaging incompatible sensor geometry. */
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
        val physical = chooseVoted(
            values.mapNotNull { it.sensorPhysicalSize?.takeIf(PhysicalSize::isValid) },
        ) { "${quantizeStep(it.widthMm, 0.05)}x${quantizeStep(it.heightMm, 0.05)}" }
        val active = chooseVoted(values.mapNotNull { it.activeArray?.takeIf(SensorRect::isValid) }) {
            "${it.left},${it.top},${it.right},${it.bottom}"
        }
        val pixel = chooseVoted(values.mapNotNull { it.pixelArraySize?.takeIf(Size2D::isValid) }) {
            "${it.width}x${it.height}"
        }
        val orientation = chooseVoted(
            values.mapNotNull { it.sensorOrientationDegrees?.takeIf { value -> value in 0..359 } },
            Int::toString,
        )
        val facing = chooseEnum(values.map(MinimalCameraMetadata::facing), LensFacing.UNKNOWN)
        val hardware = chooseEnum(values.map(MinimalCameraMetadata::hardwareLevel), HardwareLevel.UNKNOWN)
        val providedFov = chooseVoted(
            values.mapNotNull { metadata ->
                metadata.approximateFieldOfView?.takeIf { fov -> fov.isSane }
            },
        ) { fov ->
            listOf(fov.horizontalDegrees, fov.verticalDegrees, fov.diagonalDegrees, fov.focalLengthMm)
                .joinToString(",") { quantizeStep(it, 0.1) }
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
            approximateFieldOfView = providedFov ?: calculateFov(physical, focalLengths.consensus()),
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
            { fullCapabilityScore(it) },
            { fullCapabilityKey(it) },
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

    private fun fullCapabilityKey(full: FullCameraCapabilities): String = with(full.capabilities) {
        buildString {
            append(hardwareLevel.name).append('|')
            append(focalLengthsMm.orEmpty().sorted()).append('|')
            append(sensorPhysicalSize).append('|')
            append(pixelArraySize).append('|')
            append(activeArray).append('|')
            append(colorFilterArrangement).append('|')
            append(streamConfigurations.orEmpty().sortedWith(compareBy(
                { it.format.ordinal }, { it.size.width }, { it.size.height },
            )))
        }
    }

    private fun mergeExactTrust(
        values: List<CameraRouteTrust>,
        metadata: MinimalCameraMetadata,
    ): CameraRouteTrust {
        val metadataTrust = when {
            metadata.systemCameraAdvertised == CapabilitySupport.SUPPORTED -> CameraMetadataTrust.SYSTEM_ONLY
            values.any { it.metadata == CameraMetadataTrust.METADATA_VALID } ||
                metadata.hasPhotographicEvidenceForClassification -> CameraMetadataTrust.METADATA_VALID
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
            values.all { it.raw == CameraRawTrust.NOT_ADVERTISED } -> CameraRawTrust.NOT_ADVERTISED
            values.any { it.raw == CameraRawTrust.RAW_REJECTED } -> CameraRawTrust.RAW_REJECTED
            values.any { it.raw == CameraRawTrust.TRANSIENT_FAILURE } -> CameraRawTrust.TRANSIENT_FAILURE
            metadata.rawCapabilityAdvertised == CapabilitySupport.UNSUPPORTED &&
                metadata.rawStreamActuallyDeclared == CapabilitySupport.UNSUPPORTED -> CameraRawTrust.NOT_ADVERTISED
            else -> CameraRawTrust.UNKNOWN
        }
        val failure = values.mapNotNull(CameraRouteTrust::failure)
            .sortedWith(compareByDescending<CameraRouteFailure> {
                it.durability == CameraFailureDurability.STRUCTURAL
            }.thenBy { it.kind.ordinal }.thenBy { it.detail.orEmpty() })
            .firstOrNull()
        return CameraRouteTrust(
            metadata = metadataTrust,
            session = session,
            raw = raw,
            failure = failure,
            lastAttemptEpochMs = values.mapNotNull(CameraRouteTrust::lastAttemptEpochMs).maxOrNull(),
        )
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
            val confidence = when {
                metadata.hasPhotographicEvidenceForClassification -> RoleConfidence.MODERATE
                metadata.hasAnyMetadata -> RoleConfidence.WEAK
                else -> RoleConfidence.UNKNOWN
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

    /** Stable optical identity excludes all transport IDs when reliable metadata is available. */
    private fun generateOpticalFingerprint(
        metadata: MinimalCameraMetadata,
        full: FullCameraCapabilities?,
        environment: CameraEnvironmentFingerprint,
        fallbackProfile: CameraProfile,
    ): LensFingerprint {
        val stable = metadata.focalLengthsMm.isNotEmpty() &&
            (metadata.sensorPhysicalSize != null || metadata.pixelArraySize != null ||
                metadata.activeArray != null)
        val canonical = if (stable) {
            "optical-lens-v3|${stableOpticalParts(metadata, full)}"
        } else {
            "optical-fallback-v3|${environment.buildFingerprint.trim()}|${fallbackProfile.profileFingerprint}"
        }
        return LensFingerprint(
            value = (if (stable) "ol3_" else "of3_") + sha256(canonical),
            strategy = if (stable) FingerprintStrategy.STABLE_METADATA
            else FingerprintStrategy.DEVICE_SCOPED_FALLBACK,
        )
    }

    /** Tolerant canonical fingerprint: profile-specific crop/binning/RAW variants are not identity. */
    private fun stableOpticalParts(
        metadata: MinimalCameraMetadata,
        full: FullCameraCapabilities?,
    ): String = buildString {
        append("facing=").append(metadata.facing.name)
        append("|focal=").append(metadata.focalLengthsMm.consensus()?.let { quantizeStep(it, 0.2) })
        append("|physical=").append(metadata.sensorPhysicalSize?.let {
            "${quantizeStep(it.widthMm, 0.05)}x${quantizeStep(it.heightMm, 0.05)}"
        })
        append("|pixel=").append(metadata.pixelArraySize)
        append("|active=").append(metadata.activeArray)
        append("|orientation=").append(metadata.sensorOrientationDegrees)
        append("|cfa=").append(full?.capabilities?.colorFilterArrangement?.name ?: "?")
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

    private fun relationships(routes: List<CameraRoute>): List<LogicalCameraRelationship> = routes
        .flatMap { canonical ->
            canonical.profiles.mapNotNull { profile ->
                val parent = profile.logicalParentCameraId ?: return@mapNotNull null
                val physical = profile.streamPhysicalCameraId ?: return@mapNotNull null
                parent to physical
            }
        }
        .groupBy({ it.first }, { it.second })
        .toSortedMap()
        .map { (parent, physical) -> LogicalCameraRelationship(parent, physical.distinct().sorted()) }

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
        get() = hasCrediblePhotographicEvidence ||
            rawCapabilityAdvertised == CapabilitySupport.SUPPORTED

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
        values.isNotEmpty() && values.all { it == CapabilitySupport.UNSUPPORTED } ->
            CapabilitySupport.UNSUPPORTED
        else -> CapabilitySupport.UNKNOWN
    }

    private fun CapabilitySupport.promoteWhen(condition: Boolean): CapabilitySupport =
        if (condition) CapabilitySupport.SUPPORTED else this

    private fun <T : Enum<T>> chooseEnum(values: List<T>, unknown: T): T {
        val known = values.filterNot { it == unknown }
        if (known.isEmpty()) return unknown
        return known.groupingBy { it }.eachCount().entries
            .sortedWith(compareByDescending<Map.Entry<T, Int>> { it.value }
                .thenBy { it.key.ordinal })
            .first().key
    }

    private fun <T> chooseVoted(values: List<T>, key: (T) -> String): T? = values
        .groupBy(key)
        .entries
        .sortedWith(compareByDescending<Map.Entry<String, List<T>>> { it.value.size }
            .thenBy { it.key })
        .firstOrNull()
        ?.value
        ?.first()

    private fun List<Size2D>.validDistinctSizes(): List<Size2D> = filter(Size2D::isValid)
        .distinct()
        .sortedWith(compareByDescending<Size2D> { it.area ?: -1L }
            .thenBy(Size2D::width)
            .thenBy(Size2D::height))

    private fun List<Double>.consensus(): Double? {
        val values = filter(::positiveFinite).sorted()
        if (values.isEmpty()) return null
        val middle = values.size / 2
        return if (values.size % 2 == 1) values[middle] else (values[middle - 1] + values[middle]) / 2.0
    }

    private fun String?.normalizedId(): String? = this?.trim()?.takeIf(String::isNotEmpty)

    private fun positiveFinite(value: Double): Boolean = value.isFinite() && value > 0.0

    private fun quantizeStep(value: Double, step: Double): String =
        (round(value / step) * step).let(java.math.BigDecimal::valueOf)
            .stripTrailingZeros()
            .toPlainString()

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
