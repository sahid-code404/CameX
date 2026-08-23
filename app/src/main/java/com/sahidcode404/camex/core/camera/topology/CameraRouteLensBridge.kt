package com.sahidcode404.camex.core.camera.topology

import com.sahidcode404.camex.core.logic.LensMath
import com.sahidcode404.camex.core.model.CameraCapability
import com.sahidcode404.camex.core.model.CapabilitySupport
import com.sahidcode404.camex.core.model.LensCapabilities
import com.sahidcode404.camex.core.model.LensCategory
import com.sahidcode404.camex.core.model.LensDescriptor
import com.sahidcode404.camex.core.model.LensFacing
import com.sahidcode404.camex.core.model.LensIdentity
import com.sahidcode404.camex.core.model.LensNodeKind
import com.sahidcode404.camex.core.model.LensProbeResult
import com.sahidcode404.camex.core.model.LensUsability
import com.sahidcode404.camex.core.model.ProbeFailureKind
import com.sahidcode404.camex.core.model.ProbeOutcome
import com.sahidcode404.camex.core.model.ProbeStage
import com.sahidcode404.camex.core.model.ProbeStageResult
import com.sahidcode404.camex.core.model.RawAccess
import com.sahidcode404.camex.core.model.StreamConfiguration
import com.sahidcode404.camex.core.model.StreamFormat

/** Converts canonical topology into the existing runtime/UI projection without Camera2 objects. */
fun CameraRoute.toLensDescriptor(): LensDescriptor {
    val capabilities = mergedLensCapabilities()
    return LensDescriptor(
        identity = LensIdentity(
            publicCameraId = openCameraId,
            physicalCameraId = streamPhysicalCameraId,
            logicalParentCameraId = logicalParentCameraId,
            nodeKind = when (routeKind) {
                CameraRouteKind.LOGICAL_PARENT -> LensNodeKind.LOGICAL
                CameraRouteKind.LOGICAL_PHYSICAL_MEMBER -> LensNodeKind.PHYSICAL
                else -> LensNodeKind.STANDALONE
            },
        ),
        facing = minimalMetadata.facing,
        capabilities = capabilities,
        fingerprint = lensFingerprint,
        usability = trust.toLensUsability(
            rawAccess = capabilities.rawAccess,
            role = role,
            hasCrediblePhotographicEvidence = hasCrediblePhotographicEvidence,
        ),
        category = role.toLensCategory(),
        probeResult = trust.toProbeResult(),
    )
}

/**
 * Builds a topology route from a legacy/runtime descriptor. Discovery sources and trust can be
 * supplied explicitly because LensDescriptor has nowhere to carry those topology annotations.
 */
fun LensDescriptor.toCameraRoute(
    source: CameraDiscoverySource,
    routeKind: CameraRouteKind = inferredRouteKind(source),
    trust: CameraRouteTrust = inferredTrust(),
): CameraRoute {
    val physical = identity.streamPhysicalCameraId
    val open = identity.openCameraId
    val metadata = toMinimalCameraMetadata()
    return CameraRoute(
        canonicalRouteId = canonicalRouteId(open, physical),
        discoveredCameraId = physical ?: identity.publicCameraId,
        openCameraId = open,
        streamPhysicalCameraId = physical,
        logicalParentCameraId = identity.logicalParentCameraId,
        routeKind = routeKind,
        sources = setOf(source),
        minimalMetadata = metadata,
        fullCapabilities = FullCameraCapabilities(capabilities = capabilities, complete = true),
        lensFingerprint = fingerprint,
        role = category.toPhotographicRole(usability),
        roleConfidence = if (category.name.endsWith("UNKNOWN")) {
            RoleConfidence.WEAK
        } else {
            RoleConfidence.STRONG
        },
        trust = trust,
    )
}

/**
 * Lossless update path for runtime enrichment: route/source/trust/alias annotations stay owned by
 * the canonical route while LensDescriptor-owned metadata is refreshed.
 */
