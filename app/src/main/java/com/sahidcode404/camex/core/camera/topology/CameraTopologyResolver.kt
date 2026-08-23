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
 * Pure deterministic reconciliation. It never opens cameras and never treats absent metadata as a
 * rejection. Exact routes merge freely; cross-route aliases merge only when they name the same
 * hardware camera and carry a strong matching optical signature.
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

        val exactRoutes = candidates
            .groupBy(Candidate::routeKey)
            .toSortedMap()
            .values
            .map { mergeExactRoute(it, environmentFingerprint) }

        val canonicalRoutes = mergeStrongAliases(exactRoutes, environmentFingerprint)
            .sortedWith(routeComparator)
        return CameraTopology(
            environmentFingerprint = environmentFingerprint,
            routes = canonicalRoutes,
            logicalRelationships = relationships(canonicalRoutes),
        )
    }

    private fun CameraTopology?.orEmptyCandidates(): List<Candidate> = this?.routes.orEmpty()
        .flatMap { route ->
            val primary = Candidate(
                sourceSet = route.sources + CameraDiscoverySource.CACHE,
                discoveredCameraId = route.discoveredCameraId,
                openCameraId = route.openCameraId,
                streamPhysicalCameraId = route.streamPhysicalCameraId,
                logicalParentCameraId = route.logicalParentCameraId,
                routeKind = route.routeKind,
                minimalMetadata = route.minimalMetadata.normalized(),
                fullCapabilities = route.fullCapabilities,
                lensFingerprint = route.lensFingerprint,
                trust = route.trust,
            )
            listOf(primary) + route.aliases.mapNotNull { alias ->
                normalizeCandidate(
                    sourceSet = alias.sources + CameraDiscoverySource.CACHE,
                    discoveredCameraId = alias.discoveredCameraId,
                    openCameraId = alias.openCameraId,
                    streamPhysicalCameraId = alias.streamPhysicalCameraId,
                    logicalParentCameraId = alias.logicalParentCameraId,
                    routeKind = alias.routeKind,
                    minimalMetadata = route.minimalMetadata,
                    fullCapabilities = route.fullCapabilities,
                    lensFingerprint = route.lensFingerprint,
                    trust = route.trust,
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

    private fun mergeExactRoute(
        candidates: List<Candidate>,
        environment: CameraEnvironmentFingerprint,
    ): CameraRoute {
        require(candidates.isNotEmpty())
        val address = candidates.minWith(candidateAddressComparator)
        val metadata = mergeMetadata(candidates.map(Candidate::minimalMetadata))
        val full = chooseFullCapabilities(candidates.mapNotNull(Candidate::fullCapabilities))
        val trust = mergeTrust(candidates.map(Candidate::trust), metadata)
        val role = classify(metadata, trust)
        val fingerprint = chooseFingerprint(candidates.mapNotNull(Candidate::lensFingerprint))
            ?: generateFingerprint(address, metadata, full, environment)
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
            lensFingerprint = fingerprint,
            role = role.first,
            roleConfidence = role.second,
            trust = trust,
        )
    }

    private fun mergeStrongAliases(
        routes: List<CameraRoute>,
        environment: CameraEnvironmentFingerprint,
    ): List<CameraRoute> {
        if (routes.size < 2) return routes
        val byAliasKey = routes.groupBy { route ->
            val hardwareId = route.streamPhysicalCameraId ?: route.discoveredCameraId
            val signature = strongOpticalSignature(route.minimalMetadata)
            if (hardwareId.isBlank() || signature == null) {
                "unique:${route.canonicalRouteId}"
            } else {
                "hardware:${hardwareId.length}:$hardwareId|$signature"
            }
        }
        return byAliasKey.toSortedMap().values.map { aliases ->
            if (aliases.size == 1) aliases.single() else mergeAliasRoutes(aliases, environment)
        }
    }

    private fun mergeAliasRoutes(
        routes: List<CameraRoute>,
        environment: CameraEnvironmentFingerprint,
    ): CameraRoute {
        val candidates = routes.map { route ->
            Candidate(
                sourceSet = route.sources,
                discoveredCameraId = route.discoveredCameraId,
                openCameraId = route.openCameraId,
                streamPhysicalCameraId = route.streamPhysicalCameraId,
                logicalParentCameraId = route.logicalParentCameraId,
                routeKind = route.routeKind,
                minimalMetadata = route.minimalMetadata,
                fullCapabilities = route.fullCapabilities,
                lensFingerprint = route.lensFingerprint,
                trust = route.trust,
            )
        }
        val merged = mergeExactRoute(candidates, environment)
        val primaryKey = merged.canonicalRouteId
        val aliases = routes
            .flatMap { route ->
                listOf(route.asAlias()) + route.aliases
            }
            .distinctBy { alias -> canonicalRouteId(alias.openCameraId, alias.streamPhysicalCameraId) }
            .filterNot { alias ->
                canonicalRouteId(alias.openCameraId, alias.streamPhysicalCameraId) == primaryKey
            }
            .sortedWith(compareBy(
                { routeKindRank(it.routeKind) },
                CameraRouteAlias::openCameraId,
                { it.streamPhysicalCameraId.orEmpty() },
            ))
        return merged.copy(aliases = aliases)
    }

    private fun CameraRoute.asAlias() = CameraRouteAlias(
        discoveredCameraId = discoveredCameraId,
        openCameraId = openCameraId,
        streamPhysicalCameraId = streamPhysicalCameraId,
        logicalParentCameraId = logicalParentCameraId,
        routeKind = routeKind,
        sources = sources,
    )

    private fun mergeMetadata(values: List<MinimalCameraMetadata>): MinimalCameraMetadata {
        val focalLengths = values.flatMap(MinimalCameraMetadata::focalLengthsMm)
            .filter(::positiveFinite)
            .distinct()
            .sorted()
        val rawFormats = values.flatMapTo(mutableSetOf(), MinimalCameraMetadata::rawFormats)
            .filterTo(sortedSetOf(compareBy(StreamFormat::ordinal))) { it.isRawLike }
        val rawSizes = values.flatMap(MinimalCameraMetadata::rawSizes).validDistinctSizes()
        val privatePreviewSizes = values
            .flatMap(MinimalCameraMetadata::privatePreviewSizes)
            .validDistinctSizes()
        val yuvPreviewSizes = values
            .flatMap(MinimalCameraMetadata::yuvPreviewSizes)
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
            values.mapNotNull {
                it.sensorOrientationDegrees?.takeIf { degrees -> degrees in 0..359 }
            },
            Int::toString,
        )
        val facing = chooseEnum(values.map(MinimalCameraMetadata::facing), LensFacing.UNKNOWN)
        val hardware = chooseEnum(values.map(MinimalCameraMetadata::hardwareLevel), HardwareLevel.UNKNOWN)
        val providedFov = chooseVoted(
            values.mapNotNull { it.approximateFieldOfView?.takeIf { fov -> fov.isSane } },
        ) {
            listOf(it.horizontalDegrees, it.verticalDegrees, it.diagonalDegrees, it.focalLengthMm)
                .joinToString(",", transform = ::decimal)
        }
        val rawDeclared = mergeSupport(values.map(MinimalCameraMetadata::rawStreamActuallyDeclared))
            .promoteWhen(rawFormats.isNotEmpty() || rawSizes.isNotEmpty())
        val previewDeclared = mergeSupport(
            values.map(MinimalCameraMetadata::previewStreamActuallyDeclared),
        ).promoteWhen(privatePreviewSizes.isNotEmpty() || yuvPreviewSizes.isNotEmpty())
        return MinimalCameraMetadata(
            facing = facing,
            focalLengthsMm = focalLengths,
            sensorPhysicalSize = physical,
            activeArray = active,
            pixelArraySize = pixel,
            sensorOrientationDegrees = orientation,
            approximateFieldOfView = providedFov ?: calculateFov(physical, focalLengths.firstOrNull()),
            hardwareLevel = hardware,
            backwardCompatibleAdvertised = mergeSupport(
                values.map(MinimalCameraMetadata::backwardCompatibleAdvertised),
            ),
            rawCapabilityAdvertised = mergeSupport(
                values.map(MinimalCameraMetadata::rawCapabilityAdvertised),
            ),
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
            systemCameraAdvertised = mergeSupport(
                values.map(MinimalCameraMetadata::systemCameraAdvertised),
            ),
        )
    }

    private fun MinimalCameraMetadata.normalized(): MinimalCameraMetadata = mergeMetadata(listOf(this))

    private fun chooseFullCapabilities(values: List<FullCameraCapabilities>): FullCameraCapabilities? =
        values.maxWithOrNull(compareBy<FullCameraCapabilities>(
            ::fullCapabilityScore,
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
            append(value.streamConfigurations.orEmpty().sortedWith(compareBy(
                { it.format.ordinal },
                { it.size.width },
                { it.size.height },
            )))
        }
    }

    private fun mergeTrust(
        values: List<CameraRouteTrust>,
        metadata: MinimalCameraMetadata,
    ): CameraRouteTrust {
        val photographicMetadata = metadata.hasPhotographicEvidenceForClassification
        val metadataTrust = when {
            metadata.systemCameraAdvertised == CapabilitySupport.SUPPORTED ->
                CameraMetadataTrust.SYSTEM_ONLY
            values.any { it.metadata == CameraMetadataTrust.METADATA_VALID } ->
                CameraMetadataTrust.METADATA_VALID
            photographicMetadata -> CameraMetadataTrust.METADATA_VALID
            values.any { it.metadata == CameraMetadataTrust.BROKEN } -> CameraMetadataTrust.BROKEN
            values.any { it.metadata == CameraMetadataTrust.ACCESS_DENIED } &&
                values.none { it.metadata == CameraMetadataTrust.DISCOVERED } ->
                CameraMetadataTrust.ACCESS_DENIED
            values.any { it.metadata == CameraMetadataTrust.SYSTEM_ONLY } ->
                CameraMetadataTrust.SYSTEM_ONLY
            values.any { it.metadata == CameraMetadataTrust.METADATA_REJECTED } ->
                CameraMetadataTrust.METADATA_REJECTED
            values.any { it.metadata == CameraMetadataTrust.DISCOVERED } ->
                CameraMetadataTrust.DISCOVERED
            else -> CameraMetadataTrust.UNKNOWN
        }
        val session = when {
            values.any { it.session == CameraSessionTrust.SESSION_VERIFIED } ->
                CameraSessionTrust.SESSION_VERIFIED
            values.any { it.session == CameraSessionTrust.SESSION_REJECTED } ->
                CameraSessionTrust.SESSION_REJECTED
            values.any { it.session == CameraSessionTrust.TRANSIENT_FAILURE } ->
                CameraSessionTrust.TRANSIENT_FAILURE
            else -> CameraSessionTrust.UNKNOWN
        }
        val raw = when {
            values.any { it.raw == CameraRawTrust.RAW_VERIFIED } -> CameraRawTrust.RAW_VERIFIED
            values.any { it.raw == CameraRawTrust.RAW_REJECTED } -> CameraRawTrust.RAW_REJECTED
            values.any { it.raw == CameraRawTrust.TRANSIENT_FAILURE } -> CameraRawTrust.TRANSIENT_FAILURE
            values.any { it.raw == CameraRawTrust.NOT_ADVERTISED } -> CameraRawTrust.NOT_ADVERTISED
            metadata.rawCapabilityAdvertised == CapabilitySupport.UNSUPPORTED &&
                metadata.rawStreamActuallyDeclared == CapabilitySupport.UNSUPPORTED ->
                CameraRawTrust.NOT_ADVERTISED
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
        ) {
            return PhotographicRole.NON_PHOTO_DEPTH to RoleConfidence.STRONG
        }
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

    private fun generateFingerprint(
        candidate: Candidate,
        metadata: MinimalCameraMetadata,
        full: FullCameraCapabilities?,
        environment: CameraEnvironmentFingerprint,
    ): LensFingerprint {
        val optical = stableOpticalParts(metadata, full)
        val stable = metadata.focalLengthsMm.isNotEmpty() &&
            (metadata.sensorPhysicalSize != null || metadata.pixelArraySize != null ||
                metadata.activeArray != null)
        val canonical = buildString {
            append("camera-topology-fingerprint-v2|")
            append(optical)
            val hardwareId = candidate.streamPhysicalCameraId ?: candidate.discoveredCameraId
            append("|hardware=").append(hardwareId)
            if (!stable) {
                append("|build=").append(environment.buildFingerprint.trim())
                append("|open=").append(candidate.openCameraId)
                append("|physical=").append(candidate.streamPhysicalCameraId.orEmpty())
            }
        }
        return LensFingerprint(
            value = (if (stable) "ct2_" else "cf2_") + sha256(canonical),
            strategy = if (stable) {
                FingerprintStrategy.STABLE_METADATA
            } else {
                FingerprintStrategy.DEVICE_SCOPED_FALLBACK
            },
        )
    }

    private fun strongOpticalSignature(metadata: MinimalCameraMetadata): String? {
        if (metadata.focalLengthsMm.isEmpty()) return null
        if (metadata.sensorPhysicalSize == null && metadata.pixelArraySize == null &&
            metadata.activeArray == null
        ) return null
        if (metadata.rawSizes.isEmpty() && metadata.approximateFieldOfView == null) return null
        return stableOpticalParts(metadata, null)
    }

    private fun stableOpticalParts(
        metadata: MinimalCameraMetadata,
        full: FullCameraCapabilities?,
    ): String = buildString {
        append("facing=").append(metadata.facing.name)
        append("|focal=").append(metadata.focalLengthsMm.joinToString(",", transform = ::decimal))
        append("|physical=").append(metadata.sensorPhysicalSize)
        append("|pixel=").append(metadata.pixelArraySize)
        append("|active=").append(metadata.activeArray)
        append("|rawFormats=").append(metadata.rawFormats.sortedBy(StreamFormat::ordinal))
        append("|rawSizes=").append(metadata.rawSizes)
        append("|cfa=").append(full?.capabilities?.colorFilterArrangement?.name ?: "?")
    }

    private fun relationships(routes: List<CameraRoute>): List<LogicalCameraRelationship> = routes
        .flatMap { route ->
            buildList {
                route.logicalParentCameraId?.let { parent ->
                    route.streamPhysicalCameraId?.let { physical -> add(parent to physical) }
                }
                route.aliases.forEach { alias ->
                    alias.logicalParentCameraId?.let { parent ->
                        alias.streamPhysicalCameraId?.let { physical -> add(parent to physical) }
                    }
                }
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
        get() = hasCrediblePhotographicEvidence ||
            rawCapabilityAdvertised == CapabilitySupport.SUPPORTED

    private val MinimalCameraMetadata.hasAnyMetadata: Boolean
        get() = facing != LensFacing.UNKNOWN || focalLengthsMm.isNotEmpty() ||
            sensorPhysicalSize != null || activeArray != null || pixelArraySize != null ||
            sensorOrientationDegrees != null ||
            hardwareLevel != HardwareLevel.UNKNOWN ||
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

    private val routeComparator = compareBy<CameraRoute>(
        { it.minimalMetadata.facing.ordinal },
        { routeKindRank(it.routeKind) },
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