fun CameraRoute.withLensDescriptor(descriptor: LensDescriptor): CameraRoute {
    val refreshed = descriptor.toCameraRoute(
        source = sources.firstOrNull { it != CameraDiscoverySource.CACHE }
            ?: CameraDiscoverySource.CACHE,
        routeKind = routeKind,
        trust = trust,
    )
    return copy(
        minimalMetadata = refreshed.minimalMetadata,
        fullCapabilities = refreshed.fullCapabilities,
        lensFingerprint = refreshed.lensFingerprint ?: lensFingerprint,
        role = refreshed.role,
        roleConfidence = refreshed.roleConfidence,
    )
}

private fun CameraRoute.mergedLensCapabilities(): LensCapabilities {
    val base = fullCapabilities?.capabilities ?: LensCapabilities()
    val derivedStreams = buildList {
        val rawFormat = minimalMetadata.rawFormats
            .filter { it.name.startsWith("RAW") }
            .minByOrNull(StreamFormat::ordinal)
            ?: StreamFormat.RAW_SENSOR
        minimalMetadata.rawSizes.forEach { size ->
            add(StreamConfiguration(rawFormat, size))
        }
        minimalMetadata.privatePreviewSizes.forEach { size ->
            add(StreamConfiguration(StreamFormat.PRIVATE, size))
        }
        minimalMetadata.yuvPreviewSizes.forEach { size ->
            add(StreamConfiguration(StreamFormat.YUV_420_888, size))
        }
    }
    val streams = (base.streamConfigurations.orEmpty() + derivedStreams)
        .distinctBy { Triple(it.format, it.size, it.maximumResolution) }
        .takeIf(List<StreamConfiguration>::isNotEmpty)
    val reported = base.reportedCapabilities.orEmpty().toMutableSet().apply {
        if (minimalMetadata.backwardCompatibleAdvertised == CapabilitySupport.SUPPORTED) {
            add(CameraCapability.BACKWARD_COMPATIBLE)
        }
        if (minimalMetadata.rawCapabilityAdvertised == CapabilitySupport.SUPPORTED) {
            add(CameraCapability.RAW)
        }
        if (minimalMetadata.depthEvidence == CapabilitySupport.SUPPORTED) {
            add(CameraCapability.DEPTH_OUTPUT)
        }
        if (minimalMetadata.monochromeEvidence == CapabilitySupport.SUPPORTED) {
            add(CameraCapability.MONOCHROME)
        }
        if (minimalMetadata.systemCameraAdvertised == CapabilitySupport.SUPPORTED) {
            add(CameraCapability.SYSTEM_CAMERA)
        }
    }.takeIf(Set<CameraCapability>::isNotEmpty)
    return base.copy(
        focalLengthsMm = base.focalLengthsMm ?: minimalMetadata.focalLengthsMm.takeIf(List<Double>::isNotEmpty),
        sensorPhysicalSize = base.sensorPhysicalSize ?: minimalMetadata.sensorPhysicalSize,
        pixelArraySize = base.pixelArraySize ?: minimalMetadata.pixelArraySize,
        activeArray = base.activeArray ?: minimalMetadata.activeArray,
        sensorOrientationDegrees = base.sensorOrientationDegrees
            ?: minimalMetadata.sensorOrientationDegrees,
        hardwareLevel = base.hardwareLevel.takeUnless { it.name == "UNKNOWN" }
            ?: minimalMetadata.hardwareLevel,
        reportedCapabilities = reported,
        flags = base.flags.copy(
            backwardCompatible = base.flags.backwardCompatible.preferKnown(
                minimalMetadata.backwardCompatibleAdvertised,
            ),
            raw = base.flags.raw.preferKnown(minimalMetadata.rawCapabilityAdvertised),
            depthOutput = base.flags.depthOutput.preferKnown(minimalMetadata.depthEvidence),
            systemCamera = base.flags.systemCamera.preferKnown(
                minimalMetadata.systemCameraAdvertised,
            ),
        ),
        rawAccess = when {
            base.rawAccess != RawAccess.UNKNOWN -> base.rawAccess
            minimalMetadata.rawStreamActuallyDeclared != CapabilitySupport.SUPPORTED -> RawAccess.UNKNOWN
            streamPhysicalCameraId != null -> RawAccess.PHYSICAL_STREAM
            else -> RawAccess.DIRECT
        },
        streamConfigurations = streams,
    )
}

private fun LensDescriptor.toMinimalCameraMetadata(): MinimalCameraMetadata {
    val raw = capabilities.portableRawConfigurations
    val preview = capabilities.streamConfigurations.orEmpty().filter {
        it.format == StreamFormat.PRIVATE || it.format == StreamFormat.YUV_420_888
    }
    val reported = capabilities.reportedCapabilities.orEmpty()
    return MinimalCameraMetadata(
        facing = facing,
        focalLengthsMm = capabilities.focalLengthsMm.orEmpty(),
        sensorPhysicalSize = capabilities.sensorPhysicalSize,
        activeArray = capabilities.activeArray,
        pixelArraySize = capabilities.pixelArraySize,
        sensorOrientationDegrees = capabilities.sensorOrientationDegrees,
        approximateFieldOfView = LensMath.fieldOfView(capabilities),
        hardwareLevel = capabilities.hardwareLevel,
        backwardCompatibleAdvertised = capabilities.flags.backwardCompatible,
        rawCapabilityAdvertised = capabilities.flags.raw,
        rawStreamActuallyDeclared = when {
            raw.isNotEmpty() -> CapabilitySupport.SUPPORTED
            capabilities.streamConfigurations != null -> CapabilitySupport.UNSUPPORTED
            else -> CapabilitySupport.UNKNOWN
        },
        rawFormats = raw.mapTo(mutableSetOf(), StreamConfiguration::format),
        rawSizes = raw.map(StreamConfiguration::size).distinct(),
        previewStreamActuallyDeclared = when {
            preview.isNotEmpty() -> CapabilitySupport.SUPPORTED
            capabilities.streamConfigurations != null -> CapabilitySupport.UNSUPPORTED
            else -> CapabilitySupport.UNKNOWN
        },
        privatePreviewSizes = preview
            .filter { it.format == StreamFormat.PRIVATE }
            .map(StreamConfiguration::size)
            .distinct(),
        yuvPreviewSizes = preview
            .filter { it.format == StreamFormat.YUV_420_888 }
            .map(StreamConfiguration::size)
            .distinct(),
        depthEvidence = capabilities.flags.depthOutput,
        infraredEvidence = if (capabilities.colorFilterArrangement?.name == "NIR") {
            CapabilitySupport.SUPPORTED
        } else {
            CapabilitySupport.UNKNOWN
        },
        monochromeEvidence = if (CameraCapability.MONOCHROME in reported) {
            CapabilitySupport.SUPPORTED
        } else {
            CapabilitySupport.UNKNOWN
        },
        systemCameraAdvertised = capabilities.flags.systemCamera,
    )
}

private fun LensDescriptor.inferredRouteKind(source: CameraDiscoverySource): CameraRouteKind = when {
    facing == LensFacing.EXTERNAL -> CameraRouteKind.EXTERNAL
    identity.streamPhysicalCameraId != null -> CameraRouteKind.LOGICAL_PHYSICAL_MEMBER
    identity.nodeKind == LensNodeKind.LOGICAL -> CameraRouteKind.LOGICAL_PARENT
    source == CameraDiscoverySource.NDK_DEEP -> CameraRouteKind.DEEP_NDK_DIRECT
    source == CameraDiscoverySource.NDK_ADVERTISED -> CameraRouteKind.NDK_DIRECT
    else -> CameraRouteKind.PUBLIC_DIRECT
}

private fun LensDescriptor.inferredTrust(): CameraRouteTrust {
    val metadata = when (usability) {
        LensUsability.SYSTEM_ONLY -> CameraMetadataTrust.SYSTEM_ONLY
        LensUsability.INACCESSIBLE -> CameraMetadataTrust.ACCESS_DENIED
        LensUsability.BROKEN -> CameraMetadataTrust.BROKEN
        LensUsability.UNKNOWN -> CameraMetadataTrust.DISCOVERED
        else -> CameraMetadataTrust.METADATA_VALID
    }
    val session = when {
        probeResult?.previewVerified == true -> CameraSessionTrust.SESSION_VERIFIED
        probeResult?.lastResult?.outcome == ProbeOutcome.FAILURE -> CameraSessionTrust.SESSION_REJECTED
        else -> CameraSessionTrust.UNKNOWN
    }
    val raw = when {
        probeResult?.rawFrameTested == true -> CameraRawTrust.RAW_VERIFIED
        capabilities.rawAccess == RawAccess.NONE -> CameraRawTrust.NOT_ADVERTISED
        else -> CameraRawTrust.UNKNOWN
    }
    return CameraRouteTrust(metadata, session, raw)
}

private fun CameraRouteTrust.toLensUsability(
    rawAccess: RawAccess,
    role: PhotographicRole,
    hasCrediblePhotographicEvidence: Boolean,
): LensUsability = when {
    metadata == CameraMetadataTrust.SYSTEM_ONLY || role == PhotographicRole.SYSTEM_ONLY ->
        LensUsability.SYSTEM_ONLY
    metadata == CameraMetadataTrust.ACCESS_DENIED || role == PhotographicRole.INACCESSIBLE ->
        LensUsability.INACCESSIBLE
    metadata == CameraMetadataTrust.BROKEN || role == PhotographicRole.BROKEN ||
        session == CameraSessionTrust.SESSION_REJECTED -> LensUsability.BROKEN
    role == PhotographicRole.NON_PHOTO_DEPTH || role == PhotographicRole.NON_PHOTO_TOF ||
        role == PhotographicRole.NON_PHOTO_IR -> LensUsability.DEPTH_AUXILIARY
    session != CameraSessionTrust.SESSION_VERIFIED && hasCrediblePhotographicEvidence ->
        LensUsability.PHOTOGRAPHIC_CANDIDATE
    session != CameraSessionTrust.SESSION_VERIFIED -> LensUsability.UNKNOWN
    raw == CameraRawTrust.RAW_REJECTED || raw == CameraRawTrust.NOT_ADVERTISED ->
        LensUsability.PROCESSED_ONLY
    raw == CameraRawTrust.RAW_VERIFIED && rawAccess == RawAccess.PHYSICAL_STREAM ->
        LensUsability.RAW_PHYSICAL_STREAM
    raw == CameraRawTrust.RAW_VERIFIED && rawAccess == RawAccess.MAXIMUM_RESOLUTION ->
        LensUsability.RAW_MAX_RESOLUTION
    raw == CameraRawTrust.RAW_VERIFIED -> LensUsability.RAW_NATIVE
    session == CameraSessionTrust.SESSION_VERIFIED -> LensUsability.PROCESSED_ONLY
    else -> LensUsability.UNKNOWN
}

private fun CameraRouteTrust.toProbeResult(): LensProbeResult? {
    val stages = buildList {
        when (metadata) {
            CameraMetadataTrust.METADATA_VALID -> add(
                ProbeStageResult(ProbeStage.METADATA_VALID, ProbeOutcome.SUCCESS),
            )
            CameraMetadataTrust.METADATA_REJECTED,
            CameraMetadataTrust.ACCESS_DENIED,
            CameraMetadataTrust.SYSTEM_ONLY,
            CameraMetadataTrust.BROKEN,
            -> add(
                ProbeStageResult(
                    ProbeStage.METADATA_VALID,
                    ProbeOutcome.FAILURE,
                    detail = failure?.detail,
                    failureKind = failure?.kind.toProbeFailureKind(),
                ),
            )
            CameraMetadataTrust.UNKNOWN,
            CameraMetadataTrust.DISCOVERED,
            -> Unit
        }
        when (session) {
            CameraSessionTrust.SESSION_VERIFIED -> add(
                ProbeStageResult(ProbeStage.PREVIEW_SUCCESS, ProbeOutcome.SUCCESS),
            )
            CameraSessionTrust.SESSION_REJECTED -> add(
                ProbeStageResult(
                    ProbeStage.PREVIEW_SUCCESS,
                    ProbeOutcome.FAILURE,
                    detail = failure?.detail,
                    failureKind = failure?.kind.toProbeFailureKind(),
                ),
            )
            CameraSessionTrust.UNKNOWN,
            CameraSessionTrust.TRANSIENT_FAILURE,
            -> Unit
        }
        when (raw) {
            CameraRawTrust.RAW_VERIFIED -> add(
                ProbeStageResult(ProbeStage.RAW_TEST_SUCCESS, ProbeOutcome.SUCCESS),
            )
            CameraRawTrust.RAW_REJECTED -> add(
                ProbeStageResult(
                    ProbeStage.RAW_TEST_SUCCESS,
                    ProbeOutcome.FAILURE,
                    detail = failure?.detail,
                    failureKind = failure?.kind.toProbeFailureKind(),
                ),
            )
            CameraRawTrust.UNKNOWN,
            CameraRawTrust.NOT_ADVERTISED,
            CameraRawTrust.TRANSIENT_FAILURE,
            -> Unit
        }
    }
    return stages.takeIf(List<ProbeStageResult>::isNotEmpty)?.let(::LensProbeResult)
}

private fun CameraRouteFailureKind?.toProbeFailureKind(): ProbeFailureKind? = when (this) {
    CameraRouteFailureKind.ACCESS_DENIED -> ProbeFailureKind.ACCESS_DENIED
    CameraRouteFailureKind.SYSTEM_ONLY -> ProbeFailureKind.SYSTEM_RESTRICTED
    CameraRouteFailureKind.INVALID_METADATA,
    CameraRouteFailureKind.MALFORMED_VENDOR_METADATA,
    -> ProbeFailureKind.INVALID_METADATA
    CameraRouteFailureKind.SESSION_CONFIGURATION_UNSUPPORTED -> ProbeFailureKind.SESSION_CONFIGURATION
    CameraRouteFailureKind.DISCONNECTED -> ProbeFailureKind.DISCONNECTED
    CameraRouteFailureKind.TIMEOUT -> ProbeFailureKind.TIMEOUT
    CameraRouteFailureKind.SERVICE_ERROR -> ProbeFailureKind.SERVICE_ERROR
    CameraRouteFailureKind.RAW_CONFIGURATION_UNSUPPORTED,
    CameraRouteFailureKind.CAMERA_IN_USE,
    CameraRouteFailureKind.MAX_CAMERAS_IN_USE,
    CameraRouteFailureKind.APP_BACKGROUNDED,
    CameraRouteFailureKind.UNKNOWN,
    -> ProbeFailureKind.UNKNOWN
    null -> null
}

private fun PhotographicRole.toLensCategory(): LensCategory = LensCategory.entries
    .firstOrNull { it.name == name }
    ?: LensCategory.entries.firstOrNull {
        it.name == name.removePrefix("PHOTOGRAPHIC_")
    }
    ?: LensCategory.entries.first { it.name.endsWith("UNKNOWN") }

private fun LensCategory.toPhotographicRole(usability: LensUsability): PhotographicRole {
    PhotographicRole.entries.firstOrNull { it.name == name }?.let { return it }
    PhotographicRole.entries.firstOrNull { it.name == "PHOTOGRAPHIC_$name" }?.let { return it }
    return when (usability) {
        LensUsability.DEPTH_AUXILIARY -> PhotographicRole.NON_PHOTO_DEPTH
        LensUsability.SYSTEM_ONLY -> PhotographicRole.SYSTEM_ONLY
        LensUsability.INACCESSIBLE -> PhotographicRole.INACCESSIBLE
        LensUsability.BROKEN -> PhotographicRole.BROKEN
        else -> PhotographicRole.PHOTOGRAPHIC_UNKNOWN
    }
}

private fun CapabilitySupport.preferKnown(fallback: CapabilitySupport): CapabilitySupport =
    if (this == CapabilitySupport.UNKNOWN) fallback else this
